package com.gaplin.music

internal object DownloadMimePolicy {
    private const val GENERIC_MIME = "application/octet-stream"

    fun storageMime(actualMime: String, audioOnly: Boolean, fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return if (audioOnly && extension == "weba") GENERIC_MIME else actualMime
    }

    fun resolvedMime(providerMime: String?, fileName: String, inferredMime: String): String {
        val providerValue = providerMime?.trim().orEmpty()
        if (providerValue.isBlank() || providerValue.equals(GENERIC_MIME, ignoreCase = true)) {
            return inferredMime
        }
        return providerValue
    }
}
