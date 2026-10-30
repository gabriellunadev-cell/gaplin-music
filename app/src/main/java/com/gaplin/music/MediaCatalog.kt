package com.gaplin.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import java.io.File
import androidx.documentfile.provider.DocumentFile
import android.os.Build
import android.provider.MediaStore

data class AudioTrack(
    val title: String,
    val artist: String,
    val uri: Uri,
    val folderPath: String,
    val durationMs: Long,
    val album: String = "Álbum desconhecido",
    val albumId: Long = -1L,
    val artworkUri: Uri? = null,
    val mimeType: String? = null,
    val dateAddedEpochSeconds: Long = 0L
)

data class VideoEntry(
    val title: String,
    val uri: Uri,
    val folderPath: String,
    val folderName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val season: Int?,
    val episode: Int?,
    val mimeType: String? = null
) {
    val seriesName: String
        get() = title
            .replace(Regex("(?i)[. _-]*(S\\d{1,2}E\\d{1,3}|\\d{1,2}x\\d{1,3}).*$"), "")
            .replace(Regex("(?i)[. _-]*(temporada|season)[. _-]*\\d+.*$"), "")
            .replace(Regex("[._]+"), " ")
            .trim()
            .ifBlank { folderName }
}

private data class EmbeddedAudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUri: Uri?
)

