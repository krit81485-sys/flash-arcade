# Flash Arcade — Android source

A Java/Gradle Android game library and SWF player. It stores imported SWFs in app-private storage, remembers the source folder through Android's Storage Access Framework, and runs the pinned Ruffle Web release in a locally served WebView.

## Build

- Android Studio Ladybug or newer
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.10.1 / Gradle 8.11.1 (the wrapper downloads Gradle)

Open this directory in Android Studio and let Gradle sync. The Gradle build downloads the pinned Ruffle `0.7.1` self-hosted web player into `app/src/main/assets/ruffle` if it is not already present. The archive is verified against its published SHA-256 digest. The player is then bundled into the APK so games can run offline. Minimum supported Android version: Android 8.0 (API 26).

To fetch the same runtime before opening Android Studio:

```sh
./tools/fetch-ruffle.sh
```

Build a debug APK:

```sh
./gradlew assembleDebug
```

Install the APK from `app/build/outputs/apk/debug/app-debug.apk`.

## Storage and safety

- Add one `.swf` with the Android document picker or choose a folder to scan. Folder access is a persisted SAF grant; no broad storage permission is requested.
- Imported SWFs are copied into the app-private `games/` directory. This avoids broken document-provider paths and makes the library work offline.
- Covers are generated automatically and stored in the app-private `covers/` directory. A cover can be replaced from the library item's menu.
- Room stores the library and last-played metadata. Removing a library item removes only the app's copy, not the original selected file.
- The player uses `WebViewAssetLoader` HTTPS-style local URLs, disables file/content access, blocks external navigation, and handles a crashed WebView renderer without crashing the launcher.
- The four touch keys are separate views. Only the visible key circles consume touch events; the rest of the screen remains a normal WebView touch surface.

## Ruffle and compatibility

Ruffle is the practical fit for a Java app because its official self-hosted WebAssembly player can be embedded offline in Android WebView. The official Ruffle Android app is a native Rust/Kotlin application, not a Java embeddable engine/library, so it is not a drop-in replacement for this Java project.

Ruffle implements a substantial but incomplete portion of Flash. Some SWFs, especially those relying on unsupported Flash APIs, external network resources, or unusual timing/input behavior, may not work. No app can guarantee zero crashes or 100% compatibility for every SWF and Android WebView version. This project handles common player and renderer failures gracefully and keeps the runtime replaceable by changing the pinned release in `app/build.gradle`.

The graphics quality menu sets Ruffle's initial `Stage.quality` to Low, Medium, or High. A SWF can change its own quality at runtime; Ruffle does not expose a supported setting that prevents the game from doing so. Stage scaling defaults to centered `showAll` to preserve the game's aspect ratio.

## Project structure

```text
app/src/main/java/com/flasharcade/launcher/
  data/       Room entities and DAO
  storage/    SAF import, app-private copies, and generated covers
  ui/         Library, settings, preview, and player screens
  util/       Persisted game/render settings
app/src/main/assets/runner.html
app/src/main/assets/ruffle/   Downloaded Ruffle JS/WASM runtime
tools/fetch-ruffle.sh         Pinned, checksum-verified runtime fetch
```

This project contains no Flash game files. Import only SWFs you are entitled to use.
