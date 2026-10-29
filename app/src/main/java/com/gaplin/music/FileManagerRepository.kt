package com.gaplin.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

enum class ManagedMediaKind(val label: String) {
    AUDIO("Música"),
    VIDEO("Vídeo"),
    IMAGE("Foto")
}

enum class ManagerFilter(val label: String) {
    ALL("Todos"),
    AUDIO("Música"),
    VIDEO("Vídeo"),
    IMAGE("Fotos"),
    DUPLICATES("Duplicados")
}

data class ManagedMediaFile(
    val uri: Uri,
    val name: String,
    val folderPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val kind: ManagedMediaKind,
    val isSaf: Boolean
) {
    val stableId: String get() = uri.toString()
}

data class DuplicateMediaGroup(
    val kind: ManagedMediaKind,
    val sha256: String,
    val files: List<ManagedMediaFile>
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

data class MoveCopyResult(
    val copied: List<ManagedMediaFile>,
    val failed: List<ManagedMediaFile>
)

object FileManagerRepository {
    @Suppress("DEPRECATION")
    fun scan(
        context: Context,
        treeUri: Uri?,
        includeAudio: Boolean,
        includeVideo: Boolean,
        includeImages: Boolean
    ): List<ManagedMediaFile> {
        val result = mutableListOf<ManagedMediaFile>()
        if (includeAudio) {
            result += queryMediaStore(
                context,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ManagedMediaKind.AUDIO,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            )
        }
        if (includeVideo) {
            result += queryMediaStore(
                context,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ManagedMediaKind.VIDEO,
                null
            )
        }
        if (includeImages) {
            result += queryMediaStore(
                context,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ManagedMediaKind.IMAGE,
                null
            )
        }
        if (treeUri != null) result += scanSafTree(context, treeUri)
        return result.distinctBy { it.stableId }
            .distinctBy { file ->
                val normalizedFolder = if (file.isSaf) file.folderPath.removePrefix("SAF: ").trimEnd('/')
                    else file.folderPath.trimEnd('/')
                "${file.kind.name}|${normalizedFolder.lowercase()}|${file.name.lowercase()}"
            }
            .sortedWith(compareBy<ManagedMediaFile> { it.kind.ordinal }.thenBy { it.name.lowercase() })
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStore(
        context: Context,
        collection: Uri,
        kind: ManagedMediaKind,
        selection: String?
    ): List<ManagedMediaFile> {
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            pathColumn,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val files = mutableListOf<ManagedMediaFile>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val pathIndex = cursor.getColumnIndex(pathColumn)
            val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            if (idIndex < 0 || nameIndex < 0 || sizeIndex < 0) return files
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.takeIf(String::isNotBlank) ?: continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                val rawPath = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                val folder = rawPath.replace('\\', '/').trimEnd('/')
                    .ifBlank { "Armazenamento interno" }
                files += ManagedMediaFile(
                    uri = uri,
                    name = name,
                    folderPath = folder,
                    mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty()
                        .ifBlank { mimeFromName(name, kind) } else mimeFromName(name, kind),
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    modifiedSeconds = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L,
                    kind = kind,
                    isSaf = false
                )
            }
        }
        return files
    }

    private fun scanSafTree(context: Context, treeUri: Uri): List<ManagedMediaFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<ManagedMediaFile>()
        val mimeMap = MimeTypeMap.getSingleton()

        fun visit(directory: DocumentFile, relativePath: String, depth: Int) {
            if (depth > 16 || !directory.isDirectory) return
            val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
            children.forEach { child ->
                if (child.isDirectory) {
                    val childName = child.name?.takeIf(String::isNotBlank) ?: return@forEach
                    visit(child, listOf(relativePath, childName).filter(String::isNotBlank).joinToString("/"), depth + 1)
                } else if (child.isFile) {
                    val name = child.name ?: return@forEach
                    val extension = name.substringAfterLast('.', "").lowercase()
                    val kind = kindForExtension(extension) ?: return@forEach
                    val mime = child.type?.takeIf(String::isNotBlank)
                        ?: mimeMap.getMimeTypeFromExtension(extension)
                        ?: mimeFromName(name, kind)
                    result += ManagedMediaFile(
                        uri = child.uri,
                        name = name,
                        folderPath = "SAF: $relativePath",
                        mimeType = mime,
                        sizeBytes = runCatching { child.length() }.getOrDefault(0L).coerceAtLeast(0L),
                        modifiedSeconds = runCatching { child.lastModified() / 1000L }.getOrDefault(0L),
                        kind = kind,
                        isSaf = true
                    )
                }
            }
        }

        visit(root, root.name?.takeIf(String::isNotBlank) ?: "Pasta selecionada", 0)
        return result
    }

    fun findExactDuplicates(
        context: Context,
        files: List<ManagedMediaFile>
    ): List<DuplicateMediaGroup> {
        val candidateBuckets = files
            .filter { it.sizeBytes > 0L }
            .groupBy { it.kind to it.sizeBytes }
            .values
            .filter { it.size >= 2 }

        val duplicates = mutableListOf<DuplicateMediaGroup>()
        candidateBuckets.forEach { bucket ->
            val byHash = bucket.mapNotNull { file ->
                val digest = sha256(context, file.uri) ?: return@mapNotNull null
                digest to file
            }.groupBy({ it.first }, { it.second })

            byHash.forEach { (hash, matchingFiles) ->
                if (matchingFiles.size > 1) {
                    duplicates += DuplicateMediaGroup(
                        kind = matchingFiles.first().kind,
                        sha256 = hash,
                        files = matchingFiles.sortedBy { it.folderPath.lowercase() }
                    )
                }
            }
        }
        return duplicates.sortedWith(
            compareByDescending<DuplicateMediaGroup> { it.files.size }
                .thenByDescending { it.totalBytes }
        )
    }

    private fun sha256(context: Context, uri: Uri): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return null
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.getOrNull()
    }

    fun copyToTree(
        context: Context,
        file: ManagedMediaFile,
        destinationTreeUri: Uri
    ): Boolean {
        val root = DocumentFile.fromTreeUri(context, destinationTreeUri) ?: return false
        if (!root.canWrite()) return false

        val outputName = uniqueName(root, file.name)
        val destination = runCatching {
            root.createFile(file.mimeType.ifBlank { "application/octet-stream" }, outputName)
        }.getOrNull() ?: return false

        return try {
            val input = context.contentResolver.openInputStream(file.uri) ?: run {
                destination.delete()
                return false
            }
            val output = context.contentResolver.openOutputStream(destination.uri, "w") ?: run {
                input.close()
                destination.delete()
                return false
            }
            val copiedBytes = input.use { source ->
                output.use { sink -> source.copyTo(sink, 128 * 1024) }
            }
            val targetBytes = runCatching { destination.length() }.getOrDefault(copiedBytes)
            if (copiedBytes > 0L && targetBytes != copiedBytes) {
                destination.delete()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            runCatching { destination.delete() }
            false
        }
    }

    private fun uniqueName(destination: DocumentFile, originalName: String): String {
        if (destination.findFile(originalName) == null) return originalName
        val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: originalName.length
        val base = originalName.substring(0, dot)
        val extension = originalName.substring(dot)
        var suffix = 2
        while (destination.findFile("$base ($suffix)$extension") != null && suffix < 10_000) {
            suffix++
        }
        return "$base ($suffix)$extension"
    }

    fun deleteSafDocument(context: Context, uri: Uri): Boolean {
        val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        return document?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
    }

    private fun kindForExtension(extension: String): ManagedMediaKind? = when (extension) {
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma" -> ManagedMediaKind.AUDIO
        "mp4", "m4v", "mkv", "webm", "3gp", "mpeg", "mpg", "avi", "mov" -> ManagedMediaKind.VIDEO
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> ManagedMediaKind.IMAGE
        else -> null
    }

    private fun mimeFromName(name: String, kind: ManagedMediaKind): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (kind) {
                ManagedMediaKind.AUDIO -> "audio/*"
                ManagedMediaKind.VIDEO -> "video/*"
                ManagedMediaKind.IMAGE -> "image/*"
            }
    }
}
