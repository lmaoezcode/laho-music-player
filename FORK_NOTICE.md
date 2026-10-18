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

Original copyright headers (SPDX lines) in source files must be kept. New files
added by LaHo Music Player should carry their own SPDX header with
`GPL-3.0-or-later`.
