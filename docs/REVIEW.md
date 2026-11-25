# DiskQuilt — multi-model review

Eight review agents, eight model calls on the same **kenari** provider, identical evidence (source
plus five screenshots from a TECNO LI7 on Android 15), each assigned a different aspect so their
blind spots do not overlap. Raw reports and screenshots are kept privately: they quote real
file names from the test device.

| # | Aspect | Model | Report |
|---|---|---|---|
| 1 | Visual hierarchy, typography, spacing | `gpt-5-6-luna` | private |
| 2 | User flows, discoverability, friction | `gpt-6-luna` | private |
| 3 | Missing features vs WinDirStat / WizTree | `deepseek-v4-1-flash` | private |
| 4 | Performance, memory, responsiveness | `deepseek-v4-1-flash` | private |
| 5 | Architecture, state, code quality | `gpt-6-luna` | private |
| 6 | Accessibility, i18n, dark mode | `gpt-5-6-luna` | private |
| 7 | Data safety, permissions, destructive actions | `inkling-2-mini-uncensored` | private |
| 8 | Product positioning, onboarding, trust | `muse-ember-1-0` | private |

`glm-5-3-flash`, `qwen3-8-27b-uncensored` and `mimo-v2-6-flash` were the first choices for aspects
1, 4 and 5 but their upstreams returned `all_providers_failed` / dropped the connection on every
attempt, so those aspects were re-run on the models above. Six distinct models still covered the
eight aspects; the harness that produced this is `tools/review.py` and can be re-run per aspect.

---

## P0 — ship-blocking

### 1. The numbers do not reconcile, and nothing on screen says why
*Flagged by: product, features, ux, safety.*

Home says **215.4 GB used**; the result says **105.7 GB**; the footer says "2 denied".
`Android/data` alone is 14 GB and the remaining ~96 GB is other apps' private storage.
`ScanResult.errorSamples` is collected but never shown, so "2 denied" names nothing.

**Fix.** Carry `Volume.usedBytes` into `ScanState.Done` and print one line on both screens:
`Read 105.7 of 215.4 GB used · the rest is other apps' private data and Android/data, which Android
hides from every app without root`. Add the denied paths from `errorSamples`. Optionally draw a
grey "not visible to apps" tile in the treemap so the areas sum to the volume.

### 2. First-run permission ask looks like malware
*Flagged by: product, ux.*

`MANAGE_EXTERNAL_STORAGE` is requested by jumping straight to a bare system toggle, with no steps,
no reason, and a second "App settings" button that duplicates it (`HomeScreen.kt`,
`MainActivity.requestAccess`). Volumes can render empty while access is missing.

**Fix.** Inline 3-step explainer (open settings → enable "Allow management of all files" → return),
state that processing is on-device with no account and no upload, keep volume cards visible but
disabled, re-check on `ON_RESUME`, delete the duplicate button.

### 3. The root card is the first thing a user sees, and it probes `su` uninvited
*Flagged by: product.*

`DeepScanCard` sits above Volumes; `ScanViewModel.init` and every `ON_RESUME` call
`RootShell.available()`, which runs `su -c id` and can pop a Magisk/KernelSU prompt on a rooted phone.

**Fix.** Collapse to "Advanced (root only)" below the volume list; probe only when expanded or
toggled; disclose that checking may trigger a root prompt; drop "Check again".

### 4. Scanner allocates ~3 objects per filesystem entry
*Flagged by: performance.*

`DiskScanner` does `newDirectoryStream(...).toList()`, `child.toString()`, and
`Files.readAttributes(...)` per entry — ~400k allocations and 2 JNI hops per entry on the measured
127.9k files. On an SD card at 2–5 ms/stat a 1M-file volume takes 40–80 min, and cancellation is
only polled every 2048 entries.

**Fix.** Iterate the `DirectoryStream` directly, use `child.fileName`, and replace `readAttributes`
with `android.system.Os.lstat(path, reusedStructStat)`. Stat one directory's entries on a small
pool, `store.add` serially. Poll cancellation every 256 entries. Expected 2–4× throughput and
near-zero per-entry garbage.

### 5. Treemap layout is O(n²) and runs inside `DrawScope`
*Flagged by: performance.*

`Treemap.worstRatio` rescans `from..to` for min/max on every row extension, and `childrenSorted`
fully sorts every visited directory even though only `area/minArea` cells can ever be drawn. All of
it executes in the draw phase, blocking frames.

**Fix.** Track running min/max incrementally (O(n) per row), replace full sorts with top-K
selection, and compute the layout in a `Dispatchers.Default` coroutine published through `remember`.

---

## P1 — significant

### 6. Treemap colour is meaningless and its labels are unreadable
*Flagged by: product, ux, visual, a11y, performance.*

`cellColor` hashes the node index (`node * 137.508`), so colours are random per node with no legend;
labels are hard-coded near-black (`Color(0xFF0B0F12)`) on saturated fills and only leaves get
labelled, so a huge folder shows a bare name and no size; the `0.75 px` gaps vanish at small sizes.

