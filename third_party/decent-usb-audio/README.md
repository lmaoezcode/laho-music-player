# decent-usb-audio (vendored)

Bit-perfect USB Audio Class 2.0 driver for Android (userspace, no root, Android 10+)
and its Media3 `AudioSink` wrapper.

- Upstream: https://github.com/neonteamcc/decent-player — `libs/` only
- Upstream commit: `d039f9d` ("Prep for v0.1.0 public release", 2026-05-10)
- License: MIT — see [LICENSE](LICENSE) (Copyright (c) 2026 Marcelo Silva).
  `flac-jni/` comes from AOSP/Media3 and is Apache-2.0 (header in each file).
- libFLAC is **not** vendored here; it is the `third_party/flac` git submodule
  (xiph/flac tag `1.5.0`, BSD-style license, only libFLAC is built).

The AGPL proof-of-concept app in upstream `driver/Felicity/` is **not** included.

## Layout

| Directory | Gradle module | Upstream path |
|---|---|---|
| `usb-audio-driver/` | `:decent-usb-audio-driver` | `libs/decent-usb-audio-driver` |
| `usb-audio-wrapper-media3/` | `:decent-usb-audio-wrapper-media3` | `libs/decent-usb-audio-wrapper-media3` |
| `flac-jni/` | (built by the driver's CMake) | `libs/decent-media3-decoder-flac/src/main/jni/{flac_parser.cc,include}` |

## LaHo modifications

- Build files: compileSdk 37, Java 17, dependencies from the app's version catalog,
  consumer keep rules for the JNI classes.
- CMake: libFLAC from `third_party/flac`, JNI parser from `flac-jni/`.
- `UsbAudioSink`: ported from the Media3 1.9 `configure(Format, Int, IntArray?)`
  override (now `final` in `ForwardingAudioSink`) to `configure(AudioSinkConfig)`
  for Media3 1.11.
- Removed `SftpDataSource` / `DecentDataSourceFactory` (SFTP streaming with
  plain-text passwords in URIs; pulled in JSch and the Media3 cache/database).

Clone with submodules: `git clone --recurse-submodules …` or
`git submodule update --init third_party/flac`.
