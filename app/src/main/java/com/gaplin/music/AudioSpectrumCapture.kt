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
        private const val TARGET_CAPTURE_RATE_MILLIHZ = 20_000
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
        } else try {
            val effect = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            val maxSize = range[1].coerceAtMost(1024)
            val captureSize = Integer.highestOneBit(maxSize).coerceAtLeast(range[0])
            effect.captureSize = captureSize
            effect.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            val captureRateMilliHz = Visualizer.getMaxCaptureRate()
                .coerceAtMost(TARGET_CAPTURE_RATE_MILLIHZ)
                .coerceAtLeast(1)
            val listenerStatus = effect.setDataCaptureListener(
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
                captureRateMilliHz,
                false,
                true
            )
            if (listenerStatus != 0) {
                effect.release()
                throw IllegalStateException("Visualizer rejeitou a taxa FFT (status=$listenerStatus, rate=$captureRateMilliHz mHz).")
            }
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

            var energySquared = 0.0
            var peak = 0.0
            var samples = 0
            for (bin in startBin until endBin) {
                val realIndex = bin * 2
                val imagIndex = realIndex + 1
                if (imagIndex >= fft.size) break
                val real = fft[realIndex].toInt()
                val imaginary = fft[imagIndex].toInt()
                val magnitude = sqrt((real * real + imaginary * imaginary).toDouble())
                energySquared += magnitude * magnitude
                peak = maxOf(peak, magnitude)
                samples++
            }

            val rms = if (samples > 0) sqrt(energySquared / samples) else 0.0
            val measured = peak * 0.62 + rms * 0.38
            // Calibrated for the 8-bit magnitude range returned by Android Visualizer.
            // Faster attack and slower release make kicks/snare transients visibly react.
            val normalized = (ln(1.0 + measured * 2.2) / ln(1.0 + 58.0 * 2.2))
                .toFloat().coerceIn(0f, 1f)
            val boosted = (normalized * 1.85f).coerceIn(0f, 1f)
            val previous = smoothed[band]
            smoothed[band] = if (boosted > previous) {
                previous * 0.22f + boosted * 0.78f
            } else {
                previous * 0.76f + boosted * 0.24f
            }
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
