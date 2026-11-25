"""Multi-model review harness for DiskQuilt.

Each reviewer is a different model on the same kenari provider, given the same evidence
(source + screenshots) but a distinct review aspect, so the reports are not correlated.
"""
import base64
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

def _load_dotenv() -> None:
    """Reads tools/.env (or the project's .env) without adding a dependency."""
    here = os.path.dirname(os.path.abspath(__file__))
    for candidate in (os.path.join(here, ".env"), os.path.join(os.path.dirname(here), ".env")):
        if not os.path.isfile(candidate):
            continue
        with open(candidate, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
        return


_load_dotenv()

# Provider details live in the environment / gitignored .env, never in the repository.
BASE = os.environ.get("KENARI_BASE_URL", "").rstrip("/")
KEY = os.environ.get("KENARI_API_KEY", "")
if not BASE or not KEY:
    raise SystemExit(
        "Set KENARI_BASE_URL and KENARI_API_KEY (copy tools/.env.example to tools/.env)"
    )
API = BASE + "/chat/completions"
ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "..")
SHOTS = os.path.join(APP, "docs", "review_shots", "web")
OUT = os.path.join(APP, "docs", "review")

ALL_FILES = [
    "app/src/main/java/dev/handywibowo/diskquilt/ui/HomeScreen.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/ui/ResultScreen.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/ui/TreemapCanvas.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/ui/Theme.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/ui/Format.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/ui/ScanViewModel.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/MainActivity.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/scan/NodeStore.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/scan/DiskScanner.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/deep/DeepScan.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/deep/ShellExecutor.kt",
    "app/src/main/java/dev/handywibowo/diskquilt/treemap/Treemap.kt",
]

SHOT_FILES = [
    ("01_home.png", "Home screen: title, deep scan card, volume cards, manual path field, app settings button"),
    ("02_treemap.png", "Treemap after scanning internal storage"),
    ("03_contents.png", "Contents tab: children sorted by size"),
    ("04_largest.png", "Largest tab: biggest files"),
    ("05_details.png", "File details dialog"),
]


def build_source(files=None) -> str:
    parts = []
    for rel in (files or ALL_FILES):
        with open(os.path.join(APP, rel), encoding="utf-8") as fh:
            parts.append(f"===== {rel} =====\n{fh.read()}")
    return "\n\n".join(parts)


def shot_b64(name: str) -> str:
    """Downscaled JPEGs: the full-res PNGs push the request past the provider's size limit."""
    jpg = os.path.join(SHOTS, name.replace(".png", ".jpg"))
    with open(jpg, "rb") as fh:
        return base64.b64encode(fh.read()).decode()


CONTEXT = """You are reviewing DiskQuilt, an Android disk-usage analyzer (WinDirStat / WizTree style)
built with Kotlin + Jetpack Compose, minSdk 26, targetSdk 35.

What it does today:
- Scans a volume or a typed folder with a breadth-first java.nio.file walk into a compact
  array-backed NodeStore (parallel primitive arrays, no per-entry objects).
- Shows the result in three tabs: Treemap (squarified layout, tap folder to drill in),
  Contents (direct children sorted by size with proportion bars), Largest (top 300 files).
- File details dialog: size, % of scan, mtime, path; actions Open / Folder / Share / Delete.
- Live scan progress with cancel; breadcrumb navigation; back pops one level.
- Optional root deep scan to reach Android/data and Android/obb.
- Verified on a real TECNO LI7 (Android 15): 127.9K files / 940 folders / 105.7 GB in ~60 s.
  Android hides Android/data (14 GB) and Android/obb from every app without root, so the scan
  total stays below the volume's used space (215 GB).

Known constraints you must respect (do not propose impossible things):
- No Play Store distribution planned yet; it is a local debug build.
- Android 11+ requires MANAGE_EXTERNAL_STORAGE for this kind of app.
- Other apps' private /data/data is unreachable without root.

The screenshots are from a real 1080x2436 device with Indonesian locale.
"""

