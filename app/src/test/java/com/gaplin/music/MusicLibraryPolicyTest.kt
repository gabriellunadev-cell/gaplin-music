package com.gaplin.music

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLibraryPolicyTest {
    private fun track(title: String, added: Long) = AudioTrack(
        title = title,
        artist = "Teste",
        uri = Uri.parse("content://test/$title"),
        folderPath = "Downloads/Gaplin Music",
        durationMs = 0L,
        dateAddedEpochSeconds = added
    )

    @Test
    fun recentlyAddedSortsNewestFirst() {
        val tracks = listOf(track("Antiga", 100L), track("Nova", 300L), track("Meio", 200L))
        assertEquals(listOf("Nova", "Meio", "Antiga"), MusicLibraryPolicy.recentlyAdded(tracks).map { it.title })
    }

    @Test
    fun recentlyAddedKeepsTracksWithoutDateAtTheEnd() {
        val tracks = listOf(track("Sem data", 0L), track("Com data", 10L))
        assertEquals(listOf("Com data", "Sem data"), MusicLibraryPolicy.recentlyAdded(tracks).map { it.title })
    }

    @Test
    fun recentlyAddedHonorsLimit() {
        val tracks = (1L..4L).map { track("Faixa $it", it) }
        assertEquals(2, MusicLibraryPolicy.recentlyAdded(tracks, limit = 2).size)
    }
}
