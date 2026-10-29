package dev.family.podcasts

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

data class Download(val status: Int, val bytes: Long, val total: Long, val uri: String? = null) {
    val ready get() = status == DownloadManager.STATUS_SUCCESSFUL && uri != null
    val label get() = when(status) {
        DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"
        DownloadManager.STATUS_FAILED -> "Download failed · Retry"
        DownloadManager.STATUS_PAUSED -> "Waiting for connection"
        DownloadManager.STATUS_PENDING -> "Waiting to download"
        else -> if(total > 0) "Downloading ${bytes * 100 / total}%" else "Downloading…"
    }
}
class Downloads(private val context: Context, private val store: PodcastStore) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    @Synchronized fun add(episode: Episode, wifiOnly: Boolean) {
        val existing = store.downloads()[episode.id]
        if(existing != null) {
            val current = status(existing)
            if(current != null && current.status != DownloadManager.STATUS_FAILED) return
            manager.remove(existing)
        }
        val request = DownloadManager.Request(Uri.parse(feedUrl(episode.audio))).setTitle(episode.title)
            .setDescription("Podcast episode").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(false).setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, "${episode.id}.audio")
        if(wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        store.setDownload(episode.id, manager.enqueue(request))
    }
    fun status(id: Long): Download? = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if(!c.moveToFirst()) return@use null
        val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val uri = if(state == DownloadManager.STATUS_SUCCESSFUL) manager.getUriForDownloadedFile(id)?.toString() else null
        Download(state, c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), uri)
    }
    fun all() = store.downloads().mapNotNull { (id, download) -> status(download)?.let { id to it } }.toMap()
    fun source(episode: Episode): String = store.downloads()[episode.id]?.let(::status)?.takeIf { it.ready }?.uri ?: episode.audio
    @Synchronized fun remove(id: String) { store.downloads()[id]?.let { manager.remove(it) }; store.setDownload(id, null) }
}
