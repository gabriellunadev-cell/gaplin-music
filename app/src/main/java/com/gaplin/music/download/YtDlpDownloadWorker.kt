package com.gaplin.music.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class YtDlpDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "gaplin-ytdlp-download"
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_MAX_HEIGHT = "max_height"
        const val KEY_PROGRESS = "progress"
        const val KEY_ETA = "eta"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FILE_URI = "file_uri"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "gaplin_downloads"
    }

    private val notificationId = (id.hashCode() and 0x7fffffff).coerceAtLeast(1)

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.trim().orEmpty()
        val title = inputData.getString(KEY_TITLE)?.take(120).orEmpty().ifBlank { "Mídia" }
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, false)
        val maxHeight = inputData.getInt(KEY_MAX_HEIGHT, 0).coerceIn(0, 4320)

        if (url.isBlank() || !isHttpUrl(url)) {
            return Result.failure(workDataOf(KEY_ERROR to "Link inválido."))
        }

        return try {
            createNotificationChannel()
            setForeground(createForegroundInfo(title, 0))
            setProgress(workDataOf(KEY_TITLE to title, KEY_PROGRESS to 0))

            val output = withContext(Dispatchers.IO) {
                YtDlpEngine.initialize(applicationContext)

                val downloadRoot = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: File(applicationContext.filesDir, "downloads"),
                    "Gaplin Music"
                )
                val outputDirectory = File(downloadRoot, id.toString())
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                    throw IllegalStateException("Não foi possível criar a pasta de downloads.")
                }

                val startedAt = System.currentTimeMillis()
                val request = YoutubeDLRequest(url)
                    .addOption("--no-playlist")
                    .addOption("--newline")
                    .addOption("--no-mtime")
                    .addOption("-o", File(outputDirectory, "%(title).160s [%(id)s].%(ext)s").absolutePath)

                if (audioOnly) {
                    request
                        .addOption("-x")
                        .addOption("--audio-format", "mp3")
                        .addOption("--audio-quality", "0")
                } else {
                    val selector = if (maxHeight > 0) {
                        "bestvideo[height<=$maxHeight]+bestaudio/best[height<=$maxHeight]/best"
                    } else {
                        "bestvideo+bestaudio/best"
                    }
                    request
                        .addOption("-f", selector)
                        .addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(request, id.toString()) { progress, eta, _ ->
                    val safeProgress = progress.toInt().coerceIn(0, 100)
                    setProgressAsync(
                        workDataOf(
                            KEY_TITLE to title,
                            KEY_PROGRESS to safeProgress,
                            KEY_ETA to eta.coerceAtLeast(0L).toInt()
                        )
                    )
                    updateNotification(title, safeProgress)
                }

                val downloadedFile = outputDirectory.listFiles()
                    ?.filter { file ->
                        file.isFile &&
                            !file.name.endsWith(".part", ignoreCase = true) &&
                            file.lastModified() >= startedAt - 5_000L
                    }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw IllegalStateException("O yt-dlp terminou sem produzir um arquivo de mídia.")

                val publishedUri = runCatching { publishToDownloads(downloadedFile) }.getOrNull()
                if (publishedUri != null) downloadedFile.delete()

                workDataOf(
                    KEY_TITLE to title,
                    KEY_FILE_NAME to downloadedFile.name,
                    KEY_FILE_URI to (publishedUri?.toString() ?: downloadedFile.absolutePath),
                    KEY_PROGRESS to 100
                )
            }

            setProgress(workDataOf(KEY_PROGRESS to 100))
            updateNotification(title, 100, completed = true)
            Result.success(output)
        } catch (cancelled: CancellationException) {
            YoutubeDL.getInstance().destroyProcessById(id.toString())
            throw cancelled
        } catch (cancelled: InterruptedException) {
            YoutubeDL.getInstance().destroyProcessById(id.toString())
            Result.failure(workDataOf(KEY_TITLE to title, KEY_ERROR to "Download cancelado."))
        } catch (cancelled: YoutubeDL.CanceledException) {
            Result.failure(workDataOf(KEY_ERROR to "Download cancelado."))
        } catch (error: Exception) {
            val message = YtDlpEngine.readableError(
                error,
                "Não foi possível concluir o download."
            )
            Result.failure(workDataOf(KEY_TITLE to title, KEY_ERROR to message))
        }
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun publishToDownloads(source: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(source.extension.lowercase())
            ?: "application/octet-stream"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Gaplin Music"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val destination = applicationContext.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Não foi possível salvar o arquivo na pasta Downloads.")

        return try {
            applicationContext.contentResolver.openOutputStream(destination)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Não foi possível gravar o arquivo.")
            applicationContext.contentResolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            destination
        } catch (error: Exception) {
            applicationContext.contentResolver.delete(destination, null, null)
            throw error
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads do Gaplin Music",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso dos downloads iniciados no Gaplin Music"
            }
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(title: String, progress: Int, completed: Boolean = false): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (completed) "Download concluído" else "Baixando mídia")
            .setContentText(title)
            .setProgress(100, progress, false)
            .setOngoing(!completed)
            .setOnlyAlertOnce(true)
            .setAutoCancel(completed)
            .build()
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = notification(title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun updateNotification(title: String, progress: Int, completed: Boolean = false) {
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(notificationId, notification(title, progress, completed))
        }
    }
}
