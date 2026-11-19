# Pointlook Android Browser

Pointlook is an Android WebView browser with Brave's Rust-based adblock engine connected through a Kotlin/JNI library, a full Material Design 3 UI, NxDB cloud integration (bookmarks, favorites, cloud file saves) and a built-in download manager with a cloud-or-device confirm flow.

## Features

### Browser
- Android WebView browser with Material 3 toolbar, address/search bar and bottom navigation bar
- Brave adblock-rust engine through an Android JNI library
- EasyList filter download during startup
- Network request blocking for WebView subresources
- Per-session protection toggle (Shield chip) and blocked-request counter
- GitHub Actions APK artifact and tag-based GitHub Releases

### Cloud (NxDB)
- Settings screen: enter your NxDB database ID and password, save them on device, and test the connection (engine, latency, permissions)
- Bookmarks: save / remove bookmarks for the current page; bookmark icon highlights when the page is already saved
- Favorite links: save search pages and favorite links separately from bookmarks; managed in a bottom sheet with Bookmarks / Favorites tabs
- When NxDB is configured, bookmarks and favorites are stored in the cloud table `pointlook_items`; otherwise they are kept on the device

### Downloads and cloud saves
- Any download link click opens a Material confirm dialog with the file details (name, type, size, source) and a choice: **Save to cloud** (NxDB storage, up to 50 MB, folder `browser/`) or **Save to device** (system DownloadManager)
- Long-press an image in a page to save it to the cloud or device; long-press a link to open it, save it to favorites, or copy it
- Saved files screen with Cloud / Device tabs: every file shows its details (name, type, size, date, storage backend, checksum, source URL)
- Cloud file actions: save to device, copy public link, delete from cloud
- Device download actions: open system downloads, remove from list

## Build

Requirements: JDK 21, Android SDK, and Gradle 9.3.1.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Release

Push a version tag such as `v1.1.0`. GitHub Actions builds an installable debug-signed APK and attaches it to a GitHub Release. Branch builds upload the APK as an Actions artifact.

## NxDB setup

1. Create a database in the NxMirror dashboard (https://nxmirror.netlify.app/dashboard/database).
2. In Pointlook open **Menu → Settings**, paste the database ID (`nxdb-...`) and password, then **Save**.
3. Use **Test connection** to verify: it shows the engine type, latency and permissions.

The NxDB Kotlin SDK is vendored under `app/src/main/java/com/nxmirror/db/` (ported from https://github.com/crossberry-in/nxmirror-db-android).

## Ad-block engine and licenses

- [Brave adblock-rust](https://github.com/brave/adblock-rust) — MPL-2.0.
- [Android Kotlin/JNI wrapper](https://github.com/mlm-games/adblock-rs-kt-wrapper) — MPL-2.0.
- EasyList is downloaded from its upstream project at runtime; its own terms apply.

This is an independent browser project and is not affiliated with Brave Software.
