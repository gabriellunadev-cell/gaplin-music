package com.gaplin.music.download

/**
 * Platform adapters expose only media that the user is authorized to access and save.
 * Implementations must use official/permissioned interfaces and must not bypass DRM,
 * login walls, private content controls, or platform access restrictions.
 */
data class MediaSearchQuery(
    val text: String,
    val sourceId: String? = null
)

data class MediaSearchItem(
    val id: String,
    val sourceId: String,
    val title: String,
    val creator: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val pageUrl: String
)

data class ResolvedMedia(
    val item: MediaSearchItem,
    val formats: List<DownloadFormat>
)

interface MediaSourceAdapter {
    val sourceId: String
    val displayName: String

    suspend fun search(query: MediaSearchQuery): Result<List<MediaSearchItem>>

    /** Resolve formats only through an authorized source/API or user-owned media URL. */
    suspend fun resolve(item: MediaSearchItem): Result<ResolvedMedia>
}

class MediaSourceRegistry(adapters: List<MediaSourceAdapter>) {
    private val sources = adapters.associateBy { it.sourceId }

    fun all(): List<MediaSourceAdapter> = sources.values.toList()

    fun find(sourceId: String): MediaSourceAdapter? = sources[sourceId]

    fun findForUrl(url: String): MediaSourceAdapter? {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return null
        return sources.values.firstOrNull { adapter ->
            adapter.sourceId.lowercase() == host || host.endsWith(".${adapter.sourceId.lowercase()}")
        }
    }
}
