package com.gaplin.music

/**
 * yt-dlp format selectors backed by the packaged FFmpeg executable.
 * A video height is an upper bound; yt-dlp picks the best available stream
 * not exceeding it, then FFmpeg muxes video and audio into one playable file.
 */
internal object DownloadFormatPolicy {
    val videoHeights = listOf(2160, 1080, 720, 480)
    const val videoMergeContainer = "mp4"

    val audioFormats = listOf(
        "mp3" to "MP3",
        "m4a" to "M4A (AAC)"
    )

    fun selector(audioOnly: Boolean, videoHeight: Int = 1080): String {
        return if (audioOnly) {
            "bestaudio"
        } else {
            val height = videoHeight.coerceIn(144, 4320)
            "bestvideo[height<=${height}]+bestaudio/best[height<=${height}]"
        }
    }

    fun validateAudioFormat(format: String): String {
        require(audioFormats.any { it.first == format }) {
            "Formato de áudio não suportado: $format"
        }
        return format
    }
}
