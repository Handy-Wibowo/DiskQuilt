# Contributing to DiskQuilt

Thanks for taking a look. This project is small and the rules are short.

## Before you start

- For anything bigger than a bug fix, open an issue first so we can agree on the approach before
  you spend time on it.
- By contributing you agree to license your contribution under the
  [Apache License 2.0](LICENSE), the same license as the project. You keep the copyright to your
  work; nothing is assigned to the maintainer.

## Developer Certificate of Origin (DCO)

Every commit must be signed off, which is a one-line statement that you have the right to submit
the code:

```sh
git commit -s -m "fix: guard the empty folder case"
```

That adds:

```
Signed-off-by: Your Name <you@example.com>
```

Use your real name and a reachable email. A pull request with unsigned commits cannot be merged.

## Build and test

Requirements: JDK 17+ and Android SDK 35 (platform + build-tools). Copy your SDK path into
`local.properties` (`sdk.dir=...`); it is gitignored.

```sh
gradlew.bat :app:assembleDebug          # or ./gradlew on macOS/Linux
gradlew.bat :app:testDebugUnitTest      # 54 JVM tests, no device needed
gradlew.bat :app:lintDebug              # must stay at 0 errors
```

There are no instrumentation tests; the treemap, scanner, delete/refresh and deep-scan parser are
covered by JVM unit tests.

## Before you open a pull request

1. `tools/install-hooks.sh` once, so the secret check runs on your commits.
2. Your branch must include a green **CI / Test, lint, assemble** check. `main` is protected and
   requires it.
3. No new dependency unless it is really needed. If you add one, update
   `THIRD_PARTY_NOTICES.md` with its name, version and license.
4. No new user-visible string without both `values/strings.xml` and `values-in/strings.xml`
   entries.
5. If you change behaviour covered by `docs/PLAN.md`, update the relevant verification row.

## Code style

- Kotlin, 4-space indent, no wildcard imports.
- Keep the scanner and the treemap layout free of Android imports so they stay unit-testable on
  the JVM.
- Prefer the existing helpers (`formatBytes`, `Coverage`, `TreeQueries`) over new formatting code.

## Reporting bugs

Include: Android version, device model, what you scanned (volume or folder), what you expected,
what happened, and a screenshot if the UI is involved. Do **not** attach screenshots that show
private file names unless you have redacted them.

## Security

Do not open a public issue for a vulnerability. See [SECURITY.md](SECURITY.md).
