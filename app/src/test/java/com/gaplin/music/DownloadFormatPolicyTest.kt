package com.gaplin.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatPolicyTest {
    @Test
    fun audioSelectionAcceptsAnyAvailableAudioContainer() {
        assertEquals("bestaudio", DownloadFormatPolicy.selector(audioOnly = true))
    }

    @Test
    fun videoSelectionPrefersMuxedFormatsWithoutRequiringFfmpegMerge() {
        val selector = DownloadFormatPolicy.selector(audioOnly = false)
        assertTrue(selector.startsWith("best[height<=720][vcodec!=none][acodec!=none]"))
        assertTrue(selector.contains("/best[vcodec!=none][acodec!=none]"))
        assertTrue(selector.none { it == '+' })
    }
}
