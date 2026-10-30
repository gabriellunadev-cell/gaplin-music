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
    fun detectsImageCodecsAndDemuxer() {
        val decoders = """
             V..... mjpeg               MJPEG video
             V..... png                 PNG image
             V..... webp                WebP image
        """.trimIndent()
        val demuxers = """
             D  image2                  image file demuxer
             D  image_webp_pipe         WebP image pipe
             D  aac                     ADTS AAC
        """.trimIndent()
        val encoders = """
             V..... mjpeg               MJPEG encoder
             V..... png                 PNG encoder
        """.trimIndent()

        assertTrue(FfmpegRuntimePolicy.supportsComponent(decoders, "mjpeg"))
        assertTrue(FfmpegRuntimePolicy.supportsComponent(decoders, "png"))
        assertTrue(FfmpegRuntimePolicy.supportsComponent(decoders, "webp"))
        assertTrue(FfmpegRuntimePolicy.supportsComponent(demuxers, "image2"))
        assertTrue(FfmpegRuntimePolicy.supportsComponent(encoders, "mjpeg"))
        assertFalse(FfmpegRuntimePolicy.supportsComponent(decoders, "image2"))
    }

    @Test
    fun ignoresHeadersAndEmptyNames() {
        val output = "Muxer\n --\n"
        assertFalse(FfmpegRuntimePolicy.supportsMuxer(output, "mp4"))
        assertFalse(FfmpegRuntimePolicy.supportsMuxer(output, " "))
    }
}
