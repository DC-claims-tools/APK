# Docs Studio — Moto G 2024 Android Project

## Fixes & Upgrades Included
1. **PDF Black-Page Fix** (`PdfZoomView.java`): Initializes rendered bitmaps on a crisp white paper rect inside clipped page bounds before calling `PdfRenderer.Page.render()`, plus Warm Sepia and Night Invert modes.
2. **Slide-out Side Navigation Menu** (`MainActivity.java`): Edge-to-edge safe drawer with Workspace, Recent, Starred, Create New (TXT/MD/CSV), PDF Tools (Go to Page, Rotate 90°, PrintManager, Theme, Export), and Office Tools (Find & Replace, Word Count, CSV Export).
3. **Moto G 2024 Target** (`app/build.gradle`): `minSdk 26`, `targetSdk 35`, `compileSdk 35`, zero external dependencies.

## Build the APK
- **Option A (Android Studio)**: Open this folder in Android Studio -> `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
- **Option B (GitHub Actions - No PC Setup)**: Push this folder to GitHub, open the Actions tab, run **Build Moto G 2024 APK**, and download `DocsStudio-MotoG2024-debug-apk`.
- **Install on Moto G 2024**: Transfer `app-debug.apk` to your phone and tap to install (enable *Install unknown apps*), or run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
