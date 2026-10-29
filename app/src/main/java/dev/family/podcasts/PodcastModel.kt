package dev.family.podcasts

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class PodcastApp : Application() {
    val graph by lazy { AppGraph(this) }
}
class AppGraph(context: Context) {
    val store = PodcastStore(context)
    val client = PodcastClient()
    val downloads = Downloads(context, store)
    val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    val playback = MutableStateFlow(Playback())
    val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            try {
                store.load()
                loaded.complete(Unit)
            } catch(e: Exception) { loaded.completeExceptionally(e) }
        }
    }
}
data class ScreenState(val loaded: Boolean = false, val busy: Boolean = false, val searching: Boolean = false, val results: List<Show> = emptyList(), val notice: String? = null, val downloads: Map<String, Download> = emptyMap(), val settingsVersion: Int = 0)
class PodcastModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as PodcastApp).graph
    val library = graph.store.state
    val playback = graph.playback.asStateFlow()
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null
    private var controller: MediaController? = null
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    init {
        future.addListener({ runCatching { controller = future.get() } }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            runCatching { graph.loaded.await() }.onFailure { notice("Couldn't open the saved library."); return@launch }
            mutable.value = mutable.value.copy(loaded = true)
            refresh(quiet = true)
            while(true) {
                val downloads = withContext(Dispatchers.IO) { graph.downloads.all() }
                mutable.value = mutable.value.copy(downloads = downloads)
                delay(2000)
            }
        }
    }
    fun notice(message: String?) { mutable.value = mutable.value.copy(notice = message) }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        try { withContext(Dispatchers.IO) { graph.loaded.await(); block() } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { notice(e.message ?: "Something went wrong. Please try again.") }
    }
    fun setting(name: String, value: Boolean) { graph.preferences.edit().putBoolean(name, value).apply(); mutable.value = mutable.value.copy(settingsVersion = mutable.value.settingsVersion + 1) }
    fun search(query: String) {
        searchJob?.cancel()
        if(query.isBlank()) { mutable.value = mutable.value.copy(results = emptyList(), searching = false); return }
        searchJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(searching = true)
            try {
                val result = withContext(Dispatchers.IO) { graph.client.search(query) }
                mutable.value = mutable.value.copy(results = result)
                if(result.isEmpty()) notice("No shows found. Try a different name or add a feed link.")
            } catch(e: CancellationException) { throw e } catch(_: Exception) { notice("Search is unavailable. Your library and feed links still work.") }
            finally { mutable.value = mutable.value.copy(searching = false) }
        }
    }
    fun open(show: Show) = work { graph.store.cache(graph.client.feed(show.url)) }
    fun subscribe(show: Show, enabled: Boolean) = work {
        graph.store.subscribe(show, enabled)
        if(enabled) { graph.store.cache(graph.client.feed(show.url)); notice("Subscribed to ${show.title}") }
    }
    fun addFeed(url: String) = work { val feed = graph.client.feed(feedUrl(url)); graph.store.cache(feed); graph.store.subscribe(feed.show, true); notice("Subscribed to ${feed.show.title}") }
    fun refresh(quiet: Boolean = false) {
        if(mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            var failed = 0
            try {
                val limit = Semaphore(3)
                withContext(Dispatchers.IO) {
                    graph.loaded.await()
                    library.value.shows.filter { it.subscribed }.map { show -> async { limit.withPermit {
                        runCatching { graph.store.cache(graph.client.feed(show.url)) }.onFailure { synchronized(limit) { failed++ } }
                    } } }.awaitAll()
                }
                if(failed > 0) notice("$failed feed(s) couldn't refresh. Saved episodes are still available.")
                else if(!quiet) notice("Your podcasts are up to date")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun importOpml(xml: String) = work {
        val shows = FeedParser.opml(xml)
        require(shows.isNotEmpty()) { "No podcast feeds were found in this file." }
        shows.forEach { graph.store.subscribe(it, true) }
        notice("Imported ${shows.size} subscriptions. Refreshing episodes…")
        withContext(Dispatchers.Main) { refresh() }
    }
    fun queue(episode: Episode, first: Boolean = false) = work { graph.store.enqueue(episode.id, first); notice(if(first) "Playing next: ${episode.title}" else "Added to queue") }
    fun removeQueue(id: String) = work { graph.store.dequeue(id) }
    fun moveQueue(id: String, direction: Int) = work {
        val ids = library.value.queue.toMutableList(); val from = ids.indexOf(id); val to = from + direction
        if(from >= 0 && to in ids.indices) { ids.add(to, ids.removeAt(from)); graph.store.queue(ids) }
    }
    fun played(episode: Episode, value: Boolean) {
        if(playback.value.episode?.id == episode.id) { controller?.pause(); if(!value) seek(0) }
        work { graph.store.save(episode.id, if(value) episode.duration else 0, value) }
    }
    fun download(episode: Episode) = work { graph.downloads.add(episode, graph.preferences.getBoolean("wifiOnly", true)); notice("Download requested") }
    fun removeDownload(id: String) = work { graph.downloads.remove(id) }
    fun command(action: String, extras: Bundle = Bundle.EMPTY) {
        val c = controller
        if(c == null) { notice("The player is connecting. Please try again in a moment."); return }
        c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), extras)
    }
    fun play(episode: Episode) = command("OPEN", Bundle().apply { putString("episode", episode.json().toString()) })
    fun toggle() { controller?.let { if(it.isPlaying) it.pause() else { if(it.playbackState == androidx.media3.common.Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun seek(position: Long) = command("SEEK", Bundle().apply { putLong("position", position) })
    fun speed(value: Float) = command("SPEED", Bundle().apply { putFloat("speed", value) })
    fun timer(minutes: Int) = command("TIMER", Bundle().apply { putInt("minutes", minutes) })
    fun bookmark() = work { val p = playback.value; p.episode?.let { graph.store.bookmark(it.id, p.position, "Bookmark at ${timeLabel(p.position)}"); notice("Bookmark saved") } }
    fun removeBookmark(bookmark: Bookmark) = work { graph.store.removeBookmark(bookmark) }
    override fun onCleared() { MediaController.releaseFuture(future); super.onCleared() }
}
