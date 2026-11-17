# Verification

## Version 0.2.1 queue and offline fixes

Checked November 17, 2026 (America/New_York). Queue-origin policy tests cover Home/search/radio recommendations, collection/library/Explore exclusions and local-file exclusions. Drag target tests cover unequal row heights and both directions. The ten Kotlin tests and Android lint/build pass; the sixteen previously passing Rust tests concern unchanged native code.

Runtime checks observed a search selection create a 26-song recommended queue rather than queueing the 20 search results. The queue included related tracks such as Something About Us. Direct, non-long-press drags moved the current song down and back up. Playing Random Access Memories created a 13-song album queue, with no recommended additions. Existing downloads remained visible in the new Library → Offline songs card and list. Album tracks that had been omitted when only related-content sections were present now appear alongside those sections.

The saved notice is timed for five seconds and manually dismissible. An actual download completed in the renamed app and its notice disappeared; no dismiss control or stale completion notice remained after waiting. Playback uses per-queue provenance and a unique token so a delayed recommendation response cannot modify a newly selected album/playlist queue. Selecting an item within Queue seeks that existing queue instead of starting another recommendation request. The ytm-rsk display-name update retains the original application ID and signing key; installation over the old app retained existing downloads.

## Version 0.2.0 listening expansion

Checked November 16, 2026 (America/New_York), on the API 36.1 x86-64 emulator. Sixteen Rust tests and eight Kotlin/JVM tests pass. Android assembly and lint pass (zero lint errors; dependency/API and style warnings remain). ARM64 and x86-64 JNI libraries were rebuilt with 16 KiB alignment.

| Area | Observed verification |
|---|---|
| Home filters | All and Songs changed the visible result count; Artists and Playlists showed corresponding typed results. Final Albums filter showed Random Access Memories from actual listening history (one result). Album metadata/title extraction and persisted-state fallback are regression covered. |
| Explore | Albums returned 24 actual releases; working search filters returned live song and album results. Home returned mood chips and a locally personalized Listen again / Quick picks feed. |
| Mini player | Previous, Play/Pause and Next are present. |
| Android notification | Stable System UI hierarchy exposes Previous track, Play and Next track. Media notification has three actions. Android's system next command changed the active track in a full 22-item service queue. |
| Repeat | While paused, verified the accessible control changes Off → Repeat queue → Repeat song → Off. Uses Media3 queue repeat, not a ViewModel-only loop. |
| Sound quality | Real Get Lucky stream decoded/played as Opus, displayed about 134 kbps. Protected account streams fall back to available guest streams when a signature solver would be required. |
| Downloads | Downloaded a real AAC file, about 6 MB, into app-private storage. Embedded cover atom and title bytes verified. Download metadata now also includes known album/explicit fields. |
| Offline | With emulator Wi-Fi and mobile data disabled, Downloads played the local M4A and buffered over 58 seconds. Network was restored after the test. |
| Playlists | Created a local playlist, added the same downloaded song twice and opened the editable list without duplicate-key crashes. Up/down, remove and drag handles are present. Remote entry IDs and repeated remote entries are regression covered. |
| Crossfade | Set six seconds; near the end of a local file, observed Crossfade started/ended and active queue item move from 0 to 1 with playback continuing. The test reached the final approximately 3.6 seconds, so that shorter remaining overlap was used. No ExoPlayer or AndroidRuntime error was reported. |
| EQ | Bass boost enabled the ten-band controls. Pure DSP tests validate flat identity, gain/filter response, channel separation and normalization/clipping. |
| Artwork | Largest thumbnail selected and trusted image size parameters upgraded to 1200px. Existing saved low-resolution URLs are upgraded too. Full player decodes at 1200px; list/mini images decode smaller. |
| Parser/requests | Tests cover typed cards despite play overlays, explicit/duration/artist/album/playlist entry metadata, radio panels, sections/moods, continuations, signed request origin/safety context, header validation, best audio, AAC download preference, video HLS and invalid tagging paths. |

The app's account paths are implemented but not runtime verified with an authenticated Google account: sign-in may be refused in an embedded WebView; library sync, remote edits, Google uploads and Premium behavior still require that interactive account test. The JavaScript signature/PO-token solver used by the desktop app is not ported, so protected Premium streams are an explicit remaining limitation rather than a claimed 256 kbps guarantee. My Supermix and Discover Mix are shown only if returned by the actual signed-in service.