**Fix.** Colour by file category (video / image / audio / app / document / other) with a legend,
choose label ink from measured luminance, label the largest visible cells with name + size, and
aggregate sub-threshold cells into an "Other" tile.

### 7. Treemap cache key can return a stale tree
*Flagged by: performance.*

`key = (root shl 40) xor (w shl 20) xor h xor (version shl 56)` — `version` occupies bits 56–63,
overlapping `root`'s bits, so a version bump can cancel a root change. It is also a single
file-level slot, so tab and root switches recompute every time.

**Fix.** Delete the globals; use `remember(root, width, height, version) { Treemap.layout(...) }`
and pass `cells` to both the canvas and the tap handler.

### 8. Every frame redoes per-cell work
*Flagged by: performance.*

Each draw calls `Color.hsv()`, allocates a `HashSet<Int>` (boxing up to 3000 integers), runs
`formatBytes` per label, and measures text through the default 8-entry `TextMeasurer` cache.

**Fix.** Precompute ARGB colours and `TextLayoutResult`s at layout time, reuse a `BooleanArray(count)`
for the label filter, and use `rememberTextMeasurer(cacheSize = 512)`.

### 9. `largestFiles` re-sorts on every insert, on the main thread
*Flagged by: features, performance.*

`best.sortByDescending` fires each time a file beats the current minimum, and the call is made from
`remember(version)` in composition. It also returns unsorted when fewer than `limit` files exist.

**Fix.** Bounded `PriorityQueue` then one sort; compute in `LaunchedEffect` on `Dispatchers.Default`
and cache by version.

### 10. `NodeDetailsDialog` shows the parent path
*Flagged by: product.*

`store.pathOf(store.parents[node])` labels every item with its folder. One-line fix:
`store.pathOf(node)`.

### 11. Cancelled scans are published as `Done`; failures vanish
*Flagged by: product, ux, architecture.*

`ScanResult.cancelled` is set but the UI ignores it, so a cancelled scan shows partial bytes as a
finished result. `ScanState.Failed` re-renders Home with no message. A cancelled job can also still
mutate the store and publish after `reset()`.

**Fix.** Distinct "Incomplete — cancelled" banner; a real error screen with the message and Retry;
give each scan a job id and refuse to publish state from a superseded scan.

### 12. Delete is irreversible, unverified and reports the wrong number
*Flagged by: safety, ux, architecture.*

`deleteNode` deletes from the scan-time tree without re-stating the path, treats
`deleteRecursively() == false` as total failure (so a partial delete leaves the UI claiming the old
size), reports the scan-time size rather than bytes actually freed, and never checks whether the app
uid can write the path at all.

**Fix.** Re-stat before confirming; delete file-by-file and return `{deleted, failed, freed}`;
reconcile the subtree from the result; distinguish "not found" / "permission denied" / "partial";
say "permanent, no trash" in the dialog.

### 13. No search, no file-type breakdown, no multi-select
*Flagged by: features.*

On a 127.9k-entry tree the only retrieval path is drilling by size. WinDirStat's extension list and
WizTree's type view have no equivalent, and cleaning five 672 MB WhatsApp backups takes five
tap-dialog cycles.

**Fix (ranked by value/effort).** Search field filtering `names[]` linearly (sub-100 ms at 127.9k);
a "Types" tab built from one `HashMap<extension, bytes>` pass; long-press selection mode with batch
delete/share; sort options beyond size.

### 14. Accessibility: the canvas and the volume button are invisible to TalkBack
*Flagged by: a11y.*

A `Canvas` with `pointerInput` exposes nothing; `VolumeRow`'s "Scan" is a styled `Box`, so only the
whole card is focusable and the apparent button has no label.

**Fix.** Per-cell semantics or an accessible companion list; make the trailing action a real
`Button` with a 48 dp target; give the row one clear label.

### 15. No i18n: hardcoded English, `Locale.US` everywhere
*Flagged by: a11y, product.*

Every visible string is a literal in `HomeScreen.kt` / `ResultScreen.kt`; `Format.kt` and the date
formatter pin `Locale.US`, while the device is Indonesian — the system volume label already arrives
translated ("Penyimpanan internal bersama") next to English UI.

**Fix.** Move strings to `strings.xml` with an `id` translation; format numbers and dates with the
default locale; map known volume labels to friendly names.

### 16. No progress estimate for a 60 s scan
*Flagged by: product, ux.*

Indeterminate bar, no throughput, no ETA, no wake-lock, no indication that a second (root) pass may
follow, and cancellation gives no feedback.

**Fix.** Files/s and MB/s, ETA from throughput, current top-level folder only, keep-screen-on,
explicit "phase 2: deep scan", "cancelled at X" state.

### 17. Nothing survives leaving the result
*Flagged by: ux, product.*

Back to Home destroys the scan; no recent-scan summary, no last path/tab, no history or delta.

**Fix.** Persist a small summary (volume, bytes, file count, timestamp) plus last focus/tab; show
"Last scan: 105.7 GB, 3 days ago — View / Rescan".

