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
- **Fix: honour the ISO OUT endpoint's `bInterval`.** Upstream sized every packet
  for one microframe (`rate / 8000` frames). High-speed ISO endpoints send one packet
  every `2^(bInterval-1)` microframes, so DACs with `bInterval=2` (verified: Tempotec
  Sonata BHD, `22e1:f008`) received half the samples and played with constant dropouts
  (buffer underrun). The interval is now read in `UsbAudioDevice`, carried through
  `UsbAudioDeviceInfo` / `UsbAudioStream` to `nativeUsbAudioCreate`, and the packetizer
  uses `fpmf × microframesPerPacket`.
- **Fix: track switches stalling for >1.5 s after a rate change.** `drainAllUrbs`
  waited up to 500 ms per URB in phase 1 and up to 500 ms in phase 3 even when no more
  completions could arrive (seen on the Sonata BHD: 70/80 URBs reaped over 1 s, then a
  500 ms empty wait). Phase 1 now has a 250 ms overall deadline (the full ring is at most
  160 ms of audio) with 50 ms per reap, and phase 3 stops after 20 ms without a completion;
  the remaining URBs are discarded as before.
- **Fix: playback error on MP3 → FLAC switches.** The muted `DefaultAudioSink` delegate never
  receives PCM in USB mode, so its `TrimmingAudioProcessor` was configured with the MP3's
  encoder delay/padding but never flushed with a valid format; the next `reset()` threw
  `NegativeArraySizeException` in `TrimmingAudioProcessor.onFlush` (Media3 1.11) and the
  player stopped. `UsbAudioSink` now strips `encoderDelay`/`encoderPadding` from the format
  it forwards to the delegate (the delegate's output is discarded anyway).
- **Hi-res rates (88.2-192 kHz) are checked before streaming** (new
  `UsbAudioCapabilities.kt`, pure Kotlin, unit-tested in the app):
  - `UsbAudioDevice.openDevice` reads the clock source's supported rates with UAC2
    GET RANGE (`bmRequestType 0xA1`, `bRequest 0x02`, `CS_SAM_FREQ_CONTROL`, on the
    clock ID parsed from the descriptors) and parses every streaming alt setting's
    Format Type I and ISO OUT endpoint (`wMaxPacketSize`, `bInterval`) from the raw
    descriptors. Both are carried in `UsbAudioDeviceInfo`.
  - `UsbAudioSink.configureUsbBitPerfect` refuses a rate that GET RANGE does not list,
    or whose largest packet (`(floor(rate/8000 x 2^(bInterval-1)) + 1) x bytesPerFrame`)
    exceeds the streaming alt's `wMaxPacketSize` or the native URB buffer. The stream
    now uses the `bInterval` of the alt setting it streams on (was: the first alt's).
  - After SET_CUR the rate is read back with GET_CUR (`setSampleRateVerified`); a DAC
    reporting another rate is refused. A DAC that does not answer GET RANGE / GET_CUR
    is still attempted (as before).
  - **Fallback instead of silence:** when the rate is refused or the stream cannot be
    opened/started (upstream returned silently and left the delegate muted), the sink
    releases the stream, unmutes the delegate, clears the forced speaker routing and
    configures it normally, so the track plays through Android's pipeline. The reason
    is published in `signalPath`; the app shows a toast and the quality sheet.
  - Native: `USB_AUDIO_URB_BUFFER_SIZE` 4096 -> 8192 (192 kHz 32-bit stereo with
    `bInterval=2` already needs 3136 B per URB), and `nativeUsbAudioCreate` refuses a
    configuration whose URB would overflow the buffer.
- **`UsbAudioSink.signalPath: StateFlow<UsbSignalPath>`** (new `UsbSignalPath.kt`): stream
  rate, DAC-reported rate, source/USB bit depth, engine (native FLAC or ExoPlayer), DAC
  name, bit-perfect flag, fallback reason. The wrapper module now depends on
  kotlinx-coroutines-core (`api`).
- CMake: `HAVE_FSEEKO=1` for the libFLAC target so 32-bit ABIs build (libFLAC's own
  check forces it off and `compat.h` then clashes with bionic's `fseeko`).

## Verified hardware

| Phone | Android | DAC | Result |
|---|---|---|---|
| Xiaomi 11 Ultra (M2102K1C) | 12 (API 31) | Tempotec Sonata BHD (bInterval=2) | Bit-perfect 44.1 / 48 kHz, FLAC 16/24-bit (native engine) and MP3 (FFmpeg path), clean after the bInterval fix. The DAC's feedback endpoint did not answer, so the nominal rate is used. |

LaHo app side (not vendored code): `RhythmPlayerEngine` crossfades by swapping its two
ExoPlayers, but only player A owns the USB sink. A swap handed playback to player B (no USB
sink: silence) and left the USB sink on the paused "next" player, which is why track
switches were silent or slow. Crossfade is therefore disabled while the LaHo USB driver is
active (`RhythmPlayerEngine.isCrossfadeAllowed()`).

Clone with submodules: `git clone --recurse-submodules …` or
`git submodule update --init third_party/flac`.
