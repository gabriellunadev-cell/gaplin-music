package com.gaplin.music

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.LogCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

data class DownloadedMedia(
    val uri: Uri,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val audioOnly: Boolean
)

class YtDlpDownloadManager(context: Context) {
    private val appContext = context.applicationContext

    private val tempDirectory: File by lazy {
        File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
            "gaplin-download-tmp"
        ).apply { mkdirs() }
    }

    private val privateDownloadsDirectory: File by lazy {
        File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
            "Gaplin Music"
        ).apply { mkdirs() }
    }

    fun engineVersion(): String = runCatching { YtDlp.getVersion() }.getOrDefault("desconhecida")

    suspend fun initialize() = withContext(Dispatchers.IO) {
        YtDlp.init(appContext)
    }

    suspend fun listDownloaded(): List<DownloadedMedia> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = mutableListOf<DownloadedMedia>()
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Gaplin Music/"
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)"
            val selectionArgs = arrayOf(
                relativePath,
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )

            appContext.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: "Mídia baixada"
                    val mime = cursor.getString(mimeIndex) ?: mimeFromName(name)
                    val mediaType = cursor.getInt(typeIndex)
                    val audioOnly = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO ||
                        mime.startsWith("audio/") || isAudioExtension(name)

                    result += DownloadedMedia(
                        uri = ContentUris.withAppendedId(collection, id),
                        title = name.substringBeforeLast('.', name),
                        fileName = name,
                        mimeType = mime,
                        audioOnly = audioOnly
                    )
                }
            }
            result
        } else {
            privateDownloadsDirectory.listFiles()
                .orEmpty()
                .filter { it.isFile && !it.name.endsWith(".part", true) }
                .sortedByDescending { it.lastModified() }
                .map { file ->
                    val mime = mimeFromName(file.name)
                    DownloadedMedia(
                        uri = Uri.fromFile(file),
                        title = file.name.substringBeforeLast('.', file.name),
                        fileName = file.name,
                        mimeType = mime,
                        audioOnly = mime.startsWith("audio/") || isAudioExtension(file.name)
                    )
                }
        }
    }

    suspend fun download(
        item: RemoteMediaResult,
        audioOnly: Boolean,
        onProgress: (Float, Long) -> Unit
    ): List<DownloadedMedia> = withContext(Dispatchers.IO) {
        require(item.kind != RemoteMediaKind.CHANNEL) {
            "Escolha um vídeo ou uma playlist para baixar."
        }
        require(item.url.startsWith("https://")) {
            "Por segurança, somente links HTTPS são aceitos."
        }

        initialize()

        val jobDirectory = File(
            tempDirectory,
            "${safePathPart(item.id)}-${System.currentTimeMillis()}"
        ).apply {
            if (!mkdirs() && !isDirectory) {
                throw IllegalStateException("Não foi possível preparar a pasta temporária do download.")
            }
        }

        try {
            val outputTemplate = File(
                jobDirectory,
                "%(title).120B [%(id)s].%(ext)s"
            ).absolutePath

            // Keep audio/video in one progressive stream: this free runtime does
            // not bundle FFmpeg, so it must not select DASH pairs that require merging.
            val format = if (audioOnly) {
                "bestaudio[ext=m4a]/bestaudio"
            } else {
                "best[height<=720][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]"
            }

            val request = YtDlpRequest(item.url)
                .setOutputTemplate(outputTemplate)
                .addOption("-f", format)
                .addOption("--retries", "5")
                .addOption("--fragment-retries", "5")
                .addOption("--extractor-retries", "3")
                .addOption("--file-access-retries", "3")
                .addOption("--concurrent-fragments", "1")
                .addOption("--no-overwrites")
                .addOption("--newline")

            val host = Uri.parse(item.url).host.orEmpty().lowercase(Locale.ROOT)
            if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")) {
                // Current yt-dlp guidance and issue reports identify the Android
                // player as a workaround for some YouTube SABR/CDN 403 responses.
                request.addOption("--extractor-args", "youtube:player_client=android")
            }

            if (item.kind == RemoteMediaKind.VIDEO) {
                request.addOption("--no-playlist")
            }

            val callback = DownloadProgressCallback { progress, eta, _ ->
                onProgress(progress.coerceIn(0f, 100f), eta.coerceAtLeast(0L))
            }

            val diagnostics = CopyOnWriteArrayList<String>()
            val logCallback = LogCallback { level, message ->
                if (level == "ERROR" || level == "WARNING") {
                    diagnostics.add("$level: " + sanitizeDiagnostic(message))
                }
            }

            val response = try {
                YtDlp.executeDebug(request, logCallback, callback).get()
            } catch (error: ExecutionException) {
                val cause = error.cause ?: error
                throw IllegalStateException(
                    buildDownloadError(cause.message, diagnostics),
                    cause
                )
            }

            if (!response.isSuccess) {
                throw IllegalStateException(
                    buildDownloadError(
                        diagnostics.lastOrNull() ?: "Código de saída ${response.exitCode}.",
                        diagnostics
                    )
                )
            }

            val completedFiles = jobDirectory.listFiles()
                .orEmpty()
                .filter { it.isFile && !it.name.endsWith(".part", true) }
                .sortedBy { it.name }

            if (completedFiles.isEmpty()) {
                throw IllegalStateException(
                    "O yt-dlp terminou sem criar um arquivo. Verifique se a mídia está disponível e se o formato pode ser baixado."
                )
            }

            completedFiles.map { file -> publish(file, audioOnly) }
        } finally {
            // Each job has its own directory; failed partial files cannot poison a retry.
            runCatching { jobDirectory.deleteRecursively() }
        }
    }

    private fun publish(source: File, audioOnly: Boolean): DownloadedMedia {
        val extension = source.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = when {
            audioOnly && extension == "webm" -> "audio/webm"
            audioOnly && extension == "mp4" -> "audio/mp4"
            else -> mimeFromName(source.name).let {
                if (it == "application/octet-stream" && audioOnly) "audio/*" else it
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (audioOnly) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Gaplin Music")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = appContext.contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Não foi possível criar o arquivo na biblioteca de mídia.")

            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Não foi possível gravar o arquivo baixado.")

                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, published, null, null)

                return DownloadedMedia(
                    uri = uri,
                    title = source.name.substringBeforeLast('.', source.name),
                    fileName = source.name,
                    mimeType = mime,
                    audioOnly = audioOnly || mime.startsWith("audio/")
                )
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val destination = File(privateDownloadsDirectory, source.name)
        source.copyTo(destination, overwrite = true)
        return DownloadedMedia(
            uri = Uri.fromFile(destination),
            title = destination.name.substringBeforeLast('.', destination.name),
            fileName = destination.name,
            mimeType = mime,
            audioOnly = audioOnly || mime.startsWith("audio/")
        )
    }

    private fun buildDownloadError(
        causeMessage: String?,
        diagnostics: List<String>
    ): String {
        val raw = diagnostics.lastOrNull { it.startsWith("ERROR:") }
            ?: diagnostics.lastOrNull()
            ?: causeMessage?.lineSequence()?.lastOrNull()?.trim()
            ?: "Falha desconhecida no motor yt-dlp."
        val details = sanitizeDiagnostic(raw)

        if (details.contains("403", ignoreCase = true) ||
            causeMessage?.contains("403", ignoreCase = true) == true
        ) {
            return "A fonte recusou a transferência (HTTP 403). O cliente Android do yt-dlp foi tentado; este conteúdo ainda pode exigir verificação/PO Token, sessão válida ou não oferecer um formato compatível."
        }
        if (details.contains("private video", ignoreCase = true) ||
            details.contains("members-only", ignoreCase = true) ||
            details.contains("sign in", ignoreCase = true)
        ) {
            return "A fonte exige autenticação ou restringe este conteúdo. Use somente uma sessão própria e conteúdo ao qual você tenha acesso autorizado."
        }
        return details.take(500)
    }

    private fun sanitizeDiagnostic(message: String): String {
        return message
            .replace(Regex("(?i)(authorization|cookie|token)=\\S+"), "$1=[oculto]")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun safePathPart(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "media" }

    private fun mimeFromName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    private fun isAudioExtension(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "weba")
    }
}
