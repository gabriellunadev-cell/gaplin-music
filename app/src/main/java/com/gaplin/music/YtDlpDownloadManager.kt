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
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
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
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir, "gaplin-download-tmp")
            .apply { mkdirs() }
    }
    private val privateDownloadsDirectory: File by lazy {
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir, "Gaplin Music")
            .apply { mkdirs() }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        YtDlp.init(appContext)
    }

    suspend fun listDownloaded(): List<DownloadedMedia> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = mutableListOf<DownloadedMedia>()
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE
            )
            val relativePath = "\${Environment.DIRECTORY_DOWNLOADS}/Gaplin Music/"
            appContext.contentResolver.query(
                collection,
                projection,
                "\${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(relativePath),
                "\${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: "Mídia baixada"
                    val mime = cursor.getString(mimeIndex) ?: mimeFromName(name)
                    result += DownloadedMedia(
                        uri = ContentUris.withAppendedId(collection, id),
                        title = name.substringBeforeLast('.', name),
                        fileName = name,
                        mimeType = mime,
                        audioOnly = mime.startsWith("audio/") || isAudioExtension(name)
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
        require(item.kind != RemoteMediaKind.CHANNEL) { "Escolha um vídeo ou uma playlist para baixar." }
        require(item.url.startsWith("https://")) { "Por segurança, somente links HTTPS são aceitos." }
        initialize()

        val before = tempDirectory.listFiles().orEmpty().map { it.name }.toSet()
        val outputTemplate = File(
            tempDirectory,
            "%(title).120B [%(id)s].%(ext)s"
        ).absolutePath

        val format = if (audioOnly) {
            "bestaudio[ext=m4a]/bestaudio"
        } else {
            "best[height<=1080][ext=mp4]/best[height<=720]/best"
        }

        val request = YtDlpRequest(item.url)
            .setOutputTemplate(outputTemplate)
            .addOption("-f", format)
            .addOption("--newline")

        if (item.kind == RemoteMediaKind.VIDEO) {
            request.addOption("--no-playlist")
        }

        val callback = DownloadProgressCallback { progress, eta, _ ->
            onProgress(progress.coerceIn(0f, 100f), eta.coerceAtLeast(0L))
        }

        val response = try {
            YtDlp.executeAsync(request, callback).get()
        } catch (error: ExecutionException) {
            throw IllegalStateException(error.cause?.message ?: "Falha no motor yt-dlp.", error.cause ?: error)
        }

        if (!response.isSuccess) {
            throw IllegalStateException("O yt-dlp não conseguiu concluir este download (código \${response.exitCode}).")
        }

        val newFiles = tempDirectory.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part", true) && it.name !in before }
            .sortedBy { it.name }

        if (newFiles.isEmpty()) {
            throw IllegalStateException("O download terminou, mas nenhum arquivo foi encontrado.")
        }

        newFiles.map { file -> publish(file, audioOnly) }
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
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "\${Environment.DIRECTORY_DOWNLOADS}/Gaplin Music")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não foi possível criar o arquivo na pasta Downloads.")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Não foi possível gravar o arquivo baixado.")
                val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, published, null, null)
                source.delete()
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
        source.delete()
        return DownloadedMedia(
            uri = Uri.fromFile(destination),
            title = destination.name.substringBeforeLast('.', destination.name),
            fileName = destination.name,
            mimeType = mime,
            audioOnly = audioOnly || mime.startsWith("audio/")
        )
    }

    private fun mimeFromName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            else -> "application/octet-stream"
        }
    }

    private fun isAudioExtension(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "weba")
    }
}
