package dev.family.podcasts

import android.content.Intent
import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model get() = ViewModelProvider(this)[PodcastModel::class.java]
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        lifecycleScope.launch {
            try {
                val xml = withContext(Dispatchers.IO) { contentResolver.openInputStream(it)?.use { stream ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while(true) { val count = stream.read(buffer); if(count < 0) break; require(out.size() + count <= 2 * 1024 * 1024) { "This subscription file is too large." }; out.write(buffer, 0, count) }
                    out.toString("UTF-8")
                } ?: error("Couldn't open this file.") }
                model.importOpml(xml)
            } catch(_: Exception) { model.notice("Couldn't import this file. Choose an OPML subscriptions file.") }
        }
    } }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri -> uri?.let {
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(it, "wt")?.use { stream -> stream.write(FeedParser.export(model.library.value.shows).toByteArray()) } ?: error("Couldn't save file") }; model.notice("Subscriptions exported") }
            catch(_: Exception) { model.notice("Couldn't export subscriptions. Please choose another location.") }
        }
    } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent { val vm: PodcastModel = viewModel(); val state by vm.state.collectAsStateWithLifecycle()
            val dark = vm.graph.preferences.getBoolean("dark", true)
            SideEffect {
                val style = if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PodcastTheme(dark) { PodcastScreen(vm, state, { importFile.launch(arrayOf("text/*", "application/xml", "application/octet-stream")) }, { exportFile.launch("podcast-subscriptions.opml") }) }
        }
    }
}
@Composable internal fun PodcastTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val scheme = if(dark) darkColorScheme(primary = Color(0xFFFFB735), onPrimary = Color(0xFF18130F), primaryContainer = Color(0xFF51473E), onPrimaryContainer = Color(0xFFF7F2E8), background = Color(0xFF18130F), surface = Color(0xFF251D16), onBackground = Color(0xFFF7F2E8), onSurface = Color(0xFFF7F2E8), onSurfaceVariant = Color(0xFFB9B1A7), surfaceVariant = Color(0xFF3A3027), secondaryContainer = Color(0xFF51473E), onSecondaryContainer = Color(0xFFF7F2E8), secondary = Color(0xFFFFB735), outline = Color(0xFF857666))
        else lightColorScheme(primary = Color(0xFF8C4E00), onPrimary = Color.White, primaryContainer = Color(0xFFE5D8C6), background = Color(0xFFFBF5EA), surface = Color(0xFFF1E7D8), onBackground = Color(0xFF241C15), onSurface = Color(0xFF241C15), onSurfaceVariant = Color(0xFF65574C), surfaceVariant = Color(0xFFE5D8C6), secondaryContainer = Color(0xFFE5D8C6))
    MaterialTheme(colorScheme = scheme) { CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PodcastScreen(vm: PodcastModel, state: ScreenState, importOpml: () -> Unit, exportOpml: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedShow by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var feedDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val screens = rememberSaveableStateHolder()
    val selected = selectedShow?.let { Show.parse(JSONObject(it)) }?.let { selected -> library.shows.find { it.id == selected.id } ?: selected }
    val detail = library.episodes.find { it.id == detailId }
    fun openShow(show: Show) { selectedShow = show.json().toString(); vm.open(show) }
    fun play(episode: Episode) {
        if(playback.episode?.id == episode.id && playback.playing) { vm.toggle(); return }
        vm.play(episode); detailId = null; playerOpen = true
    }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); vm.notice(null) } }
    BackHandler(enabled = playerOpen || settings || selectedShow != null) { when { playerOpen -> playerOpen = false; settings -> settings = false; else -> selectedShow = null } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if(!playerOpen && !settings) Column {
            playback.episode?.let { MiniPlayer(playback, library, { playerOpen = true }, vm::toggle) }
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                listOf("Listen" to Icons.Outlined.Headphones, "Library" to Icons.Outlined.LibraryMusic, "Discover" to Icons.Outlined.Explore).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; selectedShow = null }, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .16f)))
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                playerOpen && playback.episode != null -> PlayerContent(playback, library, { playerOpen = false }, vm::toggle, vm::seek, vm::speed, vm::timer, vm::bookmark, { queueOpen = true }, { vm.command("UNDO") }, { vm.command("STOP_AFTER") }, vm::removeBookmark)
                settings -> SettingsContent(vm, { settings = false }, importOpml, exportOpml)
                selected != null -> screens.SaveableStateProvider("show-${selected.id}") { ShowContent(selected, library, state.downloads, playback, { selectedShow = null }, { vm.subscribe(selected, !selected.subscribed) }, { detailId = it.id }, ::play, { vm.open(selected) }) }
                tab == 0 -> screens.SaveableStateProvider("listen") { ListenContent(library, playback, state.downloads, state.loaded, { tab = 2 }, { queueOpen = true }, { settings = true }, { vm.refresh() }, { detailId = it.id }, ::play) }
                tab == 1 -> screens.SaveableStateProvider("library") { LibraryContent(library, state.downloads, playback, ::openShow, { detailId = it.id }, ::play, { feedDialog = true }, importOpml) }
                else -> screens.SaveableStateProvider("discover") { DiscoverContent(state, vm::search, ::openShow, { feedDialog = true }) }
            }
        }
    }
    if(detail != null) ModalBottomSheet(onDismissRequest = { detailId = null }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(library.show(detail)?.title.orEmpty(), color = MaterialTheme.colorScheme.primary)
            Text(detail.title, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            Text(episodeMeta(detail, library.progress[detail.id]), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { play(detail) }, modifier = Modifier.fillMaxWidth()) { Icon(if(playback.episode?.id == detail.id && playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if(playback.episode?.id == detail.id && playback.playing) "Pause" else if(library.position(detail) > 0 && library.progress[detail.id]?.played != true) "Resume" else "Play episode") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.queue(detail, true) }, modifier = Modifier.weight(1f)) { Text("Play next") }
                OutlinedButton(onClick = { vm.queue(detail) }, modifier = Modifier.weight(1f)) { Text("Add to queue") }
            }
            val download = state.downloads[detail.id]
            TextButton(onClick = { if(download?.ready == true || download != null && download.status != android.app.DownloadManager.STATUS_FAILED) vm.removeDownload(detail.id) else vm.download(detail) }) {
                Icon(if(download?.ready == true) Icons.Outlined.DownloadDone else Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text(when { download?.ready == true -> "Remove download"; download != null && download.status != android.app.DownloadManager.STATUS_FAILED -> "${download.label} · Cancel"; else -> download?.label ?: "Download for offline listening" })
            }
            TextButton(onClick = { vm.played(detail, library.progress[detail.id]?.played != true); detailId = null }) { Text(if(library.progress[detail.id]?.played == true) "Mark unplayed" else "Mark played") }
            val context = LocalContext.current
            TextButton(onClick = { val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${detail.title}\n${library.show(detail)?.url.orEmpty()}"); context.startActivity(Intent.createChooser(share, "Share episode")) }) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("Share podcast") }
            Text(plain(detail.description).ifBlank { "No episode notes supplied." }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
        }
    }
    if(queueOpen) ModalBottomSheet(onDismissRequest = { queueOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
        QueueContent(library, playback, vm.graph.preferences.getBoolean("autoNext", true), { play(it); queueOpen = false }, vm::removeQueue, vm::moveQueue)
    }
    if(feedDialog) {
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { feedDialog = false }, title = { Text("Add a podcast feed") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Paste a public or private RSS feed link from the publisher."); OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Feed link") }, singleLine = true) } }, confirmButton = { TextButton(onClick = { vm.addFeed(url); feedDialog = false }, enabled = url.isNotBlank()) { Text("Subscribe") } }, dismissButton = { TextButton(onClick = { feedDialog = false }) { Text("Cancel") } })
    }
}
@Composable private fun Header(title: String, subtitle: String? = null, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if(back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(title, Modifier.weight(1f), fontSize = 30.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            actions()
        }
        if(subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
@Composable internal fun Cover(image: String, title: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Serif, fontSize = 28.sp)
        if(image.isNotBlank()) AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
@Composable internal fun ListenContent(library: Library, playback: Playback, downloads: Map<String, Download>, loaded: Boolean = true, discover: () -> Unit = {}, queue: () -> Unit = {}, settings: () -> Unit = {}, refresh: () -> Unit = {}, detail: (Episode) -> Unit = {}, play: (Episode) -> Unit = {}) {
    val subscribedIds = library.shows.filter { it.subscribed }.map { it.id }.toSet()
    val episodes = library.episodes.filter { it.showId in subscribedIds }
    val recent = library.episodes.filter { (library.progress[it.id]?.position ?: 0) > 0 && library.progress[it.id]?.played != true }.sortedByDescending { library.progress[it.id]?.updated ?: 0 }.take(3)
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp), state = rememberLazyListState()) {
        item { Header("Listen", "A little space for a good listen.", actions = {
            IconButton(onClick = queue) { Icon(Icons.Outlined.QueueMusic, "Up next") }
            IconButton(onClick = settings) { Icon(Icons.Outlined.Settings, "Settings") }
        }) }
        if(!loaded) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if(recent.isNotEmpty()) {
            item { SectionTitle("Pick up where you left off") }
            items(recent, key = { "recent-${it.id}" }) { episode ->
                Card(Modifier.padding(horizontal = 24.dp, vertical = 5.dp).fillMaxWidth().clickable { detail(episode) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(episode.image.ifBlank { library.show(episode)?.image.orEmpty() }, episode.title, Modifier.size(64.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(library.show(episode)?.title.orEmpty(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(episode.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if(episode.duration > 0) "${timeLabel((episode.duration - library.position(episode)).coerceAtLeast(0))} left" else "At ${timeLabel(library.position(episode))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PlayButton(episode, playback, { play(episode) }, 48)
                    }
                    if(episode.duration > 0) LinearProgressIndicator(progress = { (library.position(episode).toFloat() / episode.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if(episodes.isEmpty() && loaded) item {
            Column(Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Headphones, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                Text("Your favourites.\nYour own pace.", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Text("Find a show, subscribe, and make yourself at home. No adverts from the app. No account to create.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = discover) { Text("Find your first podcast"); Icon(Icons.Outlined.Search, null, Modifier.padding(start = 8.dp)) }
            }
        }
        if(episodes.isNotEmpty()) {
            item { Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("Fresh episodes", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 23.sp, modifier = Modifier.weight(1f)); IconButton(onClick = refresh) { Icon(Icons.Outlined.Refresh, "Refresh podcasts") } } }
            items(episodes.filter { library.progress[it.id]?.played != true }.sortedByDescending { it.date }.take(70), key = { it.id }) { EpisodeRow(it, library, downloads[it.id], playback, { detail(it) }, { play(it) }) }
        }
    }
}
@Composable private fun SectionTitle(text: String) { Text(text, Modifier.padding(horizontal = 24.dp, vertical = 18.dp), fontSize = 23.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) }
@Composable private fun PlayButton(episode: Episode, playback: Playback, play: () -> Unit, size: Int = 48) {
    val playing = playback.episode?.id == episode.id && playback.playing
    FilledIconButton(onClick = play, modifier = Modifier.size(size.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Icon(if(playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(playing) "Pause ${episode.title}" else "Play ${episode.title}")
    }
}
@Composable private fun EpisodeRow(episode: Episode, library: Library, download: Download?, playback: Playback, detail: () -> Unit, play: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = detail).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(episode.image.ifBlank { library.show(episode)?.image.orEmpty() }, episode.title, Modifier.size(62.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(library.show(episode)?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(episode.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(download?.label ?: episodeMeta(episode, library.progress[episode.id]), fontSize = 12.sp, color = if(download?.ready == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlayButton(episode, playback, play, 44)
    }
}
private fun episodeMeta(episode: Episode, progress: Progress?): String = listOfNotNull(
    if(episode.date > 0) SimpleDateFormat("d MMM", Locale.UK).format(Date(episode.date)) else null,
    if(progress?.played == true) "Played" else if((progress?.position ?: 0) > 0 && episode.duration > 0) "${timeLabel((episode.duration - progress!!.position).coerceAtLeast(0))} left" else if(episode.duration > 0) timeLabel(episode.duration) else null
).joinToString(" · ")
private fun plain(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

@Composable private fun LibraryContent(library: Library, downloads: Map<String, Download>, playback: Playback, show: (Show) -> Unit, detail: (Episode) -> Unit, play: (Episode) -> Unit, addFeed: () -> Unit, importOpml: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("Shows") }
    var query by rememberSaveable { mutableStateOf("") }
    Column {
        Header("Your library", "Good company, kept close.", actions = { IconButton(onClick = addFeed) { Icon(Icons.Outlined.Add, "Add feed link") } })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp), placeholder = { Text("Search your library") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp))
        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Shows", "Downloads", "Played").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) } }
        LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if(filter == "Shows") {
                val shows = library.shows.filter { it.subscribed && (it.title.contains(query, true) || it.author.contains(query, true)) }.sortedBy { it.title.lowercase() }
                if(shows.isEmpty()) item { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Your subscriptions will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedButton(onClick = importOpml) { Text("Import subscriptions (OPML)") } } }
                items(shows, key = { it.id }) { ShowRow(it, { show(it) }, "${library.episodes.count { e -> e.showId == it.id && library.progress[e.id]?.played != true }} unplayed episodes") }
            } else {
                val episodes = library.episodes.filter { e -> (if(filter == "Downloads") downloads.containsKey(e.id) else library.progress[e.id]?.played == true) && (e.title.contains(query, true) || library.show(e)?.title?.contains(query, true) == true) }.sortedByDescending { it.date }
                if(episodes.isEmpty()) item { Text(if(filter == "Downloads") "Download an episode from its details to listen offline." else "Finished episodes will appear here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(episodes, key = { it.id }) { EpisodeRow(it, library, downloads[it.id], playback, { detail(it) }, { play(it) }) }
            }
        }
    }
}
@Composable private fun ShowRow(show: Show, click: () -> Unit, subtitle: String = show.author) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(show.image, show.title, Modifier.size(72.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(show.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun DiscoverContent(state: ScreenState, search: (String) -> Unit = {}, show: (Show) -> Unit = {}, feed: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    Column {
        Header("Discover", "Follow your curiosity.", actions = { IconButton(onClick = feed) { Icon(Icons.Outlined.AddLink, "Add feed link") } })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp), placeholder = { Text("Search podcasts or creators") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { IconButton(onClick = { search(query) }, enabled = query.isNotBlank() && !state.searching) { Icon(Icons.Outlined.ArrowForward, "Search podcasts") } }, shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search(query) }))
        if(state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), state = rememberLazyListState()) {
            if(state.results.isNotEmpty()) { item { SectionTitle("Search results") }; items(state.results, key = { it.id }) { ShowRow(it, { show(it) }) } }
            else {
                item { SectionTitle("Find your next good listen") }
                item { Text("Search for a podcast or creator above, or add a feed link. Your library starts empty so you can make it your own.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text("Search uses Apple’s podcast directory. Already have a feed link? Add it directly, including private subscriptions.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
@Composable private fun ShowContent(show: Show, library: Library, downloads: Map<String, Download>, playback: Playback, back: () -> Unit, subscribe: () -> Unit, detail: (Episode) -> Unit, play: (Episode) -> Unit, refresh: () -> Unit) {
    var oldestFirst by rememberSaveable(show.id) { mutableStateOf(false) }
    var unplayed by rememberSaveable(show.id) { mutableStateOf(false) }
    val episodes = library.episodes.filter { it.showId == show.id && (!unplayed || library.progress[it.id]?.played != true) }.let { if(oldestFirst) it.sortedBy { e -> e.date } else it.sortedByDescending { e -> e.date } }
    LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Podcast", back = back, actions = { IconButton(onClick = refresh) { Icon(Icons.Outlined.Refresh, "Refresh show") } }) }
        item { Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Cover(show.image, show.title, Modifier.size(112.dp)); Column(Modifier.padding(start = 20.dp).weight(1f)) { Text(show.title, fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(show.author, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            Button(onClick = subscribe, modifier = Modifier.fillMaxWidth()) { Icon(if(show.subscribed) Icons.Outlined.Check else Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(if(show.subscribed) "Subscribed" else "Subscribe") }
            if(show.description.isNotBlank()) Text(plain(show.description), maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Plays forwards through this show. Browsing never changes your player.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = oldestFirst, onClick = { oldestFirst = !oldestFirst }, label = { Text(if(oldestFirst) "Oldest first" else "Newest first") }, leadingIcon = { Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp)) }); FilterChip(selected = unplayed, onClick = { unplayed = !unplayed }, label = { Text("Unplayed") }) }
        } }
        if(episodes.isEmpty()) item { Text("No episodes loaded. Tap refresh to check this feed.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(episodes, key = { it.id }) { EpisodeRow(it, library, downloads[it.id], playback, { detail(it) }, { play(it) }) }
    }
}
@Composable private fun MiniPlayer(playback: Playback, library: Library, open: () -> Unit, toggle: () -> Unit) {
    val episode = playback.episode ?: return
    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = open)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(episode.image.ifBlank { library.show(episode)?.image.orEmpty() }, episode.title, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); Text(library.show(episode)?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            IconButton(onClick = toggle) { Icon(if(playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(playback.playing) "Pause" else "Resume") }
        }
        if(playback.duration > 0) LinearProgressIndicator(progress = { (playback.position.toFloat() / playback.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}
@Composable internal fun PlayerContent(p: Playback, library: Library, back: () -> Unit = {}, toggle: () -> Unit = {}, seek: (Long) -> Unit = {}, speed: (Float) -> Unit = {}, timer: (Int) -> Unit = {}, bookmark: () -> Unit = {}, queue: () -> Unit = {}, undo: () -> Unit = {}, stopAfter: () -> Unit = {}, removeBookmark: (Bookmark) -> Unit = {}) {
    val episode = p.episode ?: return
    var speedMenu by remember { mutableStateOf(false) }; var timerMenu by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }; var showBookmarks by remember { mutableStateOf(false) }
    var sliding by remember(episode.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header("Now playing", back = back, actions = { IconButton(onClick = queue) { Icon(Icons.Outlined.QueueMusic, "Up next") } })
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Cover(episode.image.ifBlank { library.show(episode)?.image.orEmpty() }, episode.title, Modifier.widthIn(max = 290.dp).fillMaxWidth(.75f).aspectRatio(1f)) }
            Text(library.show(episode)?.title.orEmpty(), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            Text(episode.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp)
            val chapter = episode.chapters.lastOrNull { p.position >= it.startMs }
            if(chapter != null) TextButton(onClick = { showChapters = true }) { Text(chapter.title); Icon(Icons.Outlined.List, null, Modifier.padding(start = 8.dp)) }
            Slider(value = sliding ?: p.position.toFloat().coerceAtMost(p.duration.coerceAtLeast(1).toFloat()), onValueChange = { sliding = it }, onValueChangeFinished = { sliding?.let { seek(it.toLong()) }; sliding = null }, valueRange = 0f..p.duration.coerceAtLeast(1).toFloat(), enabled = p.duration > 0, modifier = Modifier.semantics { contentDescription = "Episode position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeLabel(sliding?.toLong() ?: p.position)); Text(if(p.duration > 0) "${timeLabel((p.duration - (sliding?.toLong() ?: p.position)).coerceAtLeast(0))} left" else "Streaming", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if(p.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { seek(p.position - 15000) }, modifier = Modifier.size(64.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Replay, "Back 15 seconds", Modifier.size(36.dp)); Text("15", fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) } }
                FilledIconButton(onClick = toggle, modifier = Modifier.size(80.dp)) { Icon(if(p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(p.playing) "Pause episode" else "Resume episode", Modifier.size(44.dp)) }
                IconButton(onClick = { seek(p.position + 30000) }, modifier = Modifier.size(64.dp)) { Icon(Icons.Outlined.Forward30, "Forward 30 seconds", Modifier.size(36.dp)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Box { TextButton(onClick = { speedMenu = true }) { Text("${p.speed}×  Speed") }; DropdownMenu(speedMenu, { speedMenu = false }) { listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { value -> DropdownMenuItem(text = { Text("${value}×") }, onClick = { speed(value); speedMenu = false }) } } }
                Box { TextButton(onClick = { timerMenu = true }) { Icon(Icons.Outlined.Bedtime, null, Modifier.size(18.dp)); Text(if(p.timerMinutes == -1) " End of episode" else if(p.timerMinutes > 0) " ${p.timerMinutes} min" else " Sleep timer") }; DropdownMenu(timerMenu, { timerMenu = false }) { listOf(0, 10, 15, 30, 45, 60, -1).forEach { value -> DropdownMenuItem(text = { Text(if(value == 0) "Off" else if(value == -1) "End of this episode" else "$value minutes") }, onClick = { timer(value); timerMenu = false }) } } }
                IconButton(onClick = bookmark) { Icon(Icons.Outlined.BookmarkAdd, "Save bookmark") }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Stop after this episode", Modifier.weight(1f)); Switch(checked = p.stopAfter, onCheckedChange = { stopAfter() }) }
            }
            if(p.undo != null) OutlinedButton(onClick = undo, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Undo, null); Text("Undo last jump", Modifier.padding(start = 8.dp)) }
            if(p.error != null) Text(p.error, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = queue) { Icon(Icons.Outlined.QueueMusic, null); Text("Up next", Modifier.padding(start = 6.dp)) }
                TextButton(onClick = { showBookmarks = !showBookmarks }) { Icon(Icons.Outlined.Bookmarks, null); Text("Bookmarks", Modifier.padding(start = 6.dp)) }
            }
            if(episode.chapters.isNotEmpty()) TextButton(onClick = { showChapters = !showChapters }) { Text("${episode.chapters.size} chapters") }
            if(showChapters) episode.chapters.forEach { c -> TextButton(onClick = { seek(c.startMs) }) { Text("${timeLabel(c.startMs)}   ${c.title}") } }
            if(showBookmarks) {
                val bookmarks = library.bookmarks.filter { it.episodeId == episode.id }
                if(bookmarks.isEmpty()) Text("No bookmarks yet. Tap the bookmark button to save a moment.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                bookmarks.forEach { b -> Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { seek(b.position) }, modifier = Modifier.weight(1f)) { Text(b.label) }; IconButton(onClick = { removeBookmark(b) }) { Icon(Icons.Outlined.Close, "Remove bookmark") } } }
            }
        }
    }
}
@Composable private fun QueueContent(library: Library, playback: Playback, autoNext: Boolean, play: (Episode) -> Unit, remove: (String) -> Unit, move: (String, Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxWidth()) {
        item { Header("Up next", "Your queue plays first, then the next newer unplayed episode.") }
        val queue = library.queue.mapNotNull { id -> library.episodes.find { it.id == id } }
        items(queue, key = { it.id }) { e -> Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
            Text(e.title, Modifier.fillMaxWidth().clickable { play(e) }, fontWeight = FontWeight.Medium)
            Row { IconButton(onClick = { move(e.id, -1) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move earlier") }; IconButton(onClick = { move(e.id, 1) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move later") }; Spacer(Modifier.weight(1f)); IconButton(onClick = { remove(e.id) }) { Icon(Icons.Outlined.Close, "Remove from queue") } }
        } }
        val next = (queue.lastOrNull() ?: playback.episode)?.let { nextEpisode(it, library.copy(queue = emptyList(), progress = library.progress + queue.associate { queued -> queued.id to Progress(played = true) }), autoNext, playback.stopAfter || playback.timerMinutes == -1) }
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("After the queue", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(when { playback.stopAfter || playback.timerMinutes == -1 -> "Playback stops after the current episode."; next != null -> next.title; !autoNext -> "Automatic continuation is off."; else -> "Playback stops when there’s no newer unplayed episode." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable private fun SettingsContent(vm: PodcastModel, back: () -> Unit, importOpml: () -> Unit, exportOpml: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val choices = remember(state.settingsVersion) { Triple(vm.graph.preferences.getBoolean("autoNext", true), vm.graph.preferences.getBoolean("wifiOnly", true), vm.graph.preferences.getBoolean("dark", true)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Settings", back = back)
        SectionTitle("Listening")
        SettingSwitch("Continue within the same show", "Play the next newer unplayed episode. Never jump backwards through the archive.", choices.first) { vm.setting("autoNext", it) }
        SectionTitle("Downloads & appearance")
        SettingSwitch("Download on Wi-Fi only", "New downloads wait for Wi-Fi. Streaming uses your current connection.", choices.second) { vm.setting("wifiOnly", it) }
        SettingSwitch("Warm dark theme", "Turn off for a warm light theme.", choices.third) { vm.setting("dark", it) }
        SectionTitle("Bring your podcasts with you")
        Text("Import and export subscriptions as OPML. Private feed links are included, so keep exported files private.", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = importOpml, modifier = Modifier.padding(start = 16.dp, top = 12.dp)) { Icon(Icons.Outlined.FileOpen, null); Text("Import subscriptions", Modifier.padding(start = 8.dp)) }
        TextButton(onClick = exportOpml, modifier = Modifier.padding(start = 16.dp)) { Icon(Icons.Outlined.SaveAlt, null); Text("Export subscriptions", Modifier.padding(start = 8.dp)) }
        SectionTitle("About this first version")
        Text("Podcasts ${BuildConfig.VERSION_NAME}\nAn ad-free podcast player for Android, built with AI.\n\nNo account, app advertising or analytics. Search is provided by Apple; feeds and audio come directly from publishers. Adverts included in an episode’s audio will still play.\n\nDownloads and listening history stay on this phone. Uninstalling removes them. Subscription exports contain feed links, not listening progress.\n\nChromecast, Android Auto browsing, widgets, automatic downloads and background new-episode alerts are planned; they are not included in this version.", Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun SettingSwitch(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }; Switch(checked, change) }
}
