# LaHo Music Player — fork notice

LaHo Music Player ("Trình phát nhạc LaHo", project codename HQMP) is a modified
version of [Rhythm](https://github.com/cromaguy/Rhythm) by Anjishnu Nandi and
contributors.

- Upstream: https://github.com/cromaguy/Rhythm
- Forked from upstream commit `85fea209c4d44046192d830faa9181db3ece82ad` (2026-10-17)
- License: GNU General Public License v3.0 or later (see [LICENSE](LICENSE)).
  LaHo Music Player is distributed under the same license; its complete
  corresponding source must remain publicly available.

## Changes from upstream

- 2026-10-18: application ID changed to `vn.laho.musicplayer` (debug builds:
  `vn.laho.musicplayer.debug`); user-visible app name changed to
  "LaHo Music Player" (Vietnamese: "Trình phát nhạc LaHo"); APK file prefix
  changed to `LaHo-`. The Kotlin source package (`chromahub.rhythm.app`) is kept
  for now to make merging upstream fixes easier.
- 2026-10-18: added the LaHo 32-bit float DSP pipeline (`vn.laho.musicplayer.dsp`:
  parametric EQ, PCM-to-float sink, AutoEQ preset parser) and hooked it into
  `RhythmPlayerEngine`, `MediaPlaybackService`, `AppSettings` and the external
  capabilities settings screen.
- 2026-10-18: added the LaHo assistant (`vn.laho.musicplayer.assistant`): tool
  registry, offline Vietnamese command parser, Gemini function calling via
  Firebase AI Logic with App Check, and an experimental chat screen; added the
  Firebase / google-services Gradle setup and an entry in the external
  capabilities settings screen.
- 2026-10-18: added a userspace bit-perfect USB DAC driver: vendored the MIT
  libraries of [decent-player](https://github.com/neonteamcc/decent-player) under
  `third_party/decent-usb-audio` (ported to Media3 1.11, SFTP removed) and xiph/flac
  1.5.0 as the `third_party/flac` submodule (BSD); glue in
  `vn.laho.musicplayer.usb`, hooks in `RhythmPlayerEngine`, `MainActivity`,
  `AppSettings`, the manifest and the external capabilities settings screen.
- 2026-10-18: added an automatic mood index (`vn.laho.musicplayer.mood`: offline
  audio features, Gemini classification, own Room database) and the assistant's
  `play_by_mood` tool.
- 2026-10-18: added minimal Google Drive support (`vn.laho.musicplayer.drive`):
  read-only Drive REST v3 client, authorization through Google Play services
  (`play-services-auth`, `drive.readonly`, token kept in memory only), saving to
  `Music/LaHo` via MediaStore, and the assistant tools `drive_search` /
  `drive_download` (confirmation required) plus the `/drive` chat command.
- 2026-10-18: added metadata lookup (`vn.laho.musicplayer.metadata`): MusicBrainz
  web service client (rate limited to 1 request/s, descriptive User-Agent) with
  match scoring, Cover Art Archive covers, a "Find metadata" action in the tag
  editor of `SongInfoBottomSheet` (suggest, review, then the existing Save writes
  the tags), and the assistant's `/meta` batch command that stores suggestions
  only in LaHo's Room database (`LahoMoodDatabase` version 2, real migration
  1 to 2, schemas exported to `app/schemas`) and feeds genre/year to the mood
  index. MusicBrainz core data is CC0 and the service is free for non-commercial
  use; commercial use needs a MetaBrainz commercial plan.
- 2026-10-18: LaHo visual identity - new original adaptive launcher icon
  (stylised "L" + waveform; vector foreground/background/monochrome), splash and
  in-app logo, status-bar icon (generator: `scripts/branding/gen_laho_icon.py`);
  user-visible "Rhythm" product-name strings replaced with "LaHo" in all locales;
  the About screen and README credit Rhythm (GPL-3.0) by Anjishnu Nandi with a
  link to the upstream repository; network User-Agent / client names now say LaHo.
  Drawable resource names (`rhythm_logo`, ...) are unchanged. The update checker
  now reads releases of lmaoezcode/laho-music-player and is off by default.
- 2026-10-29: added the LaHo parametric EQ editor (`vn.laho.musicplayer.dsp.ui`:
  band list, preamp, live frequency-response graph from the biquad coefficients,
  AutoEQ `ParametricEQ.txt` import through the system file picker) and named PEQ
  presets stored as JSON in the app's files dir (`PeqPresetStore`). The DSP source
  (Rhythm's graphic EQ, the default, or the active LaHo preset) is chosen in the
  editor. Hooks: `RhythmPlayerEngine` (passes the preset state to the DSP), the
  manifest, an entry in the external capabilities settings screen and an overflow
  menu item in `EqualizerScreen` shown while LaHo DSP is on.
- 2026-10-29: USB hi-res (88.2-192 kHz) and signal-path display: the vendored
  driver checks the DAC's rates (UAC2 GET RANGE), the isochronous packet size and
  the SET_CUR read-back, and falls back to Android's pipeline with a message when a
  rate cannot be streamed (details in `third_party/decent-usb-audio/README.md`);
  new `vn.laho.musicplayer.signal` package; hooks in `PlaybackQualityInfo`
  (`lahoSignalPath` field), `MediaPlaybackService.updatePlaybackQuality`,
  `PlaybackQualityBottomSheet` ("Signal path" card), `AudioQualityBadges`
  (bit-perfect / PEQ badge) and `strings.xml` (en, vi).

Original copyright headers (SPDX lines) in source files must be kept. New files
added by LaHo Music Player should carry their own SPDX header with
`GPL-3.0-or-later`.
- 2026-10-29: disabled unofficial third-party services in both flavors (YouTube Music,
  Spotify search, Deezer, Lyrically/iTunes, BetterLyrics, Wikipedia, Apple Music canvas and
  album descriptions) and removed the hardcoded Apple Music token from `app/build.gradle.kts`.
  LRCLIB lyrics and LaHo's MusicBrainz lookup remain.
