package com.gaplin.music

import android.content.Context
import android.media.audiofx.Equalizer
import kotlin.math.ln
import kotlin.math.roundToInt

data class EqualizerBandState(
    val index: Int,
    val frequencyHz: Int,
    val label: String,
    val levelDb: Float
)

data class EqualizerSnapshot(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val preset: String = "Flat",
    val bands: List<EqualizerBandState> = emptyList(),
    val minDb: Float = -15f,
    val maxDb: Float = 15f,
    val message: String? = null
)

object AudioEqualizerController {
    val presetNames = listOf("Flat", "Bass", "Pop", "Rock", "Jazz", "Classical", "Vocal", "Electronic")

    private var effect: Equalizer? = null
    private var activeSessionId: Int = 0
    private var currentSnapshot = EqualizerSnapshot()
    private var appContext: Context? = null

    @Synchronized
    fun attach(context: Context, audioSessionId: Int): EqualizerSnapshot {
        if (audioSessionId <= 0) return currentSnapshot
        if (activeSessionId == audioSessionId && effect != null) return snapshot()

        releaseEffect()
        appContext = context.applicationContext
        return try {
            val engine = Equalizer(0, audioSessionId)
            val bandCount = engine.numberOfBands.toInt()
            val range = engine.bandLevelRange
            if (bandCount <= 0 || range.size < 2) {
                engine.release()
                currentSnapshot = EqualizerSnapshot(message = "O equalizador não está disponível neste dispositivo.")
                return currentSnapshot
            }

            effect = engine
            activeSessionId = audioSessionId
            val prefs = appContext!!.getSharedPreferences("gaplin_audio_effects", Context.MODE_PRIVATE)
            val savedPreset = prefs.getString("preset", "Flat") ?: "Flat"
            val minDb = range[0] / 100f
            val maxDb = range[1] / 100f

            if (savedPreset == "Personalizado") {
                for (index in 0 until bandCount) {
                    val frequency = engine.getCenterFreq(index.toShort()).coerceAtLeast(1)
                    val stored = prefs.getFloat("band_$index", 0f)
                    setBandLevelInternal(index, stored)
                }
            } else {
                applyPresetInternal(savedPreset, persist = false)
            }
            engine.enabled = prefs.getBoolean("enabled", false)
            currentSnapshot = buildSnapshot(minDb, maxDb)
            currentSnapshot
        } catch (error: Exception) {
            releaseEffect()
            currentSnapshot = EqualizerSnapshot(
                message = "O equalizador do sistema não pôde ser iniciado: ${error.javaClass.simpleName}."
            )
            currentSnapshot
        }
    }