ASPECTS = [
    dict(
        key="visual_hierarchy",
        files=['app/src/main/java/dev/handywibowo/diskquilt/ui/HomeScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/ResultScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/TreemapCanvas.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/Theme.kt'],
        model="glm-5-3-flash",
        shots=["01_home.png", "02_treemap.png", "05_details.png"],
        title="Visual hierarchy, typography and spacing",
        brief="""Judge the UI as a designer. Focus on: visual hierarchy on the home screen (is the
primary action obvious?), typographic scale and weight contrast, spacing rhythm, card styling,
colour usage, whether the treemap is legible, whether the tabs read as tabs, empty states, and how
the details dialog reads. Name concrete numbers (dp, sp, hex) where you propose a change.""",
    ),
    dict(
        key="ux_flows",
        files=['app/src/main/java/dev/handywibowo/diskquilt/ui/HomeScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/ResultScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/MainActivity.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/ScanViewModel.kt'],
        model="gpt-6-luna",
        shots=["01_home.png", "02_treemap.png", "03_contents.png"],
        title="User flows, discoverability and friction",
        brief="""Judge the app as a UX researcher walking through first-run, scanning, exploring,
acting on a file, and coming back later. Focus on: what is missing or confusing at each step,
whether the user can tell what is happening during a 60 s scan, whether destructive actions are
safe enough, whether the app remembers anything, and which WinDirStat/WizTree conventions are
missing. Be specific about the screen and the control.""",
    ),
    dict(
        key="features",
        files=['app/src/main/java/dev/handywibowo/diskquilt/ui/HomeScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/ResultScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/scan/NodeStore.kt'],
        model="deepseek-v4-1-flash",
        shots=["01_home.png", "02_treemap.png", "04_largest.png"],
        title="Missing features versus WinDirStat / WizTree and modern Android",
        brief="""Judge the feature set. Compare against WinDirStat and WizTree and against what an
Android user expects in 2026. Focus on high-value missing capabilities (search, filters,
duplicate detection, file-type breakdown, sorting options, history, export, scheduling,
multi-select, gesture support, share of a report, app-aware views, per-volume comparison).
Rank them by value/effort and say which are table stakes versus nice-to-have.""",
    ),
    dict(
        key="performance",
        files=['app/src/main/java/dev/handywibowo/diskquilt/scan/DiskScanner.kt', 'app/src/main/java/dev/handywibowo/diskquilt/scan/NodeStore.kt', 'app/src/main/java/dev/handywibowo/diskquilt/treemap/Treemap.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/TreemapCanvas.kt'],
        model="qwen3-8-27b-uncensored",
        shots=["02_treemap.png"],
        title="Performance, memory and responsiveness",
        brief="""Judge the implementation as a performance engineer. Read the scanner, NodeStore,
Treemap and the Compose canvas. Focus on: allocation behaviour, BFS queue cost, the treemap cache
global-state hack, recomposition and redraw cost, the O(n^2) overlap risk in layout, memory per
entry, scan throughput on 100k+ files, UI thread work, and what will break first on a 1M-file
volume or a slow SD card. Propose concrete fixes with expected effect.""",
    ),
    dict(
        key="architecture",
        files=['app/src/main/java/dev/handywibowo/diskquilt/ui/ScanViewModel.kt', 'app/src/main/java/dev/handywibowo/diskquilt/MainActivity.kt', 'app/src/main/java/dev/handywibowo/diskquilt/scan/NodeStore.kt', 'app/src/main/java/dev/handywibowo/diskquilt/deep/DeepScan.kt'],
        model="mimo-v2-6-flash",
        shots=[],
        title="Architecture, state management and code quality",
        brief="""Judge the code as a staff Android engineer. Focus on: separation between scanning
and UI, ViewModel responsibilities (it currently owns scanning, deletion, intents and formatting
glue), error handling and partial-failure reporting, the NodeStore API surface, testability,
missing tests, threading, lifecycle and process-death behaviour, and whether the root-shell path
is safely contained. Point at specific functions and propose a refactor order.""",
    ),
    dict(
        key="a11y_i18n",
        files=['app/src/main/java/dev/handywibowo/diskquilt/ui/HomeScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/TreemapCanvas.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/Format.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/Theme.kt'],
        model="gpt-5-6-luna",
        shots=["01_home.png", "02_treemap.png"],
        title="Accessibility, internationalisation and dark mode",
        brief="""Judge accessibility and i18n. Focus on: screen-reader labels for the canvas and
icon-less controls, touch target sizes, colour contrast of the treemap cells and of secondary
text, font scaling behaviour, RTL, hardcoded English strings (the device is Indonesian), number
and byte formatting per locale, dark theme correctness (the treemap label colour is hardcoded),
and reduced-motion / haptics. Cite the specific composable or draw call.""",
    ),
    dict(
        key="safety_data",
        files=['app/src/main/java/dev/handywibowo/diskquilt/ui/ResultScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/ui/ScanViewModel.kt', 'app/src/main/java/dev/handywibowo/diskquilt/deep/DeepScan.kt', 'app/src/main/java/dev/handywibowo/diskquilt/deep/ShellExecutor.kt'],
        model="inkling-2-mini-uncensored",
        shots=["05_details.png"],
        title="Data safety, permissions and destructive actions",
        brief="""Judge risk. Focus on: the delete path (recursive, irreversible, no trash), what
happens if the tree is stale and the file changed, delete failures and partial deletes, the
FileProvider path exposure in file_paths.xml, MANAGE_EXTERNAL_STORAGE justification, the root
shell command construction (quoting, injection, what runs as root), and how errors are surfaced
to the user. Rate each finding by severity and say what would make it safe.""",
    ),
    dict(
        key="product",
        files=['app/src/main/java/dev/handywibowo/diskquilt/ui/HomeScreen.kt', 'app/src/main/java/dev/handywibowo/diskquilt/MainActivity.kt'],
        model="muse-ember-1-0",
        shots=["01_home.png", "02_treemap.png"],
        title="Product positioning, onboarding and trust",
        brief="""Judge the product. Focus on: whether a first-time user understands what the app
does and why the numbers do not match Settings, how the permission ask is framed, how the
Android/data gap is explained, what makes a user come back (or not), the name and identity,
monetisation/OSS stance, and the top three things that would make this feel finished rather than
a prototype. Be blunt.""",
    ),
]


