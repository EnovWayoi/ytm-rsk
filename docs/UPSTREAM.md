# ytm-rs source study and Android port boundary

Reference: [grapedjuice/ytm-rs](https://github.com/grapedjuice/ytm-rs), commit `97bc7ebe6591ed296bf714f1dba6cc9280bb9aab`, package version `0.3.3`, inspected 2026-11-17 UTC. License: GPL-3.0-only; preserve the upstream license and attribution when redistributing derived code and provide corresponding source for distributed binaries.

This report records static source observations. It does not establish that a Google session, stream endpoint, or lyrics provider works on a particular Android device. Source references below are relative to the pinned upstream checkout in `reference/`.

## How the desktop app works

| Subsystem | Source evidence | Observed behavior |
|---|---|---|
| Native interface | `src/ui/`, `src/theme.rs`, `Cargo.toml` | egui/eframe draws native desktop screens. The default dark background is RGB 12,10,14; text 242,240,245; accent 255,48,72. This is not a website wrapped as the player. |
| Work dispatch | `src/backend.rs`, `Req`, `Resp` | UI requests travel to a Tokio backend; responses wake the UI. Playback requests include a generation to distinguish stale results. |
| HTTP context | `src/ytmapi.rs`, `Api`, `call`, `request` | Uses YouTube Music's private InnerTube `WEB_REMIX` client. Reads the current client version and visitor data from music.youtube.com; caches them for 24 hours. Anonymous consent cookie is `SOCS=CAISAiAD`. Requests carry client context, origin, visitor ID, language, country, and time-zone information. |
| Home and browse | `src/ytm.rs`, `home`, `home_more`, `album`, `playlist_start`, `playlist_more`, `artist` | Home uses `browse` with `browseId=FEmusic_home`. Parses shelves, cards, tracks, mood chips and continuation tokens. Album, playlist, artist and library pages use their own browse IDs. |
| Search | `src/ytm.rs`, `Filter::params`, `search_as`, `search_songs`, `list_item`, `list_card` | Calls `search` with query and encoded filter parameters. Results live under tabbed search / section list / music shelf renderers. Responsive rows become tracks; browse endpoints become album, playlist or artist cards. Separate filters exist for songs, videos, albums, artists and playlists. |
| Account | `src/login.rs`, `Site::Google`, `src/webmusic.rs`, `Session`, `src/vault.rs` | An isolated system webview opens Google's actual sign-in page and captures session cookies after arrival at YouTube Music. SAPISID cookies sign API requests using timestamped SHA-1 authorization (`SAPISIDHASH` variants). Credentials go into an OS credential store. Cookie replacements and revocation are handled. |
| Stream selection | `src/backend.rs:resolve`, `resolve_from`, `src/innertube.rs:player`, `audio_stream`, `hls_audio` | Anonymous playback uses a `VISIONOS` client (`1.02`, client ID 101). Selects direct Opus/AAC audio URLs. HLS audio rendition 234 is available where progressive streams are capped. Signed-in Premium playback can use `WEB_REMIX`; JavaScript challenges are handled by `src/jsc.rs`. yt-dlp and RSS feed paths are additional fallbacks. |
| Buffering and output | `src/stream.rs`, `src/audio.rs` | Progressive audio is fetched in seek-aware parallel ranges; failed signed URLs can be refreshed. One output thread owns rodio playback. Commands support play/pause, seek, queue advancement, equalizer, normalization, speed and crossfade. Upcoming audio is prefetched. |
| Lyrics | `src/lyrics.rs`, `LyricsClient::fetch`, `fetch_stream`, `fetch_unison`, `parse_lrc` | Better Lyrics provider stream and Unison are fetched together. Better Lyrics uses a cached JWT obtained through Turnstile. Parsers support TTML, word timing, LRC and plain lyrics. Sources are sorted by preference; fallback and per-song timing are supported. LRCLib is one source in this set. |
| Local state | `src/cache.rs`, `src/downloads.rs`, `src/model.rs` | Tracks hold video ID, artist links, duration and thumbnails. Downloads are tagged m4a files with artwork and can be played offline. |

Private API responses and client versions can change independently of this port. A successful JSON request is not sufficient evidence of full playback: test beyond the first minute, seeking, queue advancement and screen-off operation.

## Android design recommendations

Keep Android lifecycle and platform media responsibilities in Kotlin. Use native views or Compose for screens and Media3 for decoding, HLS, audio focus, headset controls, a foreground media service and media-session notifications. Pass JSON over a small JNI boundary to Rust for API body construction, response parsing, stream selection and lyric parsing. Do not pull desktop windowing, tray, hotkey or rodio dependencies into the Android core.

Start with anonymous browsing and playback, then validate account integration separately. A normal browser Custom Tab cannot hand its HttpOnly cookies to the app; do not promise desktop cookie sign-in by merely opening a browser. Google can reject embedded WebView sign-in. A supported account flow needs explicit implementation and device testing. Credentials must never enter logs or shared storage.

Prefer Media3's audio-only HLS media playlist over progressive playback when possible. Preserve the request user agent and select the audio rendition rather than handing a video master playlist to an audio-only interface. A stream failure should remain visible with retry, and a slow response for an old song must not replace a newer song.

Use real service responses for live browsing. Keep test fixtures visibly separate from user-facing content. Store local favorites and recent history privately; label them as local if they are not synchronized to a Google account. Line-synced LRCLib lyrics are a useful first implementation but are not equivalent to upstream's word-by-word provider suite.

## Planned minimum port and parity checklist

The following is the recommended first scope, not a claim about the completed Android build. The final README and verification report must state which items actually shipped and were checked.

| Capability | First Android target | Evidence still needed |
|---|---|---|
| Native Android interface | Kotlin screens, dark palette, phone-sized controls | APK launch and visual inspection |
| Rust integration | Real JNI calls for shared logic | Bundled ABI libraries and device invocation |
| Search | Songs and collection navigation | Live search with nonempty, correctly parsed results |
| Home / Explore | Anonymous browse feed | Real endpoint response and click-through |
| Playback | Direct stream / audio HLS, play/pause/seek | Audible playback, sustained stream, seek and error recovery |
| Queue | Previous/next, shuffle/repeat as implemented | Queue transition and end-of-track checks |
| Background controls | Android media session / foreground service | Screen-off, notification and headset tests |
| Favorites / history | Local persistence initially | Process restart and accurate labeling |
| Lyrics | Line-synced LRC initially | Real track match, timing, unavailable state |
| Google library and Premium | Separate follow-up unless fully implemented | Secure sign-in, session rotation, synchronized library and Premium stream tests |
| Desktop-only integrations | Excluded from first Android target | No claim of parity for tray, desktop hotkeys or updater |
| Advanced features | Deferred | No claim of parity for all 30 themes, word karaoke, Spotify transfer, jams, scrobbling, downloads, uploads, SponsorBlock, EQ or crossfade until implemented and tested |

## Coverage and limits

Answered from source: architecture, primary request paths, parser shapes, authentication mechanism, stream routing and lyric-provider flow. Android runtime equivalence remains unproven by this study. No shipped binary was decompiled and no desktop runtime behavior was observed. REA analysis was unnecessary because the complete source was available.
