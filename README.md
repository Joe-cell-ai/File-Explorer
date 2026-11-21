# File Explorer

A lightweight Android file manager with a clean, OnePlus-inspired experience. Independently implemented without proprietary OnePlus code or assets.

## First implementation batch
- Compose Material 3 home screen and storage usage overview
- Image, video, audio, document, downloads and APK shortcuts
- Android Storage Access Framework folder picker with user-granted access
- Folder browsing, search and basic file opening
- GitHub Actions debug APK build

## Build
Requires JDK 17 and Gradle 8.13: `gradle :app:assembleDebug`

This is the UI and browsing foundation. Full file operations, recents indexing, archive support, previews and desktop support are follow-up work.
