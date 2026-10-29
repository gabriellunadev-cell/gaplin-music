package com.gaplin.music.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

data class DirectDownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    val userAgent: String? = null,
    val referer: String? = null
)

sealed interface DirectDownloadResult {
    data class Enqueued(val downloadId: Long) : DirectDownloadResult
    data class Rejected(val reason: String) : DirectDownloadResult
}

/**
 * Transfers a direct, non-DRM media URL using Android's system DownloadManager.
 * This is the transport layer only; it does not extract protected streams or
 * circumvent platform restrictions. The app should request a source adapter to
 * resolve a downloadable representation before calling this class.
 */
class DirectDownloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)

    fun enqueue(request: DirectDownloadRequest): DirectDownloadResult {
        val uri = runCatching { Uri.parse(request.url) }.getOrNull()
            ?: return DirectDownloadResult.Rejected("URL inválida")
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
            return DirectDownloadResult.Rejected("Somente URLs HTTP/HTTPS são aceitas")
        }
        val safeName = request.fileName
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(120)
            .ifBlank { "gaplin_media" }

        return runCatching {
            val builder = DownloadManager.Request(uri)
                .setTitle(safeName)
                .setDescription("Download pelo Gaplin Music")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Gaplin Music/$safeName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            request.mimeType?.takeIf { it.isNotBlank() }?.let(builder::setMimeType)
            request.userAgent?.takeIf { it.isNotBlank() }?.let {
                builder.addRequestHeader("User-Agent", it)
            }
            request.referer?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                builder.addRequestHeader("Referer", it)
            }
            DirectDownloadResult.Enqueued(manager.enqueue(builder))
        }.getOrElse {
            DirectDownloadResult.Rejected(it.message ?: "Não foi possível iniciar o download")
        }
    }

    fun query(downloadId: Long): DownloadManager.Query =
        DownloadManager.Query().setFilterById(downloadId)
}
