package dev.family.podcasts

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Playback(val episode: Episode? = null, val position: Long = 0, val duration: Long = 0, val playing: Boolean = false, val buffering: Boolean = false, val speed: Float = 1f, val timerMinutes: Int = 0, val stopAfter: Boolean = false, val undo: Long? = null, val error: String? = null)

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var graph: AppGraph
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val storageDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var episode: Episode? = null
    private var undo: Long? = null
    private var deadline = 0L
    private var endTimer = false
    private var stopAfter = false
    private var error: String? = null
    private var internalSeek = false
    private var switching = false
    private val commands = listOf("OPEN", "SEEK", "UNDO", "SPEED", "TIMER", "STOP_AFTER")

    override fun onCreate() {
        super.onCreate()
        graph = (application as PodcastApp).graph
        val http = DefaultHttpDataSource.Factory().setUserAgent("Podcasts/0.1").setConnectTimeoutMs(15000).setReadTimeoutMs(30000).setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(15000).setSeekForwardIncrementMs(30000).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                setHandleAudioBecomingNoisy(true); setWakeMode(C.WAKE_MODE_LOCAL)
                setPlaybackSpeed(graph.preferences.getFloat("speed", 1f).coerceIn(.5f, 3f))
                addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { publish() }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { checkpoint() }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if(reason == Player.DISCONTINUITY_REASON_SEEK && !internalSeek) undo = oldPosition.positionMs
                        checkpoint()
                    }
                    override fun onPlayerError(exception: PlaybackException) { error = "Playback stopped. Your place is saved. Check your connection and tap Play to retry."; checkpoint(); publish() }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if(playbackState == Player.STATE_READY) error = null
                        if(playbackState == Player.STATE_ENDED) scope.launch { lock.withLock { finishEpisode() } }
                    }
                })
            }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(intent).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                result.setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build())
                if(controller.uid == Process.myUid()) {
                    val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    commands.forEach { available.add(SessionCommand(it, Bundle.EMPTY)) }
                    result.setAvailableSessionCommands(available.build())
                }
                return result.build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                val future = SettableFuture.create<SessionResult>()
                if(controller.uid != Process.myUid()) { future.set(SessionResult(SessionError.ERROR_PERMISSION_DENIED)); return future }
                scope.launch { lock.withLock {
                    try {
                        graph.loaded.await()
                        when(command.customAction) {
                            "OPEN" -> {
                                val requested = Episode.parse(JSONObject(args.getString("episode") ?: error("Missing episode")))
                                val known = graph.store.state.value.episodes.find { it.id == requested.id } ?: error("Episode is not in the library")
                                open(known, true)
                            }
                            "SEEK" -> seek(args.getLong("position"))
                            "UNDO" -> undo?.let { seek(it) }
                            "SPEED" -> { val speed = args.getFloat("speed", 1f).coerceIn(.5f, 3f); player.setPlaybackSpeed(speed); graph.preferences.edit().putFloat("speed", speed).apply() }
                            "TIMER" -> {
                                val minutes = args.getInt("minutes").coerceIn(-1, 180)
                                endTimer = minutes == -1
                                deadline = if(minutes > 0) SystemClock.elapsedRealtime() + minutes * 60000L else 0
                            }
                            "STOP_AFTER" -> stopAfter = !stopAfter
                            else -> { future.set(SessionResult(SessionError.ERROR_NOT_SUPPORTED)); return@withLock }
                        }
                        publish(); future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch(_: Exception) { future.set(SessionResult(SessionError.ERROR_BAD_VALUE)) }
                } }
                return future
            }
        }).build()
        scope.launch {
            graph.loaded.await()
            lock.withLock {
                if(episode == null) graph.preferences.getString("current", null)?.let { id -> graph.store.state.value.episodes.find { it.id == id } }?.let { open(it, false) }
            }
            var ticks = 0
            while(true) {
                delay(1000)
                if(deadline > 0 && SystemClock.elapsedRealtime() >= deadline) { player.pause(); deadline = 0; checkpoint() }
                publish()
                if(++ticks % 5 == 0 && player.isPlaying) checkpoint()
            }
        }
    }
    private suspend fun open(next: Episode, play: Boolean) {
        if(episode?.id == next.id && play && player.playbackState != Player.STATE_ENDED) {
            val position = player.currentPosition
            withContext(storageDispatcher) { graph.store.save(next.id, position, false); graph.store.dequeue(next.id) }
            if(player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play(); return
        }
        switching = true
        try {
        val previous = episode; val previousPosition = player.currentPosition
        withContext(storageDispatcher) { previous?.let { graph.store.save(it.id, previousPosition) } }
        val saved = graph.store.state.value.progress[next.id]
        val source = withContext(storageDispatcher) { graph.downloads.source(next) }
        // Stop the previous item before changing identity; listeners must never save
        // its last position against the newly selected episode.
        player.stop(); player.clearMediaItems()
        episode = next; undo = null; error = null
        val show = graph.store.state.value.show(next)
        val cover = next.image.ifBlank { show?.image.orEmpty() }
        val metadata = MediaMetadata.Builder().setTitle(next.title).setArtist(show?.title ?: "Podcast").setAlbumTitle(show?.title).apply { if(cover.isNotBlank()) setArtworkUri(Uri.parse(cover)) }.build()
        val position = if(saved?.played == true) 0 else saved?.position ?: 0
        player.setMediaItem(MediaItem.Builder().setMediaId(next.id).setUri(source).setMediaMetadata(metadata).build(), position)
        graph.preferences.edit().putString("current", next.id).apply()
        withContext(storageDispatcher) { if(play) { graph.store.save(next.id, position, false); graph.store.dequeue(next.id) } }
        player.prepare(); player.playWhenReady = play
        publish()
        } finally { switching = false }
    }
    private suspend fun finishEpisode() {
        val current = episode ?: return
        if(player.playbackState != Player.STATE_ENDED) return
        val position = player.duration.takeIf { it > 0 } ?: current.duration
        withContext(storageDispatcher) { graph.store.save(current.id, position, true) }
        val next = nextEpisode(current, graph.store.state.value, graph.preferences.getBoolean("autoNext", true), stopAfter || endTimer)
        if(next != null) open(next, true) else player.pause()
        stopAfter = false; endTimer = false
        publish()
    }
    private fun seek(position: Long) {
        undo = player.currentPosition
        internalSeek = true
        val duration = player.duration.takeIf { it > 0 } ?: episode?.duration ?: 0
        player.seekTo(position.coerceAtLeast(0).let { if(duration > 0) it.coerceAtMost(duration) else it })
        internalSeek = false; checkpoint(); publish()
    }
    private fun checkpoint() {
        if(switching) return
        val current = episode ?: return
        val position = player.currentPosition
        scope.launch(storageDispatcher) { graph.store.save(current.id, position) }
    }
    private fun publish() {
        graph.playback.value = Playback(episode, player.currentPosition, player.duration.takeIf { it > 0 } ?: episode?.duration ?: 0,
            player.isPlaying, player.playbackState == Player.STATE_BUFFERING, player.playbackParameters.speed,
            if(endTimer) -1 else if(deadline > 0) ((deadline - SystemClock.elapsedRealtime() + 59999) / 60000).toInt().coerceAtLeast(0) else 0, stopAfter, undo, error)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onDestroy() {
        val current = episode; val position = player.currentPosition
        scope.cancel()
        if(current != null) runBlocking(Dispatchers.IO) { graph.store.save(current.id, position) }
        session.release(); player.release()
        super.onDestroy()
    }
}