### 18. Deep-scan shell handling is unchecked
*Flagged by: safety, architecture.*

`find` stderr is discarded (`2>/dev/null`), the exit code is never read, there is no timeout and no
cancellation, and a tab or newline in a filename breaks the line protocol. `startsWith(targetPath)`
also accepts sibling prefixes.

**Fix.** Capture exit status and stderr, return explicit partial/failed status, pass the cancel flag
into `expand`, terminate the process on cancel, use a NUL-delimited protocol, and match paths on
`targetPath + "/"` boundaries.

### 19. ViewModel owns too much
*Flagged by: architecture.*

`ScanViewModel` runs scanning, root-shell access, deletion, intents, FileProvider URIs and MIME
guessing. `Failed` drops the store and loses the error detail.

**Fix.** Split scan/deep-scan, file actions and formatting into services; expose typed UI errors;
keep the store alive across failures.

---

## P2 — polish

| # | Issue | Source |
|---|---|---|
| 20 | `NodeStore` costs ~95–110 B/entry; `grow()` doubles transiently and capacity can stay at 2× `count` | performance |
| 21 | Byte units divide by 1024 but are labelled GB; Settings uses decimal GB | product |
| 22 | `Theme.kt` computes `dark` and never uses it; forces dark scheme on Android 10+ | a11y |
| 23 | Empty states are bare text ("Empty", "No files") with no next action | visual |
| 24 | Details dialog packs five actions in one row; will overflow in longer locales | visual, product |
| 25 | Contents rows use a 10 dp colour square with no file-type icon or text | product |
| 26 | Breadcrumb `›` is not RTL-aware; tap targets are text-height | a11y |
| 27 | Canvas labels use a fixed `11.sp`, so they ignore font scale | a11y |
| 28 | `file_paths.xml` exposes `<root-path path="/">`; narrow it to the scanned roots | safety |
| 29 | `NodeStore` public mutable arrays and index-unsafe methods; `findByPath` uses raw prefix match | architecture |
| 30 | No About screen, version, licence or privacy stance, for an app that asks for all-files access and probes `su` | product |
| 31 | Home spacing is unstable: `LazyColumn(weight(1f))` pushes the manual path field to the bottom | visual |
| 32 | Typography is uniformly heavy; almost every label is `SemiBold`/`Bold` | visual |
| 33 | Tab row boundary is weak: thin indicator, same green as header | visual |
| 34 | `openNode`/`shareNode` return `Boolean` that nobody checks — silent failure | product, safety |

## Missing features, ranked

From the feature review, by value/effort:

| Capability | Value | Effort | Verdict |
|---|---|---|---|
| Search across the scanned tree | Very high | M | Table stakes |
| File-type breakdown | Very high | S | Table stakes |
| Accounting for unscanned space | Very high | S | Table stakes |
| Multi-select + batch delete/share | High | M | Table stakes |
| Sort options (name / date / type) | High | S | Table stakes |
| Filters (size, age, extension, path) | High | M | Table stakes |
| Duplicate detection | Very high | M | Differentiator |
| App-aware per-package view | High | M | Table stakes on Android |
| Quick presets (WhatsApp / DCIM / Downloads) | High | S | Table stakes |
| Largest beyond 300 | Medium | S | Table stakes |
| Export / share a report | Medium | S | Nice-to-have |
| Scan history and size trend | Medium | M | Nice-to-have |
| Treemap pinch-zoom / pan | Medium | M | Nice-to-have |
| Scheduled background scan | Low | M | Nice-to-have |

## What the reviewers agreed is already right

- The array-backed `NodeStore` with parent-before-child BFS ordering: one reverse pass propagates
  sizes, no per-node objects, no recursion.
- Symlinks recorded with `NOFOLLOW_LINKS` and never queued — no cycle hang, no double counting.
- Treemap / Contents / Largest maps onto WinDirStat's mental model, and Contents' proportion bars
  read better than WinDirStat's bare columns on a phone.
- Breadcrumb + `BackHandler` popping one level and stopping at the scan root instead of finishing
  the activity.
- The honest explanation of why `Android/data` is invisible, and the volume cards showing
  used/total/free before committing to a scan.
- Squarified layout maths is textbook-correct, and the depth/area/cell caps bound the work.

## Suggested order

1. **Trust (P0 1–3):** reconciliation line, permission explainer, demote the root card.
2. **Correctness (P1 10–12):** details path, cancelled/failed states, delete semantics.
3. **Treemap (P1 6–9):** category colours + legend, keyed `remember`, precomputed draw data,
   O(n) layout off the main thread.
4. **Scanner (P0 4):** `Os.lstat`, no `toList()`, cancellable in 256-entry chunks.
5. **Reach (P1 13):** search, Types tab, multi-select.
6. **Accessibility and i18n (P1 14–15):** canvas semantics, real buttons, `strings.xml` + `id`.
7. **Retention (P1 17):** last-scan summary and restored location.
