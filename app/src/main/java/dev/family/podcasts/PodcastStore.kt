package dev.family.podcasts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class PodcastStore(context: Context) : SQLiteOpenHelper(context, "podcasts.db", null, 1) {
    private val mutable = MutableStateFlow(Library())
    val state = mutable.asStateFlow()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE shows (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE episodes (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY, position INTEGER NOT NULL, played INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE queue (id TEXT PRIMARY KEY, rank INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE bookmarks (episode TEXT NOT NULL, position INTEGER NOT NULL, label TEXT NOT NULL, PRIMARY KEY(episode, position))")
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY, download INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun load() {
        val db = readableDatabase
        val shows = mutableListOf<Show>(); val episodes = mutableListOf<Episode>(); val progress = mutableMapOf<String, Progress>(); val queue = mutableListOf<String>(); val bookmarks = mutableListOf<Bookmark>()
        db.rawQuery("SELECT json FROM shows", null).use { while(it.moveToNext()) shows.add(Show.parse(JSONObject(it.getString(0)))) }
        db.rawQuery("SELECT json FROM episodes", null).use { while(it.moveToNext()) episodes.add(Episode.parse(JSONObject(it.getString(0)))) }
        db.rawQuery("SELECT id,position,played,updated FROM progress", null).use { while(it.moveToNext()) progress[it.getString(0)] = Progress(it.getLong(1), it.getInt(2) == 1, it.getLong(3)) }
        db.rawQuery("SELECT id FROM queue ORDER BY rank", null).use { while(it.moveToNext()) queue.add(it.getString(0)) }
        db.rawQuery("SELECT episode,position,label FROM bookmarks ORDER BY rowid DESC", null).use { while(it.moveToNext()) bookmarks.add(Bookmark(it.getString(0), it.getLong(1), it.getString(2))) }
        mutable.value = Library(shows, episodes, progress, queue, bookmarks)
    }
    private fun writeJson(table: String, id: String, json: JSONObject) {
        writableDatabase.insertWithOnConflict(table, null, ContentValues().apply { put("id", id); put("json", json.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun cache(feed: Feed) {
        val old = mutable.value
        val show = feed.show.copy(subscribed = old.shows.find { it.id == feed.show.id }?.subscribed ?: feed.show.subscribed)
        val db = writableDatabase; db.beginTransaction()
        try { writeJson("shows", show.id, show.json()); feed.episodes.forEach { writeJson("episodes", it.id, it.json()) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        val merged = old.episodes.associateBy { it.id }.toMutableMap().apply { feed.episodes.forEach { put(it.id, it) } }.values.toList()
        mutable.value = old.copy(shows = old.shows.filterNot { it.id == show.id } + show, episodes = merged)
    }
    @Synchronized fun subscribe(show: Show, enabled: Boolean) {
        val old = mutable.value
        val updated = (old.shows.find { it.id == show.id } ?: show).copy(subscribed = enabled)
        writeJson("shows", updated.id, updated.json())
        mutable.value = old.copy(shows = old.shows.filterNot { it.id == updated.id } + updated)
    }
    @Synchronized fun save(id: String, position: Long, played: Boolean? = null) {
        val old = mutable.value
        val p = Progress(position.coerceAtLeast(0), played ?: old.progress[id]?.played ?: false, System.currentTimeMillis())
        writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply { put("id", id); put("position", p.position); put("played", if(p.played) 1 else 0); put("updated", p.updated) }, SQLiteDatabase.CONFLICT_REPLACE)
        mutable.value = old.copy(progress = old.progress + (id to p))
    }
    @Synchronized fun queue(ids: List<String>) {
        val clean = ids.distinct(); val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("queue", null, null)
            clean.forEachIndexed { rank, id -> db.insertOrThrow("queue", null, ContentValues().apply { put("id", id); put("rank", rank) }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        mutable.value = mutable.value.copy(queue = clean)
    }
    @Synchronized fun enqueue(id: String, first: Boolean = false) = queue(if(first) listOf(id) + mutable.value.queue.filterNot { it == id } else mutable.value.queue + id)
    @Synchronized fun dequeue(id: String) = queue(mutable.value.queue.filterNot { it == id })
    @Synchronized fun bookmark(id: String, position: Long, label: String) {
        val bookmark = Bookmark(id, position, label)
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply { put("episode", id); put("position", position); put("label", label) }, SQLiteDatabase.CONFLICT_REPLACE)
        mutable.value = mutable.value.copy(bookmarks = listOf(bookmark) + mutable.value.bookmarks.filterNot { it.episodeId == id && it.position == position })
    }
    @Synchronized fun removeBookmark(bookmark: Bookmark) {
        writableDatabase.delete("bookmarks", "episode=? AND position=?", arrayOf(bookmark.episodeId, bookmark.position.toString()))
        mutable.value = mutable.value.copy(bookmarks = mutable.value.bookmarks - bookmark)
    }
    @Synchronized fun downloads(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT id,download FROM downloads", null).use { while(it.moveToNext()) result[it.getString(0)] = it.getLong(1) }
        return result
    }
    @Synchronized fun setDownload(id: String, download: Long?) {
        if(download == null) writableDatabase.delete("downloads", "id=?", arrayOf(id))
        else writableDatabase.insertWithOnConflict("downloads", null, ContentValues().apply { put("id", id); put("download", download) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