    @Synchronized
    fun snapshot(): EqualizerSnapshot {
        val engine = effect ?: return currentSnapshot
        return try {
            val range = engine.bandLevelRange
            currentSnapshot = buildSnapshot(range[0] / 100f, range[1] / 100f)
            currentSnapshot
        } catch (_: Exception) {
            currentSnapshot
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): EqualizerSnapshot {
        val engine = effect ?: return currentSnapshot
        return runCatching {
            engine.enabled = enabled
            appContext?.getSharedPreferences("gaplin_audio_effects", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("enabled", enabled)?.apply()
            snapshot()
        }.getOrDefault(currentSnapshot)
    }

    @Synchronized
    fun applyPreset(name: String): EqualizerSnapshot {
        if (effect == null) return currentSnapshot
        return runCatching {
            applyPresetInternal(name, persist = true)
            effect?.enabled = true
            appContext?.getSharedPreferences("gaplin_audio_effects", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("enabled", true)?.apply()
            snapshot()
        }.getOrDefault(currentSnapshot)
    }

    @Synchronized
    fun setBandLevel(index: Int, levelDb: Float): EqualizerSnapshot {
        val engine = effect ?: return currentSnapshot
        return runCatching {
            if (index !in 0 until engine.numberOfBands.toInt()) return currentSnapshot
            setBandLevelInternal(index, levelDb)
            val prefs = appContext?.getSharedPreferences("gaplin_audio_effects", Context.MODE_PRIVATE)
            val actual = engine.getBandLevel(index.toShort()) / 100f
            prefs?.edit()
                ?.putString("preset", "Personalizado")
                ?.putFloat("band_$index", actual)
                ?.putBoolean("enabled", true)
                ?.apply()
            engine.enabled = true
            snapshot()
        }.getOrDefault(currentSnapshot)
    }

    @Synchronized
    fun release() {
        releaseEffect()
        currentSnapshot = EqualizerSnapshot()
    }

    private fun setBandLevelInternal(index: Int, levelDb: Float) {
        val engine = effect ?: return
        val range = engine.bandLevelRange
        val requested = (levelDb * 100f).roundToInt()
        val clamped = requested.coerceIn(range[0].toInt(), range[1].toInt())
        engine.setBandLevel(index.toShort(), clamped.toShort())
    }

    private fun applyPresetInternal(name: String, persist: Boolean) {
        val engine = effect ?: return
        val selected = if (name in presetNames) name else "Flat"
        val curve = when (selected) {
            "Bass" -> listOf(32.0 to 7.0, 120.0 to 6.0, 500.0 to 1.5, 2500.0 to 0.0, 12000.0 to 0.0, 20000.0 to 0.0)
            "Pop" -> listOf(32.0 to 1.5, 120.0 to 2.5, 500.0 to 1.0, 2500.0 to 3.5, 8000.0 to 2.0, 16000.0 to 1.0)
            "Rock" -> listOf(32.0 to 5.0, 120.0 to 3.0, 500.0 to -1.5, 2500.0 to 2.0, 8000.0 to 4.5, 16000.0 to 5.0)
            "Jazz" -> listOf(32.0 to 3.5, 120.0 to 2.0, 500.0 to 1.0, 2500.0 to 2.5, 8000.0 to 3.0, 16000.0 to 2.5)
            "Classical" -> listOf(32.0 to 3.0, 120.0 to 1.5, 500.0 to 0.0, 2500.0 to 2.0, 8000.0 to 3.0, 16000.0 to 4.0)
            "Vocal" -> listOf(32.0 to -2.0, 120.0 to -1.0, 500.0 to 1.0, 1800.0 to 4.0, 4000.0 to 4.5, 12000.0 to 1.5)
            "Electronic" -> listOf(32.0 to 5.5, 120.0 to 4.0, 500.0 to 0.5, 2500.0 to 2.5, 8000.0 to 4.0, 16000.0 to 3.5)
            else -> listOf(32.0 to 0.0, 120.0 to 0.0, 500.0 to 0.0, 2500.0 to 0.0, 8000.0 to 0.0, 16000.0 to 0.0)
        }

        for (index in 0 until engine.numberOfBands.toInt()) {
            val centerHz = (engine.getCenterFreq(index.toShort()) / 1000.0).coerceAtLeast(20.0)
            setBandLevelInternal(index, interpolateCurve(curve, centerHz).toFloat())
        }
        if (persist) {
            appContext?.getSharedPreferences("gaplin_audio_effects", Context.MODE_PRIVATE)
                ?.edit()?.putString("preset", selected)?.apply()
        }
    }

    private fun interpolateCurve(points: List<Pair<Double, Double>>, frequencyHz: Double): Double {
        val x = ln(frequencyHz.coerceAtLeast(1.0))
        if (x <= ln(points.first().first)) return points.first().second
        if (x >= ln(points.last().first)) return points.last().second
        for (index in 0 until points.lastIndex) {
            val left = points[index]
            val right = points[index + 1]
            val leftX = ln(left.first)
            val rightX = ln(right.first)
            if (x in leftX..rightX) {
                val fraction = ((x - leftX) / (rightX - leftX)).coerceIn(0.0, 1.0)
                return left.second + (right.second - left.second) * fraction
            }
        }
        return 0.0
    }

    private fun buildSnapshot(minDb: Float, maxDb: Float): EqualizerSnapshot {
        val engine = effect ?: return currentSnapshot
        val bands = (0 until engine.numberOfBands.toInt()).map { index ->
            val centerHz = engine.getCenterFreq(index.toShort()) / 1000
            EqualizerBandState(
                index = index,
                frequencyHz = centerHz,
                label = when {
                    centerHz >= 1000 -> String.format(java.util.Locale.US, "%.1fk", centerHz / 1000f)
                    else -> "${centerHz}Hz"
                },
                levelDb = engine.getBandLevel(index.toShort()) / 100f
            )
        }
        val preset = appContext?.getSharedPreferences("gaplin_audio_effects", Context.MODE_PRIVATE)
            ?.getString("preset", "Flat") ?: "Flat"
        return EqualizerSnapshot(
            supported = true,
            enabled = engine.enabled,
            preset = preset,
            bands = bands,
            minDb = minDb,
            maxDb = maxDb
        )
    }

    private fun releaseEffect() {
        runCatching { effect?.enabled = false }
        runCatching { effect?.release() }
        effect = null
        activeSessionId = 0
    }
}
