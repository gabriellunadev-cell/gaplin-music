package com.gaplin.music

/**
 * Select formats that the current APK can save without transcoding or merging.
 * Audio mode must never fall back to a video container.
 * Video mode requires a single progressive stream containing both tracks.
 */
internal object DownloadFormatPolicy {
    fun selector(audioOnly: Boolean): String = if (audioOnly) {
        "bestaudio"
    } else {
        "best[height<=720][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]"
    }
}
