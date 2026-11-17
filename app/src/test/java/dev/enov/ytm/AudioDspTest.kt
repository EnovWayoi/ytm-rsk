package dev.enov.ytm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDspTest {
    @Test fun flatProcessingPreservesPcmExactly() {
        val dsp = EqualizerDsp(48_000, 2)
        dsp.configure(ListeningEffects(equalizer = true))
        for (value in listOf(Short.MIN_VALUE, -12000, -1, 0, 1, 12000, Short.MAX_VALUE)) {
            assertEquals(value.toShort(), dsp.pcm16(value.toShort(), 0))
        }
    }

    @Test fun peakingBandChangesItsFrequencyWithoutCrossChannelLeakage() {
        val dsp = EqualizerDsp(48_000, 2)
        dsp.configure(ListeningEffects(equalizer = true, bands = List(10) { if (it == 5) 6f else 0f }))
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        repeat(48_000) { i ->
            val value = .1 * sin(2.0 * PI * 1000.0 * i / 48_000)
            val output = dsp.sample(value, 0)
            assertEquals(0.0, dsp.sample(0.0, 1), 0.0)
            if (i > 2000) { inputEnergy += value * value; outputEnergy += output * output }
        }
        val ratio = sqrt(outputEnergy / inputEnergy)
        assertTrue("1 kHz +6 dB should approximately double amplitude: $ratio", ratio in 1.95..2.04)
    }

    @Test fun normalizationOnlyAttenuatesLoudTracks() {
        val dsp = EqualizerDsp(48_000, 1)
        dsp.configure(ListeningEffects(normalization = true, loudnessDb = 6.0206f))
        assertEquals(.25, dsp.sample(.5, 0), .00001)
        dsp.configure(ListeningEffects(normalization = true, loudnessDb = -10f))
        assertEquals(.5, dsp.sample(.5, 0), 0.0)
    }

    @Test fun invalidConfigurationAndExtremeSamplesStayFiniteAndClipped() {
        val dsp = EqualizerDsp(8_000, 1)
        dsp.configure(ListeningEffects(true, List(10) { if (it == 0) Float.NaN else 120f }, true, Float.NaN))
        repeat(10_000) { i ->
            val result = dsp.sample(if (i % 2 == 0) 100.0 else -100.0, 0)
            assertTrue(result.isFinite() && result in -1.0..1.0)
        }
        assertEquals(0.0, dsp.sample(Double.NaN, 0), 1.0)
        dsp.reset()
        assertEquals(0.0, dsp.sample(0.0, 0), 0.0)
    }
}
