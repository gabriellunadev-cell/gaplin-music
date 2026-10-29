package com.gaplin.music

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMimePolicyTest {
    @Test
    fun webmAudioUsesGenericMimeOnlyForMediaStoreRegistration() {
        assertEquals(
            "application/octet-stream",
            DownloadMimePolicy.storageMime("audio/webm", true, "song.weba")
        )
    }

    @Test
    fun m4aAudioKeepsItsSupportedMimeType() {
        assertEquals(
            "audio/mp4",
            DownloadMimePolicy.storageMime("audio/mp4", true, "song.m4a")
        )
    }

    @Test
    fun realPlaybackMimeIsRecoveredFromWebaExtension() {
        assertEquals(
            "audio/webm",
            DownloadMimePolicy.resolvedMime("application/octet-stream", "song.weba", "audio/webm")
        )
    }

    @Test
    fun explicitProviderMimeIsPreserved() {
        assertEquals(
            "video/mp4",
            DownloadMimePolicy.resolvedMime("video/mp4", "clip.mp4", "application/octet-stream")
        )
    }
}
