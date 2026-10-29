package dev.family.podcasts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeedAndStoreTest {
    @Test fun `optional downloaded catalogue samples parse into playable episodes`() {
        val directory = java.io.File("../.tooling/catalogue")
        val report = java.io.File(directory, "coverage.json")
        org.junit.Assume.assumeTrue("Run scripts/check-catalogue.ps1 with explicit queries to enable the live feed smoke check", report.isFile)
        val coverage = org.json.JSONArray(report.readText().trimStart('\uFEFF'))
        assertTrue(coverage.length() > 0)
        for(index in 0 until coverage.length()) {
            val row = coverage.getJSONObject(index)
            val feed = FeedParser.parse(java.io.File(directory, row.getString("fixture")).readText(), row.getString("feed"))
            assertEquals(row.getString("query"), row.getInt("episodes"), feed.episodes.size)
            assertTrue(feed.show.title.isNotBlank()); assertTrue(feed.episodes.all { it.audio.startsWith("http") })
            assertTrue("Dates missing for ${row.getString("query")}", feed.episodes.any { it.date > 0 })
            assertTrue("Durations missing for ${row.getString("query")}", feed.episodes.any { it.duration > 0 })
        }
    }
    @Test fun `a fresh installation has no subscriptions history or seeded shows`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteDatabase("podcasts.db")
        val graph = AppGraph(app)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10000) { graph.loaded.await() } }
        assertTrue(graph.store.state.value.shows.isEmpty())
        assertTrue(graph.store.state.value.episodes.isEmpty())
        assertTrue(graph.store.state.value.progress.isEmpty())
        assertTrue(graph.store.state.value.queue.isEmpty())
        assertTrue(graph.store.state.value.bookmarks.isEmpty())
        graph.store.close()
    }
    private val xml = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:psc="http://podlove.org/simple-chapters"><channel>
        <title>Fixture podcast</title><description>A real feed shape</description><itunes:author>Publisher</itunes:author><itunes:image href="https://example.org/cover.jpg"/>
        <item><title>Episode three</title><guid>three</guid><enclosure url="https://example.org/three.mp3" type="audio/mpeg"/><pubDate>Tue, 20 Oct 2026 12:00:00 +0000</pubDate><itunes:duration>1:02:03</itunes:duration><itunes:episode>3</itunes:episode><itunes:season>1</itunes:season><description><![CDATA[<p>Notes &amp; links</p>]]></description><psc:chapters><psc:chapter start="00:00:00" title="Opening"/><psc:chapter start="00:01:30.500" title="Discussion"/></psc:chapters></item>
        <item><title>Non-audio item</title><enclosure url="https://example.org/video.mp4" type="video/mp4"/></item>
        </channel></rss>"""
    @Test fun `RSS parses namespaced metadata audio and chapters`() {
        val feed = FeedParser.parse(xml, "https://example.org/rss")
        assertEquals("Fixture podcast", feed.show.title); assertEquals("Publisher", feed.show.author)
        assertEquals(1, feed.episodes.size)
        val e = feed.episodes.single()
        assertEquals(3, e.number); assertEquals(1, e.season); assertEquals(3723000L, e.duration)
        assertEquals(90500L, e.chapters[1].startMs); assertTrue(e.description.contains("<p>")); assertTrue(e.date > 0)
    }
    @Test fun `Atom feeds and relative enclosures work`() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom show</title><entry><title>Episode</title><id>stable</id><published>2026-10-20T12:00:00Z</published><link rel="enclosure" href="/ep.mp3" type="audio/mpeg"/></entry></feed>"""
        val e = FeedParser.parse(atom, "https://example.org/feed").episodes.single()
        assertEquals("https://example.org/ep.mp3", e.audio); assertTrue(e.date > 0)
    }
    @Test fun `repeated GUIDs are deduplicated within a show but not across shows`() {
        val first = FeedParser.parse(xml, "https://example.org/one").episodes.single()
        val second = FeedParser.parse(xml, "https://example.org/two").episodes.single()
        assertNotEquals(first.id, second.id)
    }
    @Test fun `nested OPML round trip escapes feed tokens and titles`() {
        val show = Show("https://example.org/rss?key=1&two=2", "A & B \"show\"", subscribed = true)
        val exported = FeedParser.export(listOf(show, show.copy(url = "https://example.org/other", subscribed = false)))
        val imported = FeedParser.opml(exported)
        assertEquals(listOf(show), imported)
    }
    @Test fun `XML declarations that could resolve external entities are rejected`() {
        assertTrue(runCatching { FeedParser.parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///private'>]><rss/>", "https://example.org/rss") }.isFailure)
    }
    @Test fun `subscribed feeds progress bookmarks and queue survive reopening`() {
        val app = ApplicationProvider.getApplicationContext<Application>(); app.deleteDatabase("podcasts.db")
        val feed = FeedParser.parse(xml, "https://example.org/rss")
        PodcastStore(app).use { store ->
            store.load(); store.cache(feed); store.subscribe(feed.show, true); store.save(feed.episodes.single().id, 45678)
            store.enqueue(feed.episodes.single().id); store.enqueue(feed.episodes.single().id); store.bookmark(feed.episodes.single().id, 1234, "A moment")
            store.cache(feed)
            assertTrue(store.state.value.shows.single().subscribed)
        }
        PodcastStore(app).use { store ->
            store.load(); assertEquals(45678L, store.state.value.position(feed.episodes.single())); assertEquals(1, store.state.value.queue.size)
            assertEquals("A moment", store.state.value.bookmarks.single().label); assertTrue(store.state.value.shows.single().subscribed)
            store.save(feed.episodes.single().id, 40000, true); store.save(feed.episodes.single().id, 40000)
            assertTrue(store.state.value.progress.values.single().played)
        }
    }
}
