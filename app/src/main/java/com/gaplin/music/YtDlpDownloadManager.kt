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

    @Volatile
    private var quickJsVersion: String = "não iniciado"

    fun engineVersion(): String = runCatching {
        "yt-dlp ${YtDlp.getVersion()} · QuickJS-NG $quickJsVersion"
    }.getOrDefault("desconhecida")

    suspend fun initialize() = withContext(Dispatchers.IO) {
        YtDlp.init(appContext)
        val executable = installQuickJsRuntime()
        val probe = ProcessBuilder(executable.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val exitCode = probe.waitFor()
        val output = probe.inputStream.bufferedReader().use { it.readText() }.trim()
        if (exitCode != 0 || output.isBlank()) {
            throw IllegalStateException("O runtime QuickJS não conseguiu iniciar neste aparelho: ${output.take(220)}")
        }
        quickJsVersion = output.lineSequence().first().take(60)
        verifyQuickJsScriptExecution(executable)
        val jsTempDirectory = File(appContext.cacheDir, "yt-dlp-js").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Não foi possível preparar os arquivos temporários do QuickJS.")
            }
        }
        YtDlp.configureJsRuntime(executable.absolutePath, jsTempDirectory.absolutePath)
    }

    private fun verifyQuickJsScriptExecution(executable: File) {
        val script = File(appContext.cacheDir, "quickjs-self-test.js")
        try {
            script.writeText("console.log('GAPLIN_QJS_OK');", Charsets.UTF_8)
            val process = ProcessBuilder(executable.absolutePath, "--script", script.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (exitCode != 0 || "GAPLIN_QJS_OK" !in output) {
                throw IllegalStateException(
                    "O QuickJS foi iniciado, mas falhou ao executar JavaScript no Android: ${output.take(220)}"
                )
            }
        } finally {
            script.delete()
        }
    }

    private fun installQuickJsRuntime(): File {
        val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "x86_64"
        } ?: throw IllegalStateException(
            "O downloader exige uma arquitetura Android de 64 bits compatível (ARM64 ou x86_64)."
        )

        // Android 10+ blocks execve from app-writable filesDir/cacheDir (W^X).
        // jniLibs are extracted by PackageManager into this read-only executable directory.
        val executable = File(appContext.applicationInfo.nativeLibraryDir, "libqjs.so")
        if (!executable.isFile || executable.length() <= 0L || !executable.canExecute()) {
            throw IllegalStateException(
                "O APK não contém o runtime QuickJS executável para $supportedAbi em nativeLibraryDir. Recompile incluindo jniLibs."
            )
        }
        return executable
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
            // Query every file in the app's Downloads folder. Audio WebM is not
            // accepted by some Android Audio collections, but is valid in Downloads.
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val selectionArgs = arrayOf(relativePath)

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
                    val providerMime = cursor.getString(mimeIndex)
                    // MediaStore stores uncommon WebM audio as octet-stream;
                    // recover the real playback MIME from the .weba extension.
                    val mime = DownloadMimePolicy.resolvedMime(
                        providerMime = providerMime,
                        fileName = name,
                        inferredMime = mimeFromName(name)
                    )
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

    private val youtubeCookiesFile: File by lazy {
        File(appContext.noBackupFilesDir, "yt-dlp-youtube-cookies.txt")
    }

    fun hasYoutubeCookies(): Boolean =
        youtubeCookiesFile.isFile && youtubeCookiesFile.length() > 0L

    suspend fun importYoutubeCookies(uri: Uri): Int = withContext(Dispatchers.IO) {
        val raw = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 2 * 1024 * 1024) { "O arquivo de cookies excede 2 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado.")

        val lines = raw.lineSequence().map { it.trimEnd('\r') }.toList()
        require(lines.any { it.trim().equals("# Netscape HTTP Cookie File", true) }) {
            "Arquivo inválido. Importe cookies no formato Netscape (.txt)."
        }

        val youtubeRows = lines.filter { line ->
            if (line.isBlank() || line.startsWith("#") && !line.startsWith("#HttpOnly_", true)) {
                false
            } else {
                val domain = line.split('\t').firstOrNull().orEmpty()
                    .removePrefix("#HttpOnly_")
                    .trimStart('.')
                    .lowercase(Locale.ROOT)
                domain == "youtube.com" || domain.endsWith(".youtube.com")
            }
        }
        require(youtubeRows.isNotEmpty()) {
            "O arquivo não contém cookies do YouTube. Exporte os cookies de youtube.com."
        }
        require(youtubeRows.all { it.split('\t').size >= 7 }) {
            "O arquivo de cookies está incompleto ou não está no formato Netscape."
        }

        // Keep only YouTube-domain cookies; the export may contain sessions
        // for unrelated sites. Never log or back up the session file.
        val safeCookieFile = (listOf("# Netscape HTTP Cookie File") + youtubeRows)
            .joinToString(separator = "\n", postfix = "\n")
        val temporary = File(appContext.noBackupFilesDir, "yt-dlp-youtube-cookies.tmp")
        temporary.writeText(safeCookieFile, Charsets.UTF_8)
        if (youtubeCookiesFile.exists() && !youtubeCookiesFile.delete()) {
            temporary.delete()
            throw IllegalStateException("Não foi possível substituir a sessão salva.")
        }
        if (!temporary.renameTo(youtubeCookiesFile)) {
            temporary.delete()
            throw IllegalStateException("Não foi possível salvar a sessão no armazenamento privado.")
        }
        youtubeRows.size
    }

    suspend fun clearYoutubeCookies() = withContext(Dispatchers.IO) {
        if (youtubeCookiesFile.exists() && !youtubeCookiesFile.delete()) {
            throw IllegalStateException("Não foi possível remover a sessão salva.")
        }
    }

    suspend fun download(
        item: RemoteMediaResult,
        audioOnly: Boolean,
        onStage: (String) -> Unit,
        onProgress: (Float, Long) -> Unit
    ): List<DownloadedMedia> = withContext(Dispatchers.IO) {
        require(item.kind != RemoteMediaKind.CHANNEL) {
            "Escolha um vídeo ou uma playlist para baixar."
        }
        require(item.url.startsWith("https://")) {
            "Por segurança, somente links HTTPS são aceitos."
        }

        initialize()
        onStage("Validando disponibilidade")
        validateMedia(item, audioOnly)
        onStage("Iniciando transferência")

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
            val format = DownloadFormatPolicy.selector(audioOnly)

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

            if (isYouTubeUrl(item.url) && hasYoutubeCookies()) {
                request.addOption("--cookies", youtubeCookiesFile.absolutePath)
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
                .map { file ->
                    // Give audio-only WebM the audio-specific .weba extension.
                    // This keeps it identifiable as audio without transcoding.
                    if (audioOnly && file.extension.equals("webm", true)) {
                        val renamed = File(file.parentFile, file.nameWithoutExtension + ".weba")
                        if (!file.renameTo(renamed)) {
                            throw IllegalStateException("Não foi possível preparar o arquivo de áudio WebM.")
                        }
                        renamed
                    } else file
                }

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
            audioOnly && extension == "weba" -> "audio/webm"
            audioOnly && extension == "mp4" -> "audio/mp4"
            else -> mimeFromName(source.name).let {
                if (it == "application/octet-stream" && audioOnly) "audio/*" else it
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Store in Downloads rather than Audio/Video collections so uncommon
            // but valid containers (notably audio/webm) are accepted by Android.
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                // Android MediaStore rejects audio/webm on some devices.
                // Store the file generically, and retain its real MIME in DownloadedMedia.
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    DownloadMimePolicy.storageMime(mime, audioOnly, source.name)
                )
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

        val combined = (details + " " + causeMessage.orEmpty())
        return DownloadFailurePolicy.userMessage(
            raw = combined,
            hasOwnSession = hasYoutubeCookies(),
            isYouTube = isYoutubeError(combined.lowercase(Locale.ROOT))
        )
    }

    private fun validateMedia(item: RemoteMediaResult, audioOnly: Boolean) {
        val flags = mutableListOf("--retries", "2", "--extractor-retries", "2")
        if (isYouTubeUrl(item.url) && hasYoutubeCookies()) {
            flags.addAll(listOf("--cookies", youtubeCookiesFile.absolutePath))
        }

        val summary = try {
            org.json.JSONObject(YtDlp.inspectMedia(item.url, flags.toTypedArray()))
        } catch (error: Exception) {
            val raw = error.cause?.message ?: error.message ?: "Falha ao consultar a fonte."
            throw IllegalStateException(buildDownloadError(raw, listOf("ERROR: " + sanitizeDiagnostic(raw))), error)
        }

        if (summary.optBoolean("is_playlist")) {
            if (summary.optInt("entry_count") <= 0) {
                throw IllegalStateException("A playlist está vazia ou não possui itens acessíveis.")
            }
            return
        }

        val audioFormats = summary.optInt("audio_formats")
        val progressiveFormats = summary.optInt("progressive_formats")
        if (audioOnly && audioFormats <= 0) {
            throw IllegalStateException("Este vídeo não oferece uma faixa de áudio acessível nesta sessão.")
        }
        if (!audioOnly && progressiveFormats <= 0) {
            throw IllegalStateException(
                "A fonte não oferece vídeo e áudio no mesmo arquivo. Esta versão não inclui FFmpeg para mesclar faixas separadas."
            )
        }
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val host = Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT)
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    private fun isYoutubeError(message: String): Boolean =
        "youtube" in message || "googlevideo.com" in message

    private fun sanitizeDiagnostic(message: String): String {
        return message
            .replace(Regex("(?i)(authorization|cookie|token)=\\S+")) { match -> match.groupValues[1] + "=[oculto]" }
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
            "weba" -> "audio/webm"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    private fun isAudioExtension(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "weba")
    }
}
