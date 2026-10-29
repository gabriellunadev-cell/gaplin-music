package com.gaplin.music

import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailurePolicyTest {
    @Test
    fun unavailableVideoIsNotReportedAsAFormatFailure() {
        val message = DownloadFailurePolicy.userMessage(
            "[youtube] abc123: This video is not available",
            hasOwnSession = false,
            isYouTube = true
        )
        assertTrue(message.contains("não está disponível"))
        assertTrue(message.contains("Escolha outro resultado"))
    }

    @Test
    fun privateVideoExplainsTheOptionalOwnSession() {
        val message = DownloadFailurePolicy.userMessage(
            "ERROR: Private video",
            hasOwnSession = false,
            isYouTube = true
        )
        assertTrue(message.contains("cookies Netscape"))
    }

    @Test
    fun poTokenRequirementIsNotPresentedAsAFormatBug() {
        val message = DownloadFailurePolicy.userMessage(
            "PO Token required for GVS",
            hasOwnSession = true,
            isYouTube = true
        )
        assertTrue(message.contains("token oficial"))
        assertTrue(message.contains("não fabrica"))
    }

    @Test
    fun missingProgressiveFormatIsExplainedForVideoDownloads() {
        val message = DownloadFailurePolicy.userMessage(
            "Requested format is not available",
            hasOwnSession = false,
            isYouTube = true
        )
        assertTrue(message.contains("áudio e imagem no mesmo fluxo"))
    }

    @Test
    fun genericForbiddenResponseIdentifiesTheSource() {
        val message = DownloadFailurePolicy.userMessage(
            "HTTP Error 403: Forbidden",
            hasOwnSession = false,
            isYouTube = false
        )
        assertTrue(message.contains("A fonte recusou"))
    }
}
