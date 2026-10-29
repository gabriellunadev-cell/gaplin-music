package com.gaplin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PCM spectrum sampled inside Media3's playback pipeline.
 *
 * The processor copies samples only for analysis and forwards the original PCM unchanged.
 * No microphone permission or external audio capture is required.
 */
object GaplinAudioSpectrum {
    const val BAND_COUNT = 36

    @Volatile
    private var latestBands = FloatArray(BAND_COUNT)

    @Volatile
    private var latestLevel = 0f

    fun readBands(): FloatArray = latestBands.copyOf()

    fun readLevel(): Float = latestLevel

    internal fun publish(bands: FloatArray, level: Float) {
        latestBands = bands
        latestLevel = level
    }

    internal fun reset() {
        latestBands = FloatArray(BAND_COUNT)
        latestLevel = 0f
    }
}

@UnstableApi
class GaplinSpectrumAudioProcessor : BaseAudioProcessor() {
    private val windowSize = 2048
    private val samples = FloatArray(windowSize)
    private val hannWindow = FloatArray(windowSize) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (windowSize - 1))).toFloat()
    }

    private var sampleRate = 44100
    private var channelCount = 2
    private var encoding = C.ENCODING_INVALID
    private var sampleIndex = 0
    private var coefficients = FloatArray(GaplinAudioSpectrum.BAND_COUNT)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding

        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val highestFrequency = min(14000f, sampleRate * 0.45f)
        coefficients = FloatArray(GaplinAudioSpectrum.BAND_COUNT) { band ->
            val position = band / (GaplinAudioSpectrum.BAND_COUNT - 1f)
            val frequency = 45f * (highestFrequency / 45f).pow(position)
            (2.0 * cos(2.0 * PI * frequency / sampleRate)).toFloat()
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return

        val analysisBuffer = inputBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frameBytes = channelCount * 2
                while (analysisBuffer.remaining() >= frameBytes) {
                    var mixedSample = 0f
                    repeat(channelCount) {
                        mixedSample += analysisBuffer.short / 32768f
                    }
                    pushSample(mixedSample / channelCount)
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                val frameBytes = channelCount * 4
                while (analysisBuffer.remaining() >= frameBytes) {
                    var mixedSample = 0f
                    repeat(channelCount) {
                        val sample = analysisBuffer.float
                        mixedSample += if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
                    }
                    pushSample(mixedSample / channelCount)
                }
            }
        }

        // Forward the PCM buffer unchanged so playback quality and timing are preserved.
        val outputBuffer = replaceOutputBuffer(byteCount)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun pushSample(sample: Float) {
        samples[sampleIndex++] = sample
        if (sampleIndex == windowSize) {
            analyzeWindow()
            sampleIndex = 0
        }
    }

    private fun analyzeWindow() {
        var squareSum = 0f
        for (index in 0 until windowSize) {
            val sample = samples[index]
            squareSum += sample * sample
        }
        val rms = sqrt(squareSum / windowSize)

        val magnitudes = FloatArray(GaplinAudioSpectrum.BAND_COUNT)
        for (band in magnitudes.indices) {
            val coefficient = coefficients[band]
            var q1 = 0f
            var q2 = 0f
            for (index in 0 until windowSize) {
                val q0 = coefficient * q1 - q2 + samples[index] * hannWindow[index]
                q2 = q1
                q1 = q0
            }
            val power = max(0f, q1 * q1 + q2 * q2 - coefficient * q1 * q2)
            val amplitude = (sqrt(power) * 2f / windowSize).coerceIn(0f, 1f)
            magnitudes[band] = (amplitude * 4.2f).coerceIn(0f, 1f)
        }

        val previous = GaplinAudioSpectrum.readBands()
        for (index in magnitudes.indices) {
            magnitudes[index] = max(magnitudes[index], previous[index] * 0.72f)
        }

        GaplinAudioSpectrum.publish(
            magnitudes,
            (rms * 3f).coerceIn(0f, 1f)
        )
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        sampleIndex = 0
    }

    override fun onReset() {
        sampleIndex = 0
        GaplinAudioSpectrum.reset()
    }
}
