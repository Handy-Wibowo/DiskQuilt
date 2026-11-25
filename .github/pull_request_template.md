## What changed

<!-- One or two sentences. Link the issue this closes, if any: Closes #123 -->

## How it was verified

<!--
Concretely: which test, which command, which screen. "Ran :app:testDebugUnitTest" or
"scanned internal storage on a Pixel 6, treemap totals match the list totals" is useful.
"Looks fine" is not.
-->

## Checklist

- [ ] Commits are signed off (`git commit -s`) per the DCO in CONTRIBUTING.md
- [ ] `gradlew.bat :app:testDebugUnitTest` passes
- [ ] `gradlew.bat :app:lintDebug` reports 0 errors
- [ ] New user-visible strings exist in **both** `values/strings.xml` and `values-in/strings.xml`
- [ ] New dependencies, if any, are added to `THIRD_PARTY_NOTICES.md`
- [ ] The change does not add `INTERNET` or any other new permission
- [ ] Screenshots attached for UI changes (with private file names redacted)

## Notes for the reviewer

<!-- Tradeoffs, follow-up work you deliberately left out, or anything surprising. -->
