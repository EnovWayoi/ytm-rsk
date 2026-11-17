package dev.enov.ytm

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class ListeningEffects(
    val equalizer: Boolean = false,
    val bands: List<Float> = List(10) { 0f },
    val normalization: Boolean = false,
    val loudnessDb: Float = 0f,
)

/** Ten independent peaking filters, with separate delay state for each PCM channel. */
internal class EqualizerDsp(private val sampleRate: Int, private val channels: Int) {
    private data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)
    private var filters: List<Biquad> = emptyList()
    private var delays = Array(channels) { DoubleArray(0) }
    private var gain = 1.0

    init { require(sampleRate > 0 && channels > 0) }

    fun configure(effects: ListeningEffects) {
        gain = if (effects.normalization && effects.loudnessDb.isFinite()) {
            10.0.pow(-effects.loudnessDb.coerceIn(0f, 60f) / 20.0)
        } else 1.0
        filters = if (!effects.equalizer) emptyList() else FREQUENCIES.mapIndexedNotNull { index, frequency ->
            val db = effects.bands.getOrElse(index) { 0f }.takeIf { it.isFinite() }?.coerceIn(-12f, 12f) ?: 0f
            if (frequency >= sampleRate / 2.0 || db == 0f) return@mapIndexedNotNull null
            val a = 10.0.pow(db / 40.0)
            val omega = 2.0 * PI * frequency / sampleRate
            val alpha = sin(omega) / (2.0 * 1.4)
            val a0 = 1.0 + alpha / a
            Biquad((1.0 + alpha * a) / a0, -2.0 * cos(omega) / a0,
                (1.0 - alpha * a) / a0, -2.0 * cos(omega) / a0, (1.0 - alpha / a) / a0)
        }
        delays = Array(channels) { DoubleArray(filters.size * 2) }
    }

    fun sample(input: Double, channel: Int): Double {
        require(channel in 0 until channels)
        var value = if (input.isFinite()) input * gain else 0.0
        val state = delays[channel]
        filters.forEachIndexed { i, f ->
            val output = f.b0 * value + state[2 * i]
            state[2 * i] = f.b1 * value - f.a1 * output + state[2 * i + 1]
            state[2 * i + 1] = f.b2 * value - f.a2 * output
            value = output
        }
        return if (value.isFinite()) value.coerceIn(-1.0, 32767.0 / 32768.0) else 0.0
    }

    fun pcm16(input: Short, channel: Int): Short = (sample(input / 32768.0, channel) * 32768.0)
        .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    fun reset() { delays.forEach { it.fill(0.0) } }

    companion object { val FREQUENCIES = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000) }
}

@androidx.annotation.OptIn(UnstableApi::class)
class ListeningAudioProcessor : BaseAudioProcessor() {
    @Volatile private var requested = ListeningEffects()
    private var applied: ListeningEffects? = null
    private var dsp: EqualizerDsp? = null
    private var channel = 0
    private var channelCount = 0

    fun configureEffects(enabled: Boolean, bands: List<Float>, normalization: Boolean, loudnessDb: Float) {
        requested = ListeningEffects(enabled, List(10) { bands.getOrElse(it) { 0f } }, normalization, loudnessDb)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() {
        channel = 0
        applied = null
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        dsp = if (inputAudioFormat.sampleRate > 0) EqualizerDsp(inputAudioFormat.sampleRate, channelCount) else null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val engine = dsp ?: return
        val settings = requested
        if (applied != settings) { engine.configure(settings); applied = settings }
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        while (inputBuffer.remaining() >= 2) {
            output.putShort(engine.pcm16(inputBuffer.short, channel))
            channel = (channel + 1) % channelCount
        }
        if (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    override fun onReset() { dsp = null; applied = null; channel = 0; channelCount = 0 }
}
