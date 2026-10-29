package com.gaplin.music

/**
 * Format selection for the embedded yt-dlp runtime.
 *
 * This app does not ship FFmpeg, so video selection prefers a single
 * progressive/muxed stream and audio selection accepts any audio container.
 */
internal object DownloadFormatPolicy {
    fun selector(audioOnly: Boolean): String = if (audioOnly) {
        "bestaudio/best"
    } else {
        "best[height<=720][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best[height<=720]/best"
    }
}
