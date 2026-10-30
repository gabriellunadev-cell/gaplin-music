package com.gaplin.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatPolicyTest {
    @Test
    fun audioSelectionUsesAudioOnlyStreams() {
        assertEquals("bestaudio", DownloadFormatPolicy.selector(audioOnly = true))
    }

    @Test
    fun videoSelectionMergesBestStreamsUpToFourK() {
        assertEquals(
            "bestvideo[height<=2160]+bestaudio/best[height<=2160]",
            DownloadFormatPolicy.selector(audioOnly = false, videoHeight = 2160)
        )
    }

    @Test
    fun fullHdAndHdPresetsSetTheRequestedMaximumHeight() {
        assertTrue(DownloadFormatPolicy.selector(false, 1080).contains("height<=1080"))
        assertTrue(DownloadFormatPolicy.selector(false, 720).contains("height<=720"))
        assertTrue(DownloadFormatPolicy.selector(false, 480).contains("height<=480"))
    }

    @Test
    fun audioFormatIsRestrictedToSupportedContainers() {
        assertEquals("mp3", DownloadFormatPolicy.validateAudioFormat("mp3"))
        assertEquals("m4a", DownloadFormatPolicy.validateAudioFormat("m4a"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedAudioFormatIsRejected() {
        DownloadFormatPolicy.validateAudioFormat("exe")
    }
}
