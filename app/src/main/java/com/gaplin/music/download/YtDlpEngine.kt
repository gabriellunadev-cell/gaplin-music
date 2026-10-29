package com.gaplin.music.download

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Initializes native tools and periodically refreshes the stable yt-dlp release.
 * Call from a background dispatcher.
 */
object YtDlpEngine {
    private const val TAG = "YtDlpEngine"
    private const val PREFS_NAME = "gaplin_ytdlp_engine"
    private const val KEY_LAST_CHECK = "last_update_check"
    private const val KEY_LAST_CHECK_SUCCEEDED = "last_update_check_succeeded"
    private const val SUCCESS_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val RETRY_INTERVAL_MS = 15L * 60L * 1000L

    @Synchronized
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        YoutubeDL.getInstance().init(appContext)
        FFmpeg.getInstance().init(appContext)

        val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L)
        val interval = if (preferences.getBoolean(KEY_LAST_CHECK_SUCCEEDED, false)) {
            SUCCESS_INTERVAL_MS
        } else {
            RETRY_INTERVAL_MS
        }
        if (lastCheck > 0L && now >= lastCheck && now - lastCheck < interval) return

        // Avoid hammering the GitHub API: check daily after success, retry sooner after failure.
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(
                appContext,
                YoutubeDL.UpdateChannel.STABLE
            )
        }.onSuccess {
            preferences.edit()
                .putLong(KEY_LAST_CHECK, now)
                .putBoolean(KEY_LAST_CHECK_SUCCEEDED, true)
                .apply()
        }.onFailure { error ->
            preferences.edit()
                .putLong(KEY_LAST_CHECK, now)
                .putBoolean(KEY_LAST_CHECK_SUCCEEDED, false)
                .apply()
            Log.w(TAG, "Could not refresh yt-dlp; keeping the installed engine", error)
        }
    }

    fun readableError(error: Throwable, fallback: String): String {
        val lines = error.message.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("WARNING: Your yt-dlp version") }
            .toList()
        return lines.takeLast(3).joinToString(" ").take(420).ifBlank { fallback }
    }
}
