package dev.family.podcasts

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Feed(val show: Show, val episodes: List<Episode>)
object FeedParser {
    private fun parser(xml: String): XmlPullParser {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "This feed uses unsupported XML declarations." }
        return Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(StringReader(xml)) }
    }
    fun parse(xml: String, url: String): Feed {
        val canonical = feedUrl(url)
        val p = parser(xml)
        var title = ""; var author = ""; var description = ""; var image = ""
        var item: MutableMap<String, String>? = null
        var chapters = mutableListOf<Chapter>()
        val episodes = mutableListOf<Episode>()
        val texts = mutableListOf<StringBuilder>()
        var inImage = false
        fun absolute(value: String): String = runCatching { URI(canonical).resolve(value).toASCIIString() }.getOrDefault(value)
        while(p.eventType != XmlPullParser.END_DOCUMENT) {
            when(p.eventType) {
                XmlPullParser.START_TAG -> {
                    texts.add(StringBuilder())
                    val n = p.name.lowercase()
                    if(n == "item" || n == "entry") { item = mutableMapOf(); chapters = mutableListOf() }
                    if(n == "image" && item == null && p.getAttributeValue(null,"href") == null) inImage = true
                    if(n == "enclosure" || n == "link" && p.getAttributeValue(null,"rel") == "enclosure") {
                        val audio = p.getAttributeValue(null,"url") ?: p.getAttributeValue(null,"href")
                        val mime = p.getAttributeValue(null,"type").orEmpty()
                        if(audio != null && (mime.isBlank() || mime.startsWith("audio/") || mime == "application/octet-stream")) item?.set("audio", absolute(audio))
                    }
                    if(n == "image" || n == "thumbnail") {
                        val cover = p.getAttributeValue(null,"href") ?: p.getAttributeValue(null,"url")
                        if(cover != null) { if(item != null) item["image"] = absolute(cover) else image = absolute(cover) }
                    }
                    if(n == "chapter" && item != null) {
                        val start = p.getAttributeValue(null,"start")?.let(::duration) ?: 0
                        chapters.add(Chapter(p.getAttributeValue(null,"title") ?: "Chapter ${chapters.size + 1}", start))
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if(texts.isNotEmpty()) texts.last().append(p.text)
                XmlPullParser.END_TAG -> {
                    val text = if(texts.isNotEmpty()) texts.removeAt(texts.lastIndex).toString().trim() else ""
                    if(texts.isNotEmpty()) texts.last().append(text)
                    val n = p.name.lowercase()
                    val i = item
                    if(n == "item" || n == "entry") {
                        if(i != null && i["audio"]?.let { runCatching { feedUrl(it) }.isSuccess } == true) {
                            val audio = i.getValue("audio")
                            episodes.add(Episode(key(key(canonical) + ":" + (i["guid"]?.takeIf { it.isNotBlank() } ?: audio)), key(canonical), i["title"].orEmpty().ifBlank { "Untitled episode" }, audio,
                                i["image"].orEmpty(), i["description"].orEmpty(), date(i["date"].orEmpty()), duration(i["duration"].orEmpty()), i["episode"]?.toIntOrNull(), i["season"]?.toIntOrNull(), i["episodetype"]?.lowercase() ?: "full", chapters.sortedBy { it.startMs }))
                        }
                        item = null
                    } else if(i != null) {
                        when(n) {
                            "title", "duration", "episode", "season", "episodetype", "guid" -> i[n] = text
                            "id" -> if(!i.containsKey("guid")) i["guid"] = text
                            "pubdate", "published", "updated" -> if(!i.containsKey("date")) i["date"] = text
                            "description", "summary", "encoded", "content" -> if(text.length > i["description"].orEmpty().length) i["description"] = text
                        }
                    } else when(n) {
                        "title" -> if(!inImage && title.isBlank()) title = text
                        "author", "managingeditor" -> if(author.isBlank()) author = text
                        "description", "subtitle", "summary" -> if(description.isBlank()) description = text
                        "url" -> if(inImage && image.isBlank()) image = absolute(text)
                        "image" -> inImage = false
                    }
                }
            }
            p.next()
        }
        require(title.isNotBlank()) { "This link does not contain a podcast feed." }
        return Feed(Show(canonical, title, author, image, description), episodes.distinctBy { it.id }.sortedByDescending { it.date })
    }
    fun duration(value: String): Long = runCatching { (value.trim().split(':').fold(0.0) { total, part -> total * 60 + part.toDouble() } * 1000).toLong().coerceAtLeast(0) }.getOrDefault(0)
    fun date(value: String): Long {
        for(format in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
            runCatching { SimpleDateFormat(format, Locale.US).parse(value.trim())?.time }.getOrNull()?.let { return it }
        }
        return 0
    }
    fun opml(xml: String): List<Show> {
        val p = parser(xml); val shows = mutableListOf<Show>()
        while(p.eventType != XmlPullParser.END_DOCUMENT) {
            if(p.eventType == XmlPullParser.START_TAG && p.name.equals("outline", true)) {
                val url = p.getAttributeValue(null,"xmlUrl") ?: p.getAttributeValue(null,"xmlurl")
                if(url != null) runCatching { shows.add(Show(feedUrl(url), p.getAttributeValue(null,"title") ?: p.getAttributeValue(null,"text") ?: "Podcast", subscribed = true)) }
            }
            p.next()
        }
        return shows.distinctBy { it.id }
    }
    fun export(shows: List<Show>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><opml version=\"2.0\"><head><title>Podcasts subscriptions</title></head><body>" + shows.filter { it.subscribed }.joinToString("") { "<outline type=\"rss\" text=\"${esc(it.title)}\" xmlUrl=\"${esc(it.url)}\"/>" } + "</body></opml>"
    }
}
class PodcastClient {
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    fun get(url: String, maxBytes: Long = 12L * 1024 * 1024): String {
        val request = Request.Builder().url(feedUrl(url)).header("User-Agent", "Podcasts/0.1 (Android podcast player)").build()
        return http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "The server returned ${r.code}. Please try again later." }
            val body = r.body ?: error("The server sent an empty response.")
            require(body.contentLength() <= maxBytes) { "This feed is too large to load." }
            body.source().use { source ->
                require(!source.request(maxBytes + 1)) { "This feed is too large to load." }
                source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            }
        }
    }
    fun feed(url: String) = FeedParser.parse(get(url), url)
    fun search(query: String): List<Show> {
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&country=GB&limit=35&term=${URLEncoder.encode(query.trim(), "UTF-8") }"
        val rows = JSONObject(get(url)).getJSONArray("results")
        return (0 until rows.length()).mapNotNull { index ->
            val j = rows.getJSONObject(index)
            runCatching { Show(feedUrl(j.getString("feedUrl")), j.optString("collectionName", j.optString("trackName")), j.optString("artistName"), j.optString("artworkUrl600", j.optString("artworkUrl100"))) }.getOrNull()
        }.distinctBy { it.id }
    }
}
