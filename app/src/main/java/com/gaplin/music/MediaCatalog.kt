package com.gaplin.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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
    val addedAtSeconds: Long = 0L
)

data class VideoEntry(
    val title: String,
    val uri: Uri,
    val folderPath: String,
    val folderName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val season: Int?,
    val episode: Int?
) {
    val seriesName: String
        get() = title
            .replace(Regex("(?i)[. _-]*(S\\d{1,2}E\\d{1,3}|\\d{1,2}x\\d{1,3}).*$"), "")
            .replace(Regex("(?i)[. _-]*(temporada|season)[. _-]*\\d+.*$"), "")
            .replace(Regex("[._]+"), " ")
            .trim()
            .ifBlank { folderName }
}

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
            audio = if (includeAudio) scanAudio(context) else emptyList(),
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
            MediaStore.Audio.Media.DATE_ADDED
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        projection += pathColumn
        val result = mutableListOf<AudioTrack>()
        resolver.query(
            collection,
            projection.toTypedArray(),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
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
                    addedAtSeconds = cursor.getLong(dateAdded)
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
            MediaStore.Video.Media.SIZE
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
                    episode = episode
                )
            }
        }
        return result
    }

    fun scanSelectedTree(context: Context, treeUri: Uri): MediaSnapshot {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return MediaSnapshot(emptyList(), emptyList())
        val audio = mutableListOf<AudioTrack>()
        val videos = mutableListOf<VideoEntry>()
        val audioExt = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
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
                            addedAtSeconds = file.lastModified() / 1000L
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

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimEnd('/').ifBlank { "Armazenamento interno" }
}
