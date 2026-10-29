package com.gaplin.music.download

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Initializes native tools and refreshes the bundled yt-dlp stable release.
 * Call from a background dispatcher; the library serializes its updater internally.
 */
object YtDlpEngine {
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        YoutubeDL.getInstance().init(appContext)
        FFmpeg.getInstance().init(appContext)
        YoutubeDL.getInstance().updateYoutubeDL(
            appContext,
            YoutubeDL.UpdateChannel.STABLE
        )
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
