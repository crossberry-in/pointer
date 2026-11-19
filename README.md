# Pointlook Android Browser

Pointlook is an Android WebView browser prototype with Brave's Rust-based adblock engine connected through a Kotlin/JNI library.

## Features

- Android WebView browser with address/search bar and navigation controls
- Brave adblock-rust engine through an Android JNI library
- EasyList filter download during startup
- Network request blocking for WebView subresources
- Per-session protection toggle and blocked-request counter
- GitHub Actions APK artifact and tag-based GitHub Releases

## Build

Requirements: JDK 21, Android SDK, and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Release

Push a version tag such as `v1.0.0`. GitHub Actions builds an installable debug-signed APK and attaches it to a GitHub Release. Branch builds upload the APK as an Actions artifact.

## Ad-block engine and licenses

- [Brave adblock-rust](https://github.com/brave/adblock-rust) — MPL-2.0.
- [Android Kotlin/JNI wrapper](https://github.com/mlm-games/adblock-rs-kt-wrapper) — MPL-2.0.
- EasyList is downloaded from its upstream project at runtime; its own terms apply.

This is an independent browser project and is not affiliated with Brave Software.
