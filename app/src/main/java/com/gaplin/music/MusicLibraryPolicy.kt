package com.gaplin.music

internal object MusicLibraryPolicy {
    fun <T> recentlyAdded(
        tracks: List<T>,
        dateAdded: (T) -> Long,
        title: (T) -> String,
        limit: Int = 250
    ): List<T> = tracks.sortedWith(
        compareByDescending<T> { dateAdded(it) }
            .thenBy { title(it).lowercase() }
    ).take(limit.coerceAtLeast(1))
}
