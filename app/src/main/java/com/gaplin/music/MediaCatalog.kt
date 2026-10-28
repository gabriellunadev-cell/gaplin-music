package com.gaplin.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class AudioTrack(
    val title: String,
    val artist: String,
    val uri: Uri,
    val folderPath: String,
    val durationMs: Long
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
    fun scan(context: Context): MediaSnapshot {
        return MediaSnapshot(
            audio = scanAudio(context),
            videos = scanVideos(context)
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
            MediaStore.Audio.Media.DURATION
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
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val path = cursor.getColumnIndexOrThrow(pathColumn)
            while (cursor.moveToNext()) {
                val rawPath = cursor.getString(path).orEmpty()
                result += AudioTrack(
                    title = cursor.getString(title)?.takeIf(String::isNotBlank) ?: "Faixa sem título",
                    artist = cursor.getString(artist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artista desconhecido",
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(id)),
                    folderPath = normalizePath(rawPath),
                    durationMs = cursor.getLong(duration)
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

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimEnd('/').ifBlank { "Armazenamento interno" }
}