data class MediaSnapshot(
    val audio: List<AudioTrack>,
    val videos: List<VideoEntry>
) {
    val folders: List<String>
        get() = (audio.map { it.folderPath } + videos.map { it.folderPath })
            .filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

    val series: Map<String, List<VideoEntry>>
        get() = videos.groupBy { it.seriesName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
}

object MediaCatalog {
    @Suppress("DEPRECATION")
    fun scan(
        context: Context,
        includeAudio: Boolean = true,
        includeVideo: Boolean = true
    ): MediaSnapshot {
        return MediaSnapshot(
            audio = (
                (if (includeAudio) scanAudio(context) else emptyList()) +
                    scanGaplinDownloadsAudio(context)
            ).distinctBy { "${it.uri.authority}:${it.uri.lastPathSegment}" }
                .sortedWith(compareByDescending<AudioTrack> { it.dateAddedEpochSeconds }.thenBy { it.title.lowercase() }),
            videos = if (includeVideo) scanVideos(context) else emptyList()
        )
    }

    @Suppress("DEPRECATION")
    private fun scanAudio(context: Context): List<AudioTrack> {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        projection += pathColumn
        val result = mutableListOf<AudioTrack>()
        // MediaStore.Audio.Media já contém apenas arquivos de áudio.
        // Não filtrar por IS_MUSIC: alguns aparelhos deixam essa flag zerada
        // para músicas copiadas manualmente ou recebidas por outros aplicativos.
        resolver.query(
            collection,
            projection.toTypedArray(),
            null,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val path = cursor.getColumnIndexOrThrow(pathColumn)
            while (cursor.moveToNext()) {
                val rawPath = cursor.getString(path).orEmpty()
                result += AudioTrack(
                    title = cursor.getString(title)?.takeIf(String::isNotBlank) ?: "Faixa sem título",
                    artist = cursor.getString(artist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artista desconhecido",
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(id)),
                    folderPath = normalizePath(rawPath),
                    durationMs = cursor.getLong(duration),
                    album = cursor.getString(album)?.takeIf(String::isNotBlank) ?: "Álbum desconhecido",
                    albumId = cursor.getLong(albumId),
                    artworkUri = cursor.getLong(albumId).takeIf { it > 0L }?.let {
                        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it)
                    },
                    mimeType = cursor.getString(mimeType),
                    dateAddedEpochSeconds = cursor.getLong(dateAdded)
                )
            }
        }
        return result
    }

    /**
     * Downloads made by Gaplin are stored in Downloads/Gaplin Music.
     * Query MediaStore.Files instead of Audio.Media because Android may index
     * WebM audio as application/octet-stream and omit it from the audio collection.
     */
    private fun scanGaplinDownloadsAudio(context: Context): List<AudioTrack> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val relativePath = "${android.os.Environment.DIRECTORY_DOWNLOADS}/Gaplin Music/"
        val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "weba")
        val result = mutableListOf<AudioTrack>()

        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(relativePath),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val date = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val fileName = cursor.getString(name).orEmpty()
                val extension = fileName.substringAfterLast('.', "").lowercase()
                val providerMime = cursor.getString(mime).orEmpty()
                val isAudio = extension in audioExtensions || providerMime.startsWith("audio/")
                if (!isAudio) continue

                val resolvedMime = DownloadMimePolicy.resolvedMime(
                    providerMime = providerMime,
                    fileName = fileName,
                    inferredMime = when (extension) {
                        "mp3" -> "audio/mpeg"
                        "m4a", "mp4" -> "audio/mp4"
                        "aac" -> "audio/aac"
                        "flac" -> "audio/flac"
                        "wav" -> "audio/wav"
                        "ogg" -> "audio/ogg"
                        "opus" -> "audio/opus"
                        "weba", "webm" -> "audio/webm"
                        else -> "audio/*"
                    }
                )

                val mediaUri = ContentUris.withAppendedId(collection, cursor.getLong(id))
                val embedded = readEmbeddedAudioMetadata(
                    context = context,
                    uri = mediaUri,
                    cacheKey = "download-${cursor.getLong(id)}-${cursor.getLong(date)}"
                )
                result += AudioTrack(
                    title = embedded.title?.takeIf(String::isNotBlank)
                        ?: fileName.substringBeforeLast('.', fileName).ifBlank { "Faixa baixada" },
                    artist = embedded.artist?.takeIf(String::isNotBlank) ?: "Artista desconhecido",
                    uri = mediaUri,
                    folderPath = normalizePath(cursor.getString(path).orEmpty()),
                    durationMs = cursor.getLong(duration).coerceAtLeast(0L),
                    album = embedded.album?.takeIf(String::isNotBlank) ?: "Downloads",
                    artworkUri = embedded.artworkUri,
                    mimeType = resolvedMime,
                    dateAddedEpochSeconds = cursor.getLong(date)
                )
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun scanVideos(context: Context): List<VideoEntry> {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
        projection += pathColumn
        val result = mutableListOf<VideoEntry>()
        resolver.query(
            collection,
            projection.toTypedArray(),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val path = cursor.getColumnIndexOrThrow(pathColumn)
            while (cursor.moveToNext()) {
                val fileName = cursor.getString(name)?.ifBlank { "Vídeo sem título" } ?: "Vídeo sem título"
                val folderPath = normalizePath(cursor.getString(path).orEmpty())
                val folderName = folderPath.trimEnd('/').substringAfterLast('/').ifBlank { "Vídeos" }
                val episodeMatch = Regex("(?i)S(\\d{1,2})E(\\d{1,3})").find(fileName)
                    ?: Regex("(?i)(\\d{1,2})x(\\d{1,3})").find(fileName)
                    ?: Regex("(?i)(?:temporada|season)[ ._-]*(\\d+).*?(?:epis[oó]dio|episode|ep)[ ._-]*(\\d+)").find(fileName)
                val season = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = episodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                result += VideoEntry(
                    title = fileName.substringBeforeLast('.', fileName).replace(Regex("[._]+"), " ").trim(),
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(id)),
                    folderPath = folderPath,
                    folderName = folderName,
                    durationMs = cursor.getLong(duration),
                    sizeBytes = cursor.getLong(size),
                    season = season,
                    episode = episode,
                    mimeType = cursor.getString(mimeType)
                )
            }
        }
        return result
    }

    fun scanSelectedTree(context: Context, treeUri: Uri): MediaSnapshot {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return MediaSnapshot(emptyList(), emptyList())
        val audio = mutableListOf<AudioTrack>()
        val videos = mutableListOf<VideoEntry>()
        val audioExt = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "weba")
        val videoExt = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mpeg", "mpg")
        fun visit(directory: DocumentFile, depth: Int, relativePath: String) {
            if (depth > 12 || !directory.isDirectory) return
            val children = try {
                directory.listFiles()
            } catch (_: SecurityException) {
                return
            } catch (_: IllegalStateException) {
                return
            }
            children.forEach { file ->
                if (file.isDirectory) {
                    val childName = file.name?.takeIf(String::isNotBlank) ?: return@forEach
                    visit(file, depth + 1, listOf(relativePath, childName).filter(String::isNotBlank).joinToString("/"))
                } else if (file.isFile) {
                    val name = file.name ?: return@forEach
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val folder = "SAF: $relativePath"
                    val folderName = relativePath.substringAfterLast('/').ifBlank { root.name ?: "Pasta selecionada" }
                    when (ext) {
                        in audioExt -> audio += AudioTrack(
                            title = name.substringBeforeLast('.'),
                            artist = "Arquivo local",
                            uri = file.uri,
                            folderPath = folder,
                            durationMs = 0L,
                            dateAddedEpochSeconds = file.lastModified() / 1000L,
                            mimeType = file.type
                        )
                        in videoExt -> {
                            val match = Regex("(?i)S(\\d{1,2})E(\\d{1,3})").find(name)
                                ?: Regex("(?i)(\\d{1,2})x(\\d{1,3})").find(name)
                                ?: Regex("(?i)(?:temporada|season)[ ._-]*(\\d+).*?(?:epis[oó]dio|episode|ep)[ ._-]*(\\d+)").find(name)
                            videos += VideoEntry(
                                title = name.substringBeforeLast('.').replace(Regex("[._]+"), " ").trim(),
                                uri = file.uri,
                                folderPath = folder,
                                folderName = folderName,
                                durationMs = 0L,
                                sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                                season = match?.groupValues?.getOrNull(1)?.toIntOrNull(),
                                episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                            )
                        }
                    }
                }
            }
        }
        visit(root, 0, root.name?.takeIf(String::isNotBlank) ?: "Pasta selecionada")
        return MediaSnapshot(audio, videos)
    }

    private fun readEmbeddedAudioMetadata(
        context: Context,
        uri: Uri,
        cacheKey: String
    ): EmbeddedAudioMetadata {
        val directory = File(context.cacheDir, "gaplin-audio-artwork")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val picture = retriever.embeddedPicture
            val artworkUri = if (
                picture != null && picture.isNotEmpty() && picture.size <= 8 * 1024 * 1024 &&
                (directory.isDirectory || directory.mkdirs())
            ) {
                val cached = File(directory, "$cacheKey.img")
                if (!cached.isFile || cached.length() != picture.size.toLong()) {
                    cached.outputStream().use { it.write(picture) }
                }
                Uri.fromFile(cached)
            } else {
                null
            }
            EmbeddedAudioMetadata(title, artist, album, artworkUri)
        } catch (_: Exception) {
            EmbeddedAudioMetadata(null, null, null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimEnd('/').ifBlank { "Armazenamento interno" }
}