Physical-phone audio quality, connected Bluetooth/USB outputs, OEM battery limits, sponsor timing against an actual known music video, video playback for every codec/region, file-drop interaction, long-session cache behavior and all accessibility/display combinations remain unverified. The emulator has no host audio output, so playback checks concern decoder/player/buffer state and progression, not listening by ear. Crossfade and normalization should be listened to on the target headphones before considering them hardware validated.

## Initial 0.1.x verification (historical)

Icon update (0.1.1): launcher and app header use the original `assets/icon/icon.png` from grapedjuice/ytm-rs, without redrawing it. Version code increased to 2 for installation over the previous APK. Android assembly and lint were rerun for this resource change.

Checked November 16, 2026 (America/New_York). This records observed checks, not full feature parity or physical-device certification.

| Check | Result |
|---|---|
| Rust host tests | Four passed: response parsing/deduplication; multiple LRC timestamps/offset/invalid timing; playability errors/HLS preference; request endpoint, live context, visitor header, browser UA and audio-rendition selection |
| Rust cross-compilation | Release JNI libraries built for aarch64-linux-android and x86_64-linux-android |
| Native compatibility | ARM64 ELF LOAD alignment verified at 0x4000 (16 KiB); APK contains both ABIs |
| Android build | `assembleDebug` succeeded using AGP 9.4.1, Kotlin 2.2.10, Gradle 9.6.0, JBR 25, SDK 36 |
| Kotlin unit test | One passed: unknown, ordinary and over-one-hour duration formatting |
| Android lint | Passed with zero errors and 14 warnings, concerning newer SDK/dependency versions, backup configuration and optional KTX style |
| Installation/launch | APK installed and launched on Medium_Phone_API_36.1 x86-64 emulator; no AndroidRuntime crash observed |
| Home | Real guest home response rendered 30 entries with artwork |
| Search | “Daft Punk Get Lucky” rendered 20 song results; album filter rendered 18 results |
| Collection navigation | Random Access Memories opened with real tracks and related content |
| Stream transport | Current visitor/version bootstrap succeeded; player status OK; HLS master/audio playlist HTTP 200; first and later audio segments HTTP 200 with nonempty payloads |
| Playback | Media3 reported PLAYING with advancing position and buffered audio, past 80 seconds, without a reported playback error |
| Pause/resume | Android media session changed to PAUSED at about 80 seconds, then resumed |
| Background | Android Home left playback active; PlaybackService reported isForeground=true and media notification ID 1001 |
| Seek | Slider seek to about 2:14 succeeded; media session remained PLAYING and lyrics moved to the corresponding lines |
| Next | In-app next switched from All I Want for Christmas Is You to Treat You Better with the new title, duration and lyrics |
| Lyrics | Real LRCLib line-synced lyrics rendered, highlighted the current line and scrolled as playback progressed |
| Local persistence | A saved favorite remained after force-stop, APK update and relaunch |
| Layout | Home and full-player/lyrics screens visually inspected; system-bar contrast and bottom control spacing corrected |

The emulator was launched without host audio output. Playback checks establish successful player state, decoding/buffering and progression; listening on a physical phone remains unverified. ARM64 was compiled and inspected, not run on hardware. Headset disconnection, OEM battery restrictions, long screen-off sessions, automatic end-of-track transitions, all shuffle/repeat cases, accessibility with a screen reader and every display size remain untested. The media notification exposes the platform actions available for the current item; next/previous queue controls are implemented in the app.

YouTube's private API can change, return region-dependent results, or require sign-in on some networks. Streams use a current visitor ID obtained with a supported browser user agent; missing that bootstrap was observed to produce LOGIN_REQUIRED during testing and was corrected. The APK does not contain a sign-in bypass, cached test music, embedded account credentials or hardcoded stream URLs.

The implementation boundaries in README.md apply: no Google account sync, offline downloads, pagination or complete desktop parity. The local favorites list is not a synchronized YouTube library. Lyrics are line-synced LRCLib, not the full Better Lyrics word-timing provider suite.
