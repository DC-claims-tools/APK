# Documents — Moto G 2024 Android Project

## Fixes & Upgrades Included
1. **Custom Adaptive Launcher Icon** (`mipmap-anydpi-v26/ic_launcher.xml`): Folded-corner document badge with Material You monochrome layer for Moto G 2024 (Android 14/15).
2. **PDF Black-Page Fix** (`PdfZoomView.java`): Initializes rendered bitmaps on crisp white paper inside clipped page bounds before calling `PdfRenderer.Page.render()`, plus Warm Sepia and Night Invert modes.
3. **Production Android UI & Side Menu** (`MainActivity.java`): Vector drawable icons with RippleDrawable feedback, Dark/Light app theme, Search + Filter chips + Sort, Page Thumbnails Grid, Bookmarks, Check/Date stamps, PNG page export, and PrintManager.
4. **Moto G 2024 Target** (`app/build.gradle`): `minSdk 26`, `targetSdk 35`, `compileSdk 35`, zero external dependencies.

## Build the APK
- **Option A (Android Studio)**: Open this folder in Android Studio -> `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
- **Option B (GitHub Actions - No PC Setup)**: Push this folder to GitHub, open the Actions tab, run **Build Documents APK**, and download `Documents-MotoG2024-debug-apk`.
- **Install on Moto G 2024**: Transfer `app-debug.apk` to your phone and tap to install (enable *Install unknown apps*), or run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
