package com.gaplin.music.download

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Initializes native tools and refreshes the bundled yt-dlp stable release.
 * Call from a background dispatcher.
 */
object YtDlpEngine {
    private const val TAG = "YtDlpEngine"

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        YoutubeDL.getInstance().init(appContext)
        FFmpeg.getInstance().init(appContext)

        // A temporary GitHub/API outage must not disable an otherwise usable downloader.
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(
                appContext,
                YoutubeDL.UpdateChannel.STABLE
            )
        }.onFailure { error ->
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
