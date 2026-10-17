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

Original copyright headers (SPDX lines) in source files must be kept. New files
added by LaHo Music Player should carry their own SPDX header with
`GPL-3.0-or-later`.
