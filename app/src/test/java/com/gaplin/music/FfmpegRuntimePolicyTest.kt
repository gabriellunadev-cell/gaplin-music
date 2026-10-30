package com.gaplin.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegRuntimePolicyTest {
    @Test
    fun detectsEnabledMp4MuxerFromFfmpegFeatureTable() {
        val output = """
            Muxer
             E mov             QuickTime / MOV
             E mp4             MP4 (MPEG-4 Part 14)
             DE matroska       Matroska
        """.trimIndent()

        assertTrue(FfmpegRuntimePolicy.supportsMuxer(output, "mp4"))
    }

    @Test
    fun doesNotMistakeMovForMp4() {
        val output = " E mov QuickTime / MOV"
        assertFalse(FfmpegRuntimePolicy.supportsMuxer(output, "mp4"))
    }

    @Test
    fun demuxOnlyEntryDoesNotCountAsAnOutputMuxer() {
        val output = " D mp4 MPEG-4 input"
        assertFalse(FfmpegRuntimePolicy.supportsMuxer(output, "mp4"))
    }

    @Test
    fun ignoresHeadersAndEmptyNames() {
        val output = "Muxer\n --\n"
        assertFalse(FfmpegRuntimePolicy.supportsMuxer(output, "mp4"))
        assertFalse(FfmpegRuntimePolicy.supportsMuxer(output, " "))
    }
}
