// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFeatureExtractorTest {

    private val fs = 44_100

    private fun sine(freq: Double, seconds: Double, amp: Double = 0.5) =
        FloatArray((fs * seconds).toInt()) { (amp * sin(2 * PI * freq * it / fs)).toFloat() }

    /** Decaying noise bursts on every beat: a crude drum track. */
    private fun clickTrack(bpm: Double, seconds: Double, amp: Double = 0.8, seed: Int = 1): FloatArray {
        val r = Random(seed)
        val out = FloatArray((fs * seconds).toInt())
        val period = 60.0 / bpm * fs
        var beat = 0.0
        while (beat < out.size) {
            val start = beat.toInt()
            for (i in 0 until (0.06 * fs).toInt()) {
                if (start + i >= out.size) break
                out[start + i] += (amp * exp(-i / (0.012 * fs)) * (r.nextDouble() * 2 - 1)).toFloat()
            }
            beat += period
        }
        return out
    }

    @Test
    fun fftFindsTheSinePeak() {
        val n = 1024
        val bin = 37
        val re = DoubleArray(n) { sin(2 * PI * bin * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val mags = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(bin, mags.indices.maxByOrNull { mags[it] })
        assertEquals(n / 2.0, mags[bin], 1e-6)
    }

    @Test
    fun loudnessOfAFullScaleHalfAmplitudeSine() {
        val f = AudioFeatureExtractor.extract(sine(440.0, 5.0, amp = 0.5), fs)
        // RMS of 0.5*sin = 0.3536 -> -9.03 dBFS
        assertEquals(-9.03, f.rmsDb, 0.2)
        assertTrue("steady tone has ~no dynamic range", f.dynamicRangeDb < 0.5)
    }

    @Test
    fun brightnessFollowsFrequency() {
        val dark = AudioFeatureExtractor.extract(sine(300.0, 4.0), fs).spectralCentroidHz
        val bright = AudioFeatureExtractor.extract(sine(5000.0, 4.0), fs).spectralCentroidHz
        assertTrue("dark=$dark bright=$bright", dark < 800 && bright > 4000)
    }

    @Test
    fun tempoOfClickTracks() {
        for (bpm in listOf(90.0, 120.0, 140.0)) {
            val est = AudioFeatureExtractor.extract(clickTrack(bpm, 20.0), fs).bpm
            assertTrue("bpm=$bpm est=$est", abs(est - bpm) / bpm < 0.04)
        }
    }

    @Test
    fun steadyToneHasNoTempo() {
        assertEquals(0.0, AudioFeatureExtractor.extract(sine(440.0, 10.0), fs).bpm, 0.0)
    }

    @Test
    fun bassWeightIsMeasured() {
        assertTrue(AudioFeatureExtractor.extract(sine(60.0, 4.0), fs).lowEnergyRatio > 0.9)
        assertTrue(AudioFeatureExtractor.extract(sine(2000.0, 4.0), fs).lowEnergyRatio < 0.01)
    }

    @Test
    fun arousalOrdersCalmBelowEnergetic() {
        val calm = AudioFeatureExtractor.extract(sine(220.0, 10.0, amp = 0.05), fs)
        val busy = clickTrack(140.0, 10.0, amp = 0.9).let { drums ->
            val hiss = sine(4000.0, 10.0, amp = 0.2)
            FloatArray(drums.size) { drums[it] + hiss[it] }
        }
        val energetic = AudioFeatureExtractor.extract(busy, fs)
        assertTrue("calm=${calm.arousal()} energetic=${energetic.arousal()}", calm.arousal() + 0.3 < energetic.arousal())
    }

    @Test
    fun shortOrSilentInputIsSafe() {
        val tiny = AudioFeatureExtractor.extract(FloatArray(1000), fs)
        assertEquals(0.0, tiny.bpm, 0.0)
        val silent = AudioFeatureExtractor.extract(FloatArray(fs * 3), fs)
        assertTrue(silent.rmsDb <= -69.0)
        assertEquals(0.0, silent.bpm, 0.0)
    }
}