def call(aspect, source):
    """One reviewer. Reasoning models bill thinking tokens against max_tokens, so the budget is
    generous and a null `content` is retried once with more room."""
    selected = [s for s in SHOT_FILES if s[0] in aspect.get("shots", [])]
    content = [{"type": "text", "text": CONTEXT + "\n\n" + aspect["brief"] + "\n\n" + source}]
    for name, desc in selected:
        content.append({"type": "text", "text": f"Screenshot {name}: {desc}"})
        content.append({
            "type": "image_url",
            "image_url": {"url": "data:image/jpeg;base64," + shot_b64(name)},
        })

    messages = [
        {"role": "system", "content": (
            "You are a senior reviewer. Answer in English. Output GitHub-flavoured Markdown only. "
            "Structure: '## What works' (max 5 bullets), '## Findings' (numbered, each with "
            "severity P0/P1/P2, the exact file or screen, the problem, and the concrete fix), "
            "'## Top 3 changes'. No preamble, no closing summary."
        )},
        {"role": "user", "content": content},
    ]

    started = time.time()
    last = "unknown"
    for attempt, budget in enumerate((16000, 16000)):
        body = {"model": aspect["model"], "messages": messages, "max_tokens": budget}
        try:
            r = requests.post(API, headers={
                "Authorization": "Bearer " + KEY,
                "Content-Type": "application/json",
            }, data=json.dumps(body), timeout=600)
        except Exception as exc:
            last = f"{type(exc).__name__}: {exc}"
            time.sleep(10 * (attempt + 1))
            continue
        if r.status_code != 200:
            last = f"HTTP {r.status_code}: {r.text[:300]}"
            time.sleep(10 * (attempt + 1))
            continue
        d = r.json()
        if "choices" not in d:
            last = f"no choices: {json.dumps(d)[:300]}"
            continue
        msg = d["choices"][0]["message"]
        text = msg.get("content")
        if not text:
            last = f"empty content (finish={d['choices'][0].get('finish_reason')})"
            continue
        usage = d.get("usage", {})
        return aspect, text, json.dumps(usage), time.time() - started
    return aspect, None, last, time.time() - started


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    os.makedirs(OUT, exist_ok=True)
    source_cache = {}
    aspects = [a for a in ASPECTS if only is None or a["key"] == only]
    print(f"{len(aspects)} reviewers", flush=True)

    results = []
    with ThreadPoolExecutor(max_workers=len(aspects)) as pool:
        futures = [pool.submit(call, a, build_source(a.get("files"))) for a in aspects]
        for f in futures:
            aspect, text, meta, elapsed = f.result()
            results.append((aspect, text, meta, elapsed))
            status = "ok" if text else "FAIL"
            print(f"{status:4} {aspect['key']:16} {aspect['model']:24} {elapsed:6.1f}s {meta[:80]}", flush=True)

    index = []
    for aspect, text, meta, elapsed in results:
        if not text:
            continue
        path = os.path.join(OUT, aspect["key"] + ".md")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(f"<!-- model: {aspect['model']} -->\n\n")
            fh.write(f"# {aspect['title']}\n\n")
            fh.write(text)
        index.append({"key": aspect["key"], "model": aspect["model"],
                      "title": aspect["title"], "elapsed_s": round(elapsed, 1)})
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as fh:
        json.dump(index, fh, indent=2)
    print("wrote", len(index), "reports")


if __name__ == "__main__":
    main()
