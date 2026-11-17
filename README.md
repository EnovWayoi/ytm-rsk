# ytm-rsk

<img src="app/src/main/res/drawable-nodpi/ic_launcher.png" width="100" alt="ytm-rsk icon">

A native YouTube Music client for Android, built with **Rust and Kotlin**.

**ytm-rsk is an independent Android adaptation of [ytm-rs](https://github.com/grapedjuice/ytm-rs) by [grapedjuice](https://github.com/grapedjuice) and its contributors.** The original project provides the foundation for this port's YouTube Music integration. The new red robot logo combines its smiling icon with the Android robot silhouette; see [logo credits](docs/BRANDING.md).

[Download the APK](https://github.com/EnovWayoi/ytm-rsk/releases/latest) · [Release history](https://github.com/EnovWayoi/ytm-rsk/releases) · [Report an issue](https://github.com/EnovWayoi/ytm-rsk/issues)

## Install

1. Download `ytm-rsk-v0.2.2.apk` from the [latest release](https://github.com/EnovWayoi/ytm-rsk/releases/latest).
2. Open the APK on your Android device and allow installation from your browser or file manager when prompted.
3. Open **ytm-rsk** and start browsing or searching.

Requires **Android 8.0 or newer**. The APK includes **ARM64** for phones and **x86-64** for emulators. ARMv7-only devices are not supported.

The published APK uses the non-debuggable release variant for direct installation. It retains the existing development signing certificate for update compatibility; it is not a Play Store release. Updates published here retain the same application ID; installing over an earlier compatible build preserves your library and downloads. Independently built APKs may use a different signing key.

## Features

### Discover and organize

- Home combines **Listen again**, related Quick picks, and albums, artists and playlists from your listening activity.
- Search songs, albums, artists, playlists and videos. Browse new releases, charts and moods.
- Type filters, mood filters, list search and continuation loading.
- Animated skeleton loaders for Home, Explore, search and library pages.
- Local favorites, saved collections, history and editable playlists.
- Import local audio through Android's file picker or supported file drops.
- Optional podcast categories.

### Listen

- Selecting a song from **Home or search** starts that song and builds a related recommendation queue.
- Albums, playlists and library lists preserve their own queues without automatic radio additions. You can explicitly start song radio from a song menu.
- Previous/next, play/pause and seeking in the app, plus Android media notifications and background playback.
- Shuffle and a single repeat button that cycles **Off → queue → song**.
- Direct queue and playlist drag reordering, with up/down controls as an accessible alternative.
- Autoplay for recommendation queues, next-track prefetch and a bounded audio cache.
- Crossfade, loudness normalization, a 10-band equalizer with presets, and playback speed with matching pitch changes.
- Connected output-device selection, subject to Android's routing rules.
- Best available direct Opus audio, with audio HLS fallback. The player displays the actual codec and bitrate.
- Prefer a matching album recording over a music video, or open the video in the native video player.
- Optional SponsorBlock skips, explicit badges, Hide explicit songs and Restricted Mode.
- Line-synced LRCLib lyrics and high-resolution full-player artwork.

### Offline songs

Download a song from its menu, then open **Library → Offline songs**.

Downloads are AAC `.m4a` files with embedded metadata and cover art. They are stored in the app's private storage and play without an internet connection; they do not appear in Android's shared Downloads folder. Uninstalling the app removes this private storage.

The completion notice disappears after five seconds and includes **Show** and dismiss controls. Favorites are bookmarks; only downloaded or imported audio is available offline.

## Account support and current limits

Google account access is optional. The app has paths for a signed-in feed/library, likes, saved collections, playlist edits and music uploads, but **these account features have not been verified with an authenticated user account**.

- Google may refuse sign-in in an embedded WebView.
- Session cookies are encrypted using Android Keystore. Passwords are entered on Google's page and are not stored by the app.
- Premium formats that require YouTube's JavaScript signature or PO-token solver are **not supported yet**. Available guest streams are used as a fallback; 256 kbps cannot be guaranteed.
- My Supermix and Discover Mix appear only when the signed-in service actually returns them. Guest Quick picks use local listening history.
- Desktop features such as the complete word-by-word lyrics provider suite, all 30 themes, jams, Spotify transfer and scrobbling have not been ported.

YouTube's private APIs can change, and playback can vary by track, region or network. Emulator testing is documented in [Verification](docs/VERIFICATION.md); physical-device audio, Bluetooth/USB routing and some video/SponsorBlock cases still need testing.

## Rust and Kotlin

| Component | Responsibility |
| --- | --- |
| Rust core | YouTube Music request construction, signed headers, response parsing, stream selection, lyric timing and M4A tagging |
| Kotlin / Compose | Android screens, library state, account session storage and file import |
| Kotlin / Media3 | Audio/video playback, queue, media session, notification controls, caching, crossfade and audio effects |
| JNI | Connects the Kotlin app to the Rust core |

The main application is native; it does not wrap the YouTube Music website for playback. The optional Google sign-in screen uses a WebView.

## Build from source

The tested Windows toolchain uses JDK 25, Android SDK 36, Android NDK r27d, and stable Rust with the GNU Windows host toolchain. Gradle 9.6.0 is pinned through the wrapper with a distribution checksum; Android Gradle Plugin 9.4.1 supplies Kotlin 2.2.10.

Install the Android SDK/NDK through Android Studio's SDK Manager, then:

```powershell
git clone https://github.com/EnovWayoi/ytm-rsk.git
cd ytm-rsk

rustup toolchain install stable-x86_64-pc-windows-gnu --profile minimal
$env:RUSTUP_TOOLCHAIN = "stable-x86_64-pc-windows-gnu"

# Set these to your installed JDK, Android SDK and NDK paths.
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME = "C:\path\to\android-ndk-r27d"

.\scripts\build.ps1
```

The script runs Rust tests, builds the ARM64 and x86-64 libraries, copies them into the app, builds the APK, runs Kotlin tests and checks Android lint. Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Generated native libraries, build outputs, SDK paths, signing keys and downloaded toolchains are not committed. After a successful native build, `-SkipNative` can be used for an Android-only rebuild; rebuild native code after changing Rust.

To create a signed release, provide your keystore through environment variables (keep the keystore and passwords out of Git), then run:

```powershell
$env:YTM_KEYSTORE_PATH = "C:\private\ytm-rsk.keystore"
$env:YTM_KEY_ALIAS = "your-key-alias"
# Set YTM_KEYSTORE_PASSWORD and YTM_KEY_PASSWORD securely in your environment.
.\scripts\build.ps1 -Release
```

This produces `app/build/outputs/apk/release/app-release.apk`, runs the shared unit tests and release lint, and requires all four signing variables. Use the same signing certificate as your previous installation to update it without uninstalling.

To run just the Rust tests:

```powershell
cargo test --manifest-path native/Cargo.toml
```

## Project layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/dev/enov/ytm/` | Android UI, repository, media service, queue policy and audio processing |
| `app/src/test/` | Kotlin regression tests |
| `native/src/` | Rust core and JNI entry point |
| `native/tests/` | Rust integration tests |
| `scripts/build.ps1` | Windows build and validation script |
| `docs/UPSTREAM.md` | Study of the original desktop implementation |
| `docs/VERIFICATION.md` | Observed test results and remaining verification gaps |

## Privacy

No analytics or developer-operated server. Catalog, audio and optional account actions go to Google/YouTube; artwork uses the supplied image hosts; lyrics use [LRCLib](https://lrclib.net/); enabled skip requests use [SponsorBlock](https://sponsor.ajay.app/).

Library data, imported audio and downloads stay in app-private storage, with Android backup disabled. Sign out removes the stored Google session. Online browsing and streaming require internet; downloaded audio plays locally.

## Credits

- [Logo sources and attribution](docs/BRANDING.md) - ytm-rs smile and the Android robot by Google, recolored red.

- **[ytm-rs](https://github.com/grapedjuice/ytm-rs)** by **[grapedjuice](https://github.com/grapedjuice)** and its contributors — the original native Rust desktop app, API behavior, client constants, parser shapes and app icon used as the basis for this Android adaptation.
- Upstream reference commit: [`97bc7ebe6591ed296bf714f1dba6cc9280bb9aab`](https://github.com/grapedjuice/ytm-rs/commit/97bc7ebe6591ed296bf714f1dba6cc9280bb9aab).
- [AndroidX / Jetpack Compose / Media3](https://github.com/androidx/media) — Android interface and media playback.
- [Kotlin](https://github.com/JetBrains/kotlin) and [Rust](https://github.com/rust-lang/rust) — application languages and toolchains.
- [Coil](https://github.com/coil-kt/coil) — artwork loading.
- [LRCLib](https://github.com/tranxuanthang/lrclib) — lyric lookup and timing.
- [SponsorBlock](https://github.com/ajayyy/SponsorBlock) — community-contributed skip segments.
- [mp4ameta](https://github.com/Saecki/rust-mp4ameta), [serde_json](https://github.com/serde-rs/json) and [jni-rs](https://github.com/jni-rs/jni-rs) — metadata, JSON and the native bridge.

Original project copyright remains with its respective contributors. This Android adaptation is maintained independently by [EnovWayoi](https://github.com/EnovWayoi). It is not an official Google or YouTube product.

## License

**GPL-3.0-only**, following the original ytm-rs project. See [LICENSE](LICENSE). Source for published APKs is available in this repository and in each release's source archives. Third-party components retain their respective licenses.
