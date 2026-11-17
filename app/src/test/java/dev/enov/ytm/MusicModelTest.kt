package dev.enov.ytm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicModelTest {
    @Test fun artworkUpgradesTrustedSizesOnly() {
        assertEquals("https://lh3.googleusercontent.com/art=w1200-h1200-l90-rj", highResolutionArt("https://lh3.googleusercontent.com/art=w120-h120-l90-rj"))
        assertEquals("https://example.com/art=w120-h120", highResolutionArt("https://example.com/art=w120-h120"))
        assertEquals("file:/data/cover.jpg", highResolutionArt("file:/data/cover.jpg"))
    }
    @Test fun typedCollectionsDoNotBecomePlayableSongs() {
        assertTrue(Song("abcdefghijk", "Track", "Artist", "").playable)
        assertTrue(Song("episode", "Episode", "Show", "", "episode").playable)
        listOf("album", "artist", "playlist", "show", "collection").forEach {
            assertFalse(Song("id", "Collection", "", "", it).playable)
        }
    }
    @Test fun artistAndLocalIdentityStayIndependentOfPresentation() {
        assertEquals("Daft Punk", Song("abcdefghijk", "Track", "Daft Punk • Album • 3:05", "").artist)
        assertEquals("Artist", Song("abcdefghijk", "Track", "Artist · Album", "").artist)
        assertTrue(Song("file:/data/user/0/dev.enov.ytm/files/music.m4a", "Track", "Artist", "", "download").local)
        assertFalse(Song("abcdefghijk", "Track", "Artist", "").local)
    }
}
