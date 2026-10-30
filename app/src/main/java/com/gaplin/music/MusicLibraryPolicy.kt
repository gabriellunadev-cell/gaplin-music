package com.gaplin.music

internal object MusicLibraryPolicy {
    fun recentlyAdded(tracks: List<AudioTrack>, limit: Int = 250): List<AudioTrack> =
        tracks.sortedWith(
            compareByDescending<AudioTrack> { it.dateAddedEpochSeconds }
                .thenBy { it.title.lowercase() }
        ).take(limit.coerceAtLeast(1))
}
