package dev.family.podcasts

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

fun key(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
fun feedUrl(value: String): String {
    val uri = URI(value.trim()).normalize()
    require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Enter a full http or https feed link." }
    val authority = uri.rawAuthority.lowercase()
    return "${uri.scheme.lowercase()}://$authority${uri.rawPath.ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
}
data class Show(val url: String, val title: String, val author: String = "", val image: String = "", val description: String = "", val subscribed: Boolean = false) {
    val id: String get() = key(url)
    fun json() = JSONObject().put("url", url).put("title", title).put("author", author).put("image", image).put("description", description).put("subscribed", subscribed)
    companion object { fun parse(j: JSONObject) = Show(j.getString("url"), j.getString("title"), j.optString("author"), j.optString("image"), j.optString("description"), j.optBoolean("subscribed")) }
}
data class Chapter(val title: String, val startMs: Long)
data class Episode(val id: String, val showId: String, val title: String, val audio: String, val image: String = "", val description: String = "", val date: Long = 0, val duration: Long = 0, val number: Int? = null, val season: Int? = null, val type: String = "full", val chapters: List<Chapter> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("showId", showId).put("title", title).put("audio", audio).put("image", image).put("description", description).put("date", date).put("duration", duration).put("number", number).put("season", season).put("type", type).put("chapters", org.json.JSONArray(chapters.map { JSONObject().put("title", it.title).put("start", it.startMs) }))
    companion object {
        fun parse(j: JSONObject): Episode {
            val c = j.optJSONArray("chapters")
            return Episode(j.getString("id"), j.getString("showId"), j.getString("title"), j.getString("audio"), j.optString("image"), j.optString("description"), j.optLong("date"), j.optLong("duration"), if(j.has("number") && !j.isNull("number")) j.getInt("number") else null, if(j.has("season") && !j.isNull("season")) j.getInt("season") else null, j.optString("type", "full"), (0 until (c?.length() ?: 0)).map { Chapter(c!!.getJSONObject(it).getString("title"), c.getJSONObject(it).getLong("start")) })
        }
    }
}
data class Progress(val position: Long = 0, val played: Boolean = false, val updated: Long = 0)
data class Bookmark(val episodeId: String, val position: Long, val label: String)
data class Library(val shows: List<Show> = emptyList(), val episodes: List<Episode> = emptyList(), val progress: Map<String, Progress> = emptyMap(), val queue: List<String> = emptyList(), val bookmarks: List<Bookmark> = emptyList()) {
    fun show(e: Episode) = shows.find { it.id == e.showId }
    fun position(e: Episode) = progress[e.id]?.position ?: 0
}
/** Screen sort order never determines playback order. An explicit queue wins. */
fun nextEpisode(current: Episode, library: Library, autoNext: Boolean, stopAfter: Boolean = false): Episode? {
    if (stopAfter) return null
    library.queue.asSequence().mapNotNull { id -> library.episodes.find { it.id == id } }
        .firstOrNull { it.id != current.id }?.let { return it }
    if (!autoNext) return null
    return library.episodes.filter { candidate ->
        candidate.showId == current.showId && candidate.id != current.id && candidate.type == "full" && library.progress[candidate.id]?.played != true &&
            (candidate.date <= 0 || current.date <= 0 || candidate.date >= current.date) &&
            if (candidate.number != null && current.number != null) {
                (candidate.season ?: 1) > (current.season ?: 1) || ((candidate.season ?: 1) == (current.season ?: 1) && candidate.number > current.number)
            } else current.date > 0 && candidate.date > current.date
    }.sortedWith(if (current.number != null) compareBy<Episode> { if(it.number != null) 0 else 1 }.thenBy { it.season ?: 1 }.thenBy { it.number ?: Int.MAX_VALUE }.thenBy { it.date }
        else compareBy { it.date }).firstOrNull()
}
fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if(seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
