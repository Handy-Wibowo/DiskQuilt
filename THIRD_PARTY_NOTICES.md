# Third-party notices

DiskQuilt is distributed under the Apache License 2.0 (see `LICENSE`). It uses the following
third-party components. Each remains under its own licence; the texts are available at the links
below and must be retained in binary distributions.

## Runtime / shipped in the APK

| Component | Version | Licence |
|---|---|---|
| AndroidX Core (`androidx.core:core-ktx`) | 1.13.1 | Apache-2.0 |
| AndroidX Activity Compose (`androidx.activity:activity-compose`) | 1.9.3 | Apache-2.0 |
| AndroidX Lifecycle (`lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`) | 2.8.6 | Apache-2.0 |
| Jetpack Compose UI (`ui`, `ui-tooling-preview`, `foundation`) | 1.7.5 (BOM 2024.10.01) | Apache-2.0 |
| Jetpack Compose Material 3 (`material3`) | 1.3.1 (BOM 2024.10.01) | Apache-2.0 |
| Kotlin standard library | 2.0.21 | Apache-2.0 |
| `libandroidx.graphics.path.so` (bundled by Compose UI) | 1.7.5 | Apache-2.0 |

Apache-2.0 text: https://www.apache.org/licenses/LICENSE-2.0

AndroidX and Jetpack Compose are developed by the Android Open Source Project:
https://developer.android.com/jetpack

## Build / test only (not distributed in the APK)

| Component | Version | Licence |
|---|---|---|
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |
| Android Gradle Plugin | 8.7.2 | Apache-2.0 |
| Kotlin Gradle Plugin | 2.0.21 | Apache-2.0 |
| Gradle wrapper | 8.11.1 | Apache-2.0 |

EPL-1.0 text: https://www.eclipse.org/legal/epl-v10.html

## Notes

- The squarified treemap implementation in `treemap/Treemap.kt` is original code written for this
  project. It implements the published algorithm of Bruls, Huizing and van Wijk, *Squarified
  Treemaps* (2000); algorithms are not copyrightable and no code was copied.
- The launcher icon is an original vector asset.
- The `tools/review.py` review harness is part of this project and is licensed under Apache-2.0 with
  the rest of the source.
