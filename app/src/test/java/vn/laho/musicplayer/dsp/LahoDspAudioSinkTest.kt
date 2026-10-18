// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LahoDspAudioSinkTest {

    /** Minimal fake AudioSink that consumes at most [bytesPerCall] bytes per handleBuffer. */
    private class FakeSink(private val bytesPerCall: Int) {
        var config: AudioSink.AudioSinkConfig? = null
        val received = mutableListOf<Byte>()
        var flushes = 0
        private var current: ByteBuffer? = null

        val sink: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "configure" -> { config = args!![0] as AudioSink.AudioSinkConfig; null }
                "handleBuffer" -> {
                    val buffer = args!![0] as ByteBuffer
                    current?.let { check(it === buffer) { "AudioSink contract: same buffer until consumed" } }
                    current = buffer
                    val n = minOf(bytesPerCall, buffer.remaining())
                    repeat(n) { received += buffer.get() }
                    val done = !buffer.hasRemaining()
                    if (done) current = null
                    done
                }
                "flush", "reset" -> { flushes++; current = null; null }
                "supportsFormat" -> true
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink

        fun receivedFloats(): FloatArray {
            val bb = ByteBuffer.wrap(received.toByteArray()).order(ByteOrder.nativeOrder()).asFloatBuffer()
            return FloatArray(bb.remaining()).also { bb.get(it) }
        }
    }

    private fun pcmFormat(encoding: Int, rate: Int = 96_000, channels: Int = 2) =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .setEncoderDelay(576)
            .setEncoderPadding(1024)
            .build()

    private fun s24Buffer(values: IntArray): ByteBuffer {
        val bb = ByteBuffer.allocate(values.size * 3)
        for (v in values) {
            bb.put((v and 0xFF).toByte())
            bb.put(((v shr 8) and 0xFF).toByte())
            bb.put(((v shr 16) and 0xFF).toByte())
        }
        bb.flip()
        return bb
    }

    private fun drain(sink: AudioSink, buffer: ByteBuffer): Int {
        var calls = 1
        while (!sink.handleBuffer(buffer, 0L, 1)) calls++
        return calls
    }

    @Test
    fun hiResPcmReachesDelegateAsExactFloatWhenEqIsFlat() {
        val fake = FakeSink(bytesPerCall = 1000)
        val peq = PeqProcessor().apply { setConfig(PeqConfig.FLAT) }
        val sink = LahoDspAudioSink(fake.sink, peq)
        sink.configure(AudioSink.AudioSinkConfig.Builder(pcmFormat(C.ENCODING_PCM_24BIT)).build())

        val delegateFormat = fake.config!!.format
        assertEquals(C.ENCODING_PCM_FLOAT, delegateFormat.pcmEncoding)
        assertEquals(96_000, delegateFormat.sampleRate)
        assertEquals("gapless metadata kept", 576, delegateFormat.encoderDelay)
        assertEquals(1024, delegateFormat.encoderPadding)

        val values = IntArray(2000) { ((it * 4099) % 16_777_216) - 8_388_608 }
        val calls = drain(sink, s24Buffer(values))
        assertTrue("partial consumption must be retried", calls > 1)

        val expected = FloatArray(values.size) { values[it] / 8388608f }
        assertArrayEquals(expected, fake.receivedFloats(), 0f)
    }

    @Test
    fun nonPcmInputBypassesDsp() {
        val fake = FakeSink(bytesPerCall = Int.MAX_VALUE)
        val sink = LahoDspAudioSink(fake.sink, PeqProcessor())
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setSampleRate(48_000).setChannelCount(6).build()
        val config = AudioSink.AudioSinkConfig.Builder(ac3).build()
        sink.configure(config)
        assertSame(config, fake.config)

        val payload = ByteBuffer.wrap(ByteArray(64) { it.toByte() })
        assertTrue(sink.handleBuffer(payload, 0L, 1))
        assertEquals((0 until 64).map { it.toByte() }, fake.received)
    }

    @Test
    fun formatSupportIsQueriedAsFloat() {
        var queried: Format? = null
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            if (method.name == "getFormatSupport") {
                queried = args!![0] as Format
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            } else null
        } as AudioSink
        val sink = LahoDspAudioSink(delegate, PeqProcessor())
        sink.getFormatSupport(pcmFormat(C.ENCODING_PCM_32BIT))
        assertEquals(C.ENCODING_PCM_FLOAT, queried!!.pcmEncoding)
    }

    @Test
    fun flushDropsPendingOutput() {
        val fake = FakeSink(bytesPerCall = 8)
        val sink = LahoDspAudioSink(fake.sink, PeqProcessor().apply { setConfig(PeqConfig.FLAT) })
        sink.configure(AudioSink.AudioSinkConfig.Builder(pcmFormat(C.ENCODING_PCM_16BIT)).build())

        val first = ByteBuffer.allocate(400).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(sink.handleBuffer(first, 0L, 1)) // only 8 of 800 float bytes consumed
        sink.flush()
        fake.received.clear()

        val second = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        second.putShort(Short.MIN_VALUE).putShort(16384).flip()
        drain(sink, second)
        assertArrayEquals(floatArrayOf(-1f, 0.5f), fake.receivedFloats(), 0f)
    }

    @Test
    fun boostedOutputIsClampedAndCounted() {
        val fake = FakeSink(bytesPerCall = Int.MAX_VALUE)
        var clipped = 0
        val peq = PeqProcessor().apply { setConfig(PeqConfig(true, 6.0206, emptyList())) }
        val sink = LahoDspAudioSink(fake.sink, peq, onClipped = { clipped += it })
        sink.configure(
            AudioSink.AudioSinkConfig.Builder(pcmFormat(C.ENCODING_PCM_FLOAT, channels = 1)).build(),
        )
        val input = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        input.putFloat(0.9f).putFloat(0.25f).flip()
        drain(sink, input)

        assertArrayEquals(floatArrayOf(1f, 0.5f), fake.receivedFloats(), 1e-4f)
        assertEquals(1, clipped)
    }
}
