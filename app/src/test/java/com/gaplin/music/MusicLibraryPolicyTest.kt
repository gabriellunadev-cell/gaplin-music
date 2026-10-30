package com.gaplin.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLibraryPolicyTest {
    private data class Track(val title: String, val added: Long)

    private fun recent(tracks: List<Track>, limit: Int = 250) =
        MusicLibraryPolicy.recentlyAdded(tracks, { it.added }, { it.title }, limit)

    @Test
    fun recentlyAddedSortsNewestFirst() {
        val tracks = listOf(Track("Antiga", 100L), Track("Nova", 300L), Track("Meio", 200L))
        assertEquals(listOf("Nova", "Meio", "Antiga"), recent(tracks).map { it.title })
    }

    @Test
    fun recentlyAddedKeepsTracksWithoutDateAtTheEnd() {
        val tracks = listOf(Track("Sem data", 0L), Track("Com data", 10L))
        assertEquals(listOf("Com data", "Sem data"), recent(tracks).map { it.title })
    }

    @Test
    fun recentlyAddedHonorsLimit() {
        val tracks = (1L..4L).map { Track("Faixa $it", it) }
        assertEquals(2, recent(tracks, limit = 2).size)
    }
}
