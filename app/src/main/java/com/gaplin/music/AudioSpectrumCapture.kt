package com.gaplin.music

import android.media.audiofx.Visualizer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class AudioSpectrumCapture(
    audioSessionId: Int,
    private val onSpectrum: (FloatArray) -> Unit
) : AutoCloseable {
    companion object {
        const val BAND_COUNT = 64
        private const val CAPTURE_RATE_MICROS = 50_000
    }

    private var visualizer: Visualizer? = null
    private val smoothed = FloatArray(BAND_COUNT)
    var isAvailable: Boolean = false
        private set
    var errorMessage: String? = null
        private set

    init {
        if (audioSessionId <= 0) {
            errorMessage = "A sessão de áudio ainda não está disponível."
            return
        }

        try {
            val effect = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            val maxSize = range[1].coerceAtMost(1024)
            val captureSize = Integer.highestOneBit(maxSize).coerceAtLeast(range[0])
            effect.captureSize = captureSize
            effect.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            effect.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 8) return
                        onSpectrum(extractBands(fft))
                    }
                },
                CAPTURE_RATE_MICROS,
                false,
                true
            )
            effect.enabled = true
            visualizer = effect
            isAvailable = true
        } catch (error: Exception) {
            errorMessage = when (error) {
                is SecurityException -> "Permissão de áudio necessária para analisar o espectro."
                else -> "O Android não disponibilizou a captura FFT para esta sessão."
            }
            runCatching { visualizer?.release() }
            visualizer = null
            isAvailable = false
        }
    }

    @Synchronized
    private fun extractBands(fft: ByteArray): FloatArray {
        val output = FloatArray(BAND_COUNT)
        val maxBin = (fft.size / 2 - 1).coerceAtLeast(2)
        val minLog = ln(1.0)
        val maxLog = ln(maxBin.toDouble())

        for (band in 0 until BAND_COUNT) {
            val startPosition = band.toDouble() / BAND_COUNT
            val endPosition = (band + 1).toDouble() / BAND_COUNT
            val startBin = exp(minLog + (maxLog - minLog) * startPosition).toInt().coerceIn(1, maxBin)
            val endBin = ceil(exp(minLog + (maxLog - minLog) * endPosition)).toInt().coerceIn(startBin + 1, maxBin + 1)

            var energy = 0.0
            var samples = 0
            for (bin in startBin until endBin) {
                val realIndex = bin * 2
                val imagIndex = realIndex + 1
                if (imagIndex >= fft.size) break
                val real = fft[realIndex].toInt()
                val imaginary = fft[imagIndex].toInt()
                energy += sqrt((real * real + imaginary * imaginary).toDouble())
                samples++
            }

            val average = if (samples > 0) energy / samples else 0.0
            // Log compression makes quiet details visible without flattening transient peaks.
            val normalized = (ln(1.0 + average * 1.8) / ln(1.0 + 180.0)).toFloat().coerceIn(0f, 1f)
            val boosted = (normalized * 1.32f).coerceIn(0f, 1f)
            smoothed[band] = smoothed[band] * 0.60f + boosted * 0.40f
            output[band] = smoothed[band]
        }
        return output
    }

    override fun close() {
        isAvailable = false
        runCatching {
            visualizer?.enabled = false
            visualizer?.setDataCaptureListener(null, 0, false, false)
            visualizer?.release()
        }
        visualizer = null
        smoothed.fill(0f)
    }
}
