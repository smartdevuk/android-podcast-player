package dev.family.podcasts

import org.junit.Assert.*
import org.junit.Test

class PlaybackOrderTest {
    private fun e(n: Int, season: Int? = null, numbered: Boolean = true, show: String = "show") = Episode("$show-$season-$n", show, "Episode $n", "https://audio.example/$n.mp3", date = n * 1000L, number = if(numbered) n else null, season = season)
    @Test fun `newest first display still continues 3 to 4`() {
        val episodes = (5 downTo 1).map { e(it) }
        assertEquals(e(4), nextEpisode(e(3), Library(episodes = episodes), true))
    }
    @Test fun `already played newer episodes are skipped`() {
        assertEquals(e(5), nextEpisode(e(3), Library(episodes = (1..5).map { e(it) }, progress = mapOf(e(4).id to Progress(played = true))), true))
    }
    @Test fun `a higher episode number cannot send playback to an older publication`() {
        val current = e(3).copy(date = 10000)
        assertEquals(e(5).copy(date = 12000), nextEpisode(current, Library(episodes = listOf(e(4).copy(date = 5000), e(5).copy(date = 12000))), true))
    }
    @Test fun `newest episode stops instead of going backwards or switching show`() {
        assertNull(nextEpisode(e(3), Library(episodes = listOf(e(2), e(3), e(4, show = "other"))), true))
    }
    @Test fun `explicit queue takes precedence even when automatic continuation is off`() {
        val queued = e(1, show = "other")
        assertEquals(queued, nextEpisode(e(3), Library(episodes = listOf(e(4), queued), queue = listOf(queued.id)), false))
    }
    @Test fun `explicitly queued replay is respected`() {
        val queued = e(1, show = "other")
        assertEquals(queued, nextEpisode(e(3), Library(episodes = listOf(queued), progress = mapOf(queued.id to Progress(played = true)), queue = listOf(queued.id)), true))
    }
    @Test fun `stop after current episode overrides queue and autoplay`() {
        assertNull(nextEpisode(e(3), Library(episodes = listOf(e(4)), queue = listOf(e(4).id)), true, true))
    }
    @Test fun `missing episode numbers use dates not input order`() {
        val episodes = listOf(5, 2, 4, 1, 3).map { e(it, numbered = false) }
        assertEquals(e(4, numbered = false), nextEpisode(e(3, numbered = false), Library(episodes = episodes), true))
    }
    @Test fun `numbered seasons advance to the start of the next season`() {
        val current = e(10, 1)
        val next = e(1, 2).copy(date = 20000)
        assertEquals(next, nextEpisode(current, Library(episodes = listOf(e(9, 1), e(2, 2), next)), true))
    }
    @Test fun `trailers and bonus episodes are not inserted by automatic continuation`() {
        assertEquals(e(5), nextEpisode(e(3), Library(episodes = listOf(e(4).copy(type = "trailer"), e(5))), true))
    }
    @Test fun `missing ordering metadata stops rather than guessing`() {
        assertNull(nextEpisode(e(3, numbered = false).copy(date = 0), Library(episodes = listOf(e(4, numbered = false))), true))
    }
    @Test fun `tokenised feed links retain encoded path and query semantics`() {
        assertEquals("https://example.org/private/a%2Fb?key=x%26y&v=1", feedUrl("HTTPS://EXAMPLE.ORG/private/a%2Fb?key=x%26y&v=1#fragment"))
    }
    @Test fun `non web feed links are rejected`() {
        for(url in listOf("file:///sdcard/a", "javascript:alert(1)", "https://user:secret@example.org/rss")) assertTrue(runCatching { feedUrl(url) }.isFailure)
    }
    @Test fun `episode serialisation preserves markers and unknown duration`() {
        val episode = e(1).copy(chapters = listOf(Chapter("Opening", 0), Chapter("Second", 12000)))
        assertEquals(episode, Episode.parse(episode.json()))
    }
}
