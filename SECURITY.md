# Security policy

## Supported versions

Only the latest commit on `main` is supported. There are no maintained release branches.

## Reporting a vulnerability

Please **do not** open a public issue. Use GitHub's private reporting:

1. Go to the repository's **Security** tab.
2. Choose **Report a vulnerability**.
3. Describe the issue, the version or commit, the steps to reproduce, and the impact.

If private reporting is unavailable, contact the maintainer through the GitHub profile listed on
the repository. Expect an acknowledgement within a few days.

## Scope

The app requests **All files access** (`MANAGE_EXTERNAL_STORAGE`) and can delete files. Issues that
matter most:

- path traversal, or a way to make the app read or delete outside the tree the user selected
- command injection through the optional root deep scan (`Android/data`, `Android/obb`)
- leaking `FileProvider` content URIs to apps that were not granted access
- a way to make the scan report misleading sizes or hide data deletion

Out of scope, by design:

- reading `Android/data`, `Android/obb` or other apps' private storage without root — the OS
  blocks it and the UI says so
- the debug APK being debuggable; release builds use a private signing key
- denial of service by scanning an enormous volume slowly

## What the app never does

- no network access (it has no `INTERNET` permission)
- no analytics, accounts or telemetry
- no upload of file names, paths or contents
