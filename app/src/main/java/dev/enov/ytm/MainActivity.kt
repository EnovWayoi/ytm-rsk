package dev.enov.ytm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val dropPermissions = mutableListOf<android.view.DragAndDropPermissions>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { MusicApp() }
        window.decorView.setOnDragListener { _, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.let { it.hasMimeType("audio/*") || it.hasMimeType("application/octet-stream") } == true
                android.view.DragEvent.ACTION_DROP -> {
                    requestDragAndDropPermissions(event)?.let(dropPermissions::add)
                    val model = ViewModelProvider(this)[MusicViewModel::class.java]
                    val data = event.clipData
                    if (data == null) false else {
                        (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri }.forEach(model::importAudio)
                        true
                    }
                }
                else -> true
            }
        }
    }
    override fun onResume() { super.onResume(); ViewModelProvider(this)[MusicViewModel::class.java].refreshLocal() }
    override fun onDestroy() { dropPermissions.forEach { it.release() }; super.onDestroy() }
}

private val background = Color(0xFF0C0A0E)
private val surface = Color(0xFF1C1921)
private val muted = Color(0xFFB2ACBA)
private fun accent(name: String) = when (name) { "Iris" -> Color(0xFFBCA2FF); "Mint" -> Color(0xFF8BDDB7); "Amber" -> Color(0xFFF3C77D); else -> Color(0xFFFF5268) }
private fun Song.canPlay() = kind == "song" || kind == "episode" || id.startsWith("content:") || id.startsWith("file:")
private fun Song.remotePlaylist() = kind == "playlist" && !id.startsWith("local:")

@Composable
fun MusicApp(vm: MusicViewModel = viewModel()) {
    val tint = accent(vm.theme)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Songs") }
    var full by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = darkColorScheme(primary = tint, onPrimary = background, primaryContainer = tint.copy(alpha = .18f), onPrimaryContainer = tint, secondaryContainer = tint.copy(alpha = .18f), onSecondaryContainer = tint, background = background, surface = surface, onSurface = Color(0xFFF2F0F5), secondary = tint)
    fun refresh() { if (query.isNotBlank()) vm.search(query, filter) else if (tab == 1) vm.explore() else vm.home() }
    fun search() { if (query.isNotBlank()) { vm.search(query, filter); keyboard?.hide(); focus.clearFocus() } }
    MaterialTheme(colorScheme = colors) {
        BackHandler(enabled = tab != 0) { tab = 0; query = ""; vm.home() }
        Scaffold(containerColor = background, bottomBar = {
            Column {
                vm.current?.let { MiniPlayer(vm, it) { full = true } }
                NavigationBar(containerColor = background, tonalElevation = 0.dp) {
                    listOf("Home" to Icons.Rounded.Home, "Explore" to Icons.Rounded.Explore, "Library" to Icons.Rounded.LibraryMusic, "Settings" to Icons.Rounded.Settings).forEachIndexed { i, (name, icon) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; query = ""; focus.clearFocus(); when (i) { 0 -> vm.home(); 1 -> vm.explore(); 2 -> vm.openLibrary(vm.libraryCategory) } }, icon = { Icon(icon, name) }, label = { Text(name) }, colors = NavigationBarItemDefaults.colors(indicatorColor = tint.copy(alpha = .15f), selectedIconColor = tint, selectedTextColor = tint))
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(32.dp))
                    Text(stringResource(R.string.app_name), Modifier.padding(start = 10.dp).weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("ANDROID", color = Color.Gray, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                if (tab < 2) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Songs, artists, albums…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = {
                        if (query.isNotEmpty()) Row {
                            IconButton(onClick = { query = ""; refresh() }) { Icon(Icons.Rounded.Close, "Clear search") }
                            IconButton(onClick = ::search) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Search") }
                        }
                    }, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                    val categories = (if (query.isBlank()) listOf("All") else emptyList()) + listOf("Songs", "Albums", "Artists", "Playlists") + (if (query.isNotBlank()) listOf("Videos") else emptyList()) + (if (vm.podcasts) listOf("Podcasts") else emptyList())
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { name -> FilterChip(selected = (if (query.isBlank()) vm.category else filter) == name, onClick = { filter = if (name == "All") "Songs" else name; if (query.isNotBlank()) vm.search(query, name) else vm.setCategory(name) }, label = { Text(name) }) }
                    }
                }
                vm.error?.let { message -> Message(message) { vm.error = null } }
                if (vm.downloadBusy || vm.downloadStatus.isNotBlank()) Row(Modifier.padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vm.downloadBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(vm.downloadStatus, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodySmall, color = muted)
                    if (vm.downloads.isNotEmpty() && !vm.downloadBusy && vm.downloadStatus.startsWith("Saved")) TextButton(onClick = { tab = 2; vm.openLibrary("Offline songs"); vm.dismissDownloadNotice() }) { Text("Show") }
                    if (!vm.downloadBusy) IconButton(onClick = vm::dismissDownloadNotice) { Icon(Icons.Rounded.Close, "Dismiss download notice") }
                }
                when (tab) { 2 -> Library(vm); 3 -> Settings(vm); else -> if (vm.busy && vm.items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } else Feed(vm, tab == 1, ::refresh) }
            }
        }
        if (full && vm.current != null) FullPlayer(vm) { full = false }
    }
}

@Composable
private fun Message(message: String, dismiss: () -> Unit) {
    Surface(color = Color(0xFF382029), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f).padding(14.dp), style = MaterialTheme.typography.bodySmall); IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "Dismiss error") } }
    }
}

@Composable
private fun Feed(vm: MusicViewModel, explore: Boolean, onRefresh: () -> Unit) {
    if (vm.activePlaylistId.isNotBlank() && !vm.activePlaylistId.startsWith("local:") && vm.activePlaylistEditable) {
        Column(Modifier.fillMaxSize()) {
            ListHeader(vm.pageTitle, vm.items, vm, onRefresh)
            Text("Changes require playlist ownership; YouTube may reject edits.", Modifier.padding(horizontal = 22.dp), color = muted, style = MaterialTheme.typography.bodySmall)
            ReorderList(vm.items, vm, vm::moveRemotePlaylistSong, remove = { index -> vm.items.getOrNull(index)?.let(vm::removeRemotePlaylistSong) }, more = true)
        }
        return
    }
    var find by remember(vm.pageTitle) { mutableStateOf("") }
    val matching = vm.items.filter { find.isBlank() || "${it.title} ${it.subtitle}".contains(find, true) }
    val ids = matching.map { it.id }.toSet()
    val sections = vm.sections.map { it.copy(items = it.items.filter { song -> song.id in ids }) }.filter { it.items.isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ListHeader(vm.pageTitle, matching, vm, onRefresh)
            if (vm.activePlaylistId.isNotBlank() && !vm.activePlaylistEditable) Text("This playlist is read-only in this session.", Modifier.padding(horizontal = 22.dp, vertical = 6.dp), color = muted, style = MaterialTheme.typography.bodySmall)
            if (explore && !vm.pageTitle.startsWith("Results") && vm.chips.none { it.title == "Charts" }) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.browse("FEmusic_new_releases_albums", "New releases") }, label = { Text("New releases") })
                AssistChip(onClick = { vm.browse("FEmusic_charts", "Charts") }, label = { Text("Charts") })
                AssistChip(onClick = { vm.browse("FEmusic_moods_and_genres", "Moods & genres") }, label = { Text("Moods & genres") })
            }
            if (vm.chips.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { vm.chips.forEach { chip -> SuggestionChip(onClick = { vm.selectMood(chip) }, label = { Text(chip.title) }) } }
            FindBox(find) { find = it }
        }
        if (matching.isEmpty()) item { EmptyList("Nothing in this category yet. Search for music or try another category.") }
        if (sections.isNotEmpty()) {
            sections.forEachIndexed { sectionIndex, section ->
                item(key = "section-$sectionIndex") { Text(section.title, Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                itemsIndexed(section.items, key = { index, song -> "$sectionIndex-$index-${song.id}" }) { _, song -> SongRow(song, vm, section.items) }
            }
            val sectionIds = sections.flatMap { it.items }.map { it.id }.toSet()
            itemsIndexed(matching.filterNot { it.id in sectionIds }, key = { i, song -> "extra-$i-${song.id}" }) { _, song -> SongRow(song, vm, matching) }
        } else itemsIndexed(matching, key = { i, song -> "$i-${song.id}" }) { _, song -> SongRow(song, vm, matching) }
        if (vm.canLoadMore) item { LoadMore(vm) }
    }
}

@Composable
private fun ListHeader(title: String, songs: List<Song>, vm: MusicViewModel, onRefresh: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(if (title.startsWith("Results")) "SEARCH" else "MADE FOR LISTENING", color = accent(vm.theme), letterSpacing = 2.sp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${songs.size} ${if (songs.size == 1) "result" else "results"}", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh") }
            songs.firstOrNull { it.canPlay() }?.let { first -> FilledTonalButton(onClick = { vm.select(first, songs) }) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") } }
        }
    }
}

@Composable
private fun FindBox(value: String, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, singleLine = true, placeholder = { Text("Find in this list") }, leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(20.dp)) }, trailingIcon = { if (value.isNotBlank()) IconButton(onClick = { changed("") }) { Icon(Icons.Rounded.Close, "Clear list filter") } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp))
}

@Composable
private fun EmptyList(message: String) { Text(message, Modifier.padding(22.dp), color = muted) }

@Composable
private fun LoadMore(vm: MusicViewModel) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = vm::loadMore, enabled = !vm.busy) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(if (vm.busy) "Loading…" else "Load more", Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun Library(vm: MusicViewModel) {
    val category = vm.libraryCategory
    var selectedPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(category) { selectedPlaylist = null }
    var naming by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<UserPlaylist?>(null) }
    var onGoogle by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(vm::importAudio) }
    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(vm::uploadToAccount) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your music", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { context.startActivity(Intent(context, AccountActivity::class.java)) }) { Icon(if (vm.signedIn) Icons.Rounded.AccountCircle else Icons.Rounded.PersonAdd, "Google account") }
        }
        Surface(onClick = { selectedPlaylist = null; vm.openLibrary("Offline songs") }, color = surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.OfflinePin, null, tint = accent(vm.theme))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text("Offline songs", fontWeight = FontWeight.SemiBold); Text("${vm.downloads.size} saved · Available without internet", style = MaterialTheme.typography.bodySmall, color = muted) }
                Icon(Icons.Rounded.ChevronRight, "Open offline songs")
            }
        }
        val categories = listOf("Offline songs", "Favorites", "Albums", "Artists", "Playlists", "History", "Uploads") + (if (vm.podcasts) listOf("Podcasts") else emptyList()) + (if (vm.signedIn) listOf("Google library") else emptyList())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { label -> FilterChip(selected = category == label, onClick = { selectedPlaylist = null; vm.openLibrary(label) }, label = { Text(label) }) }
        }
        if (category == "Uploads") Column(Modifier.padding(horizontal = 22.dp)) {
            Text("Import keeps a local copy. Upload sends audio to your Google account.", style = MaterialTheme.typography.bodySmall, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { importer.launch(arrayOf("audio/*")) }) { Text("Import locally") }
                if (vm.signedIn) OutlinedButton(onClick = { uploader.launch(arrayOf("audio/*")) }, enabled = !vm.downloadBusy) { Text("Upload to Google") }
            }
        }
        if (category == "Playlists" && selectedPlaylist == null && vm.activePlaylistId.isBlank()) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("On this device", Modifier.weight(1f), color = muted, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { name = ""; rename = null; onGoogle = false; naming = true }) { Icon(Icons.Rounded.Add, null); Text("New playlist") }
                } }
                items(vm.playlists, key = { it.id }) { playlist ->
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clickable { selectedPlaylist = playlist.id; vm.openPlaylist(playlist) }.padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(34.dp), tint = accent(vm.theme))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(playlist.title, fontWeight = FontWeight.SemiBold); Text("${playlist.songs.size} songs", style = MaterialTheme.typography.bodySmall, color = muted) }
                        Box { IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename playlist") }, onClick = { rename = playlist; name = playlist.title; naming = true; menu = false })
                            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { vm.deletePlaylist(playlist.id); menu = false })
                        } }
                    }
                }
                val saved = ((if (vm.signedIn) vm.items else emptyList()) + vm.savedCollections).filter { it.kind == "playlist" && !it.id.startsWith("local:") }.distinctBy { it.id }
                if (saved.isNotEmpty()) item { Text(if (vm.signedIn) "YouTube Music playlists" else "Saved from YouTube Music", Modifier.padding(22.dp), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(saved, key = { i, s -> "saved-$i-${s.id}" }) { _, song -> SongRow(song, vm, saved) }
                if (vm.playlists.isEmpty() && saved.isEmpty()) item { EmptyList("Create a playlist, or save one from search.") }
                if (vm.canLoadMore) item { LoadMore(vm) }
            }
        } else {
            val playlist = vm.playlists.find { it.id == selectedPlaylist }
            if (selectedPlaylist != null && playlist != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedPlaylist = null; vm.openLibrary("Playlists") }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Text("Playlists") }
                    Text(playlist.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                }
                ReorderList(playlist.songs, vm, { from, to -> vm.movePlaylistSong(playlist.id, from, to) }, remove = { vm.removeFromPlaylist(playlist.id, it) })
            } else if (vm.activePlaylistId.isNotBlank() && !vm.activePlaylistId.startsWith("local:") && category == "Playlists") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedPlaylist = null; vm.openLibrary("Playlists") }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Text("Playlists") }
                    Text(vm.pageTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                }
                if (vm.activePlaylistEditable) {
                    Text("Changes require playlist ownership.", Modifier.padding(horizontal = 22.dp), color = muted, style = MaterialTheme.typography.bodySmall)
                    ReorderList(vm.items, vm, vm::moveRemotePlaylistSong, remove = { index -> vm.items.getOrNull(index)?.let(vm::removeRemotePlaylistSong) }, more = true)
                } else {
                    Text("This playlist is read-only in this session.", Modifier.padding(horizontal = 22.dp), color = muted, style = MaterialTheme.typography.bodySmall)
                    SongList(vm.items, vm, vm.pageTitle, "No tracks loaded.")
                }
            } else if (vm.busy && vm.items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else SongList(vm.items, vm, vm.pageTitle, "Nothing here yet. Save music or play a song to get started.")
        }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text(if (rename == null) "New playlist" else "Rename playlist") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Playlist name") })
            if (vm.signedIn && rename == null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(onGoogle, { onGoogle = it }); Text("Create on YouTube Music") }
        }
    }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { if (rename != null) vm.renamePlaylist(rename!!.id, name.trim()) else if (onGoogle) vm.createRemotePlaylist(name.trim()) else vm.createPlaylist(name.trim()); naming = false } }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } })
}

@Composable
private fun SongList(songs: List<Song>, vm: MusicViewModel, title: String, empty: String) {
    var find by remember(title) { mutableStateOf("") }
    val matching = songs.filter { find.isBlank() || "${it.title} ${it.subtitle}".contains(find, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { ListHeader(title, matching, vm); FindBox(find) { find = it } }
        if (matching.isEmpty()) item { EmptyList(empty) }
        itemsIndexed(matching, key = { i, song -> "$i-${song.id}" }) { _, song -> SongRow(song, vm, matching) }
        if (vm.canLoadMore) item { LoadMore(vm) }
    }
}

@Composable
private fun Art(song: Song, modifier: Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val request = remember(song.art, large) { ImageRequest.Builder(context).data(song.art).size(if (large) 1200 else 200).crossfade(true).build() }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(surface), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFF716878), modifier = Modifier.size(28.dp))
        if (song.art.isNotBlank()) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
    }
}

@Composable
private fun SongRow(song: Song, vm: MusicViewModel, songs: List<Song>, modifier: Modifier = Modifier, onPlay: (() -> Unit)? = null) {
    val selected = vm.current?.id == song.id
    var menu by remember { mutableStateOf(false) }
    var playlistPicker by remember { mutableStateOf(false) }
    var deletingRemote by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clickable { if (onPlay != null) onPlay() else if (song.remotePlaylist()) vm.openRemotePlaylist(song) else vm.select(song, songs) }.padding(start = 20.dp, end = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(song, Modifier.size(54.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp)) {
            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (selected) accent(vm.theme) else Color(0xFFF2F0F5), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) Text("E", Modifier.padding(end = 6.dp).background(Color(0xFF4E4855), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp), fontSize = 9.sp, color = Color.White)
                Text(song.subtitle.ifBlank { if (song.canPlay()) "Song" else song.kind.replaceFirstChar { it.uppercase() } }, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (song.canPlay()) {
            val liked = vm.favorites.any { it.id == song.id }
            IconButton(onClick = { vm.toggleFavorite(song) }, modifier = Modifier.size(44.dp)) { Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove favorite" else "Save favorite", tint = if (liked) accent(vm.theme) else Color.Gray, modifier = Modifier.size(22.dp)) }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.MoreVert, "Options for ${song.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (song.canPlay()) {
                    DropdownMenuItem(text = { Text("Start song radio") }, onClick = { vm.radio(song); menu = false })
                    DropdownMenuItem(text = { Text("Add to queue") }, onClick = { vm.enqueue(song); menu = false })
                    DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { playlistPicker = true; menu = false })
                    if (!song.id.startsWith("file:") && !song.id.startsWith("content:")) {
                        DropdownMenuItem(text = { Text("Download audio") }, onClick = { vm.download(song); menu = false }, enabled = !vm.downloadBusy)
                        DropdownMenuItem(text = { Text("Play music video") }, onClick = { vm.playVideo(song); menu = false })
                    }
                    if (song.id.startsWith("file:")) DropdownMenuItem(text = { Text("Remove download") }, onClick = { vm.removeDownload(song); menu = false })
                    if (song.albumId.isNotBlank()) DropdownMenuItem(text = { Text("Go to album") }, onClick = { vm.browse(song.albumId, song.albumTitle.ifBlank { "Album" }); menu = false })
                    if (song.artistId.isNotBlank()) DropdownMenuItem(text = { Text("Go to artist") }, onClick = { vm.browse(song.artistId, song.artist); menu = false })
                } else {
                    val saved = vm.savedCollections.any { it.id == song.id }
                    DropdownMenuItem(text = { Text(if (saved) "Remove from library" else "Save to library") }, onClick = { vm.toggleCollection(song); menu = false })
                    DropdownMenuItem(text = { Text("Open ${song.kind}") }, onClick = { if (song.remotePlaylist()) vm.openRemotePlaylist(song) else vm.select(song, songs); menu = false })
                    if (vm.signedIn && song.remotePlaylist()) DropdownMenuItem(text = { Text("Delete from YouTube Music") }, onClick = { deletingRemote = true; menu = false })
                }
            }
        }
    }
    if (playlistPicker) PlaylistPicker(vm, song) { playlistPicker = false }
    if (deletingRemote) AlertDialog(onDismissRequest = { deletingRemote = false }, title = { Text("Delete playlist?") }, text = { Text("Delete “${song.title}” from your YouTube Music account? This requires ownership and cannot be undone.") }, confirmButton = { TextButton(onClick = { vm.deleteRemotePlaylist(song); deletingRemote = false }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletingRemote = false }) { Text("Cancel") } })
}

@Composable
private fun PlaylistPicker(vm: MusicViewModel, song: Song, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add to playlist") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (vm.playlists.isNotEmpty()) Text("On this device", color = muted, style = MaterialTheme.typography.labelSmall)
            vm.playlists.forEach { playlist -> TextButton(onClick = { vm.addToPlaylist(playlist.id, song); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text(playlist.title, Modifier.fillMaxWidth()) } }
            val remote = (vm.savedCollections + vm.items).filter { it.remotePlaylist() }.distinctBy { it.id }
            if (vm.signedIn && remote.isNotEmpty() && !song.id.startsWith("file:") && !song.id.startsWith("content:")) {
                Text("YouTube Music · ownership required", color = muted, style = MaterialTheme.typography.labelSmall)
                remote.forEach { playlist -> TextButton(onClick = { vm.addToRemotePlaylist(playlist.id, song); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text(playlist.title, Modifier.fillMaxWidth()) } }
            }
            if (vm.playlists.isEmpty() && remote.isEmpty()) Text("Create or save a playlist in Library first.", color = muted)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

@Composable
private fun MiniPlayer(vm: MusicViewModel, song: Song, open: () -> Unit) {
    Surface(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable(onClick = open), shape = RoundedCornerShape(16.dp), color = surface) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(song, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(if (vm.resolving) "Connecting…" else song.subtitle, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = vm::previous, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous song") }
                IconButton(onClick = vm::togglePlay, enabled = !vm.resolving, modifier = Modifier.size(44.dp)) { Icon(if (vm.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (vm.playing) "Pause" else "Play") }
                IconButton(onClick = vm::next, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.SkipNext, "Next song") }
            }
            LinearProgressIndicator(progress = { if (vm.duration > 0) (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
private fun FullPlayer(vm: MusicViewModel, close: () -> Unit) {
    val song = vm.current ?: return
    var panel by remember { mutableStateOf("Now playing") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().background(Brush.verticalGradient(listOf(accent(vm.theme).copy(alpha = .16f), background))).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = close) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
                    Text("NOW PLAYING", Modifier.weight(1f), fontSize = 11.sp, letterSpacing = 2.sp)
                    val liked = vm.favorites.any { it.id == song.id }
                    IconButton(onClick = { vm.toggleFavorite(song) }) { Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Toggle favorite", tint = accent(vm.theme)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("Now playing", "Lyrics", "Queue").forEach { name -> FilterChip(selected = panel == name, onClick = { panel = name }, label = { Text(name) }) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (panel) {
                        "Lyrics" -> LyricsPanel(vm)
                        "Queue" -> ReorderList(vm.queue, vm, vm::moveQueue, remove = vm::removeQueue, queue = true)
                        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { Art(song, Modifier.size(maxWidth.coerceAtMost(420.dp)).aspectRatio(1f), large = true) }
                            Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(song.subtitle, Modifier.padding(top = 6.dp, bottom = 8.dp), color = muted)
                            Text(vm.audioQuality, color = muted, style = MaterialTheme.typography.labelSmall)
                            if (!song.id.startsWith("content:") && !song.id.startsWith("file:")) TextButton(onClick = { vm.playVideo(song) }) { Icon(Icons.Rounded.MusicVideo, null, Modifier.size(18.dp)); Text("Music video") }
                        }
                    }
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
                if (vm.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                var dragging by remember(song.id) { mutableStateOf<Float?>(null) }
                Slider(value = dragging ?: vm.position.toFloat(), onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { vm.seek(it.toLong()) }; dragging = null }, valueRange = 0f..vm.duration.coerceAtLeast(1).toFloat(), enabled = vm.duration > 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(vm.position), color = Color.Gray, fontSize = 12.sp); Text(time(vm.duration), color = Color.Gray, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Control(Icons.Rounded.Shuffle, "Shuffle", vm.shuffled, vm::toggleShuffle)
                    Control(Icons.Rounded.SkipPrevious, "Previous song", action = vm::previous)
                    FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(64.dp), enabled = !vm.resolving) { Icon(if (vm.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (vm.playing) "Pause" else "Play", Modifier.size(34.dp)) }
                    Control(Icons.Rounded.SkipNext, "Next song", action = vm::next)
                    Control(if (vm.repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, when (vm.repeatMode) { 1 -> "Repeat song"; 2 -> "Repeat queue"; else -> "Repeat off" }, vm.repeatMode != 0, vm::cycleRepeat)
                }
            }
        }
    }
}

@Composable
private fun ReorderList(songs: List<Song>, vm: MusicViewModel, move: (Int, Int) -> Unit, remove: ((Int) -> Unit)? = null, more: Boolean = false, queue: Boolean = false) {
    var find by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var startCenter by remember { mutableFloatStateOf(0f) }
    val currentSongs by rememberUpdatedState(songs)
    val currentMove by rememberUpdatedState(move)
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        item { FindBox(find) { find = it }; Text("Drag the handle up or down", Modifier.padding(horizontal = 22.dp, vertical = 6.dp), color = muted, style = MaterialTheme.typography.bodySmall) }
        itemsIndexed(songs) { index, song ->
            if (find.isBlank() || (song.title + " " + song.subtitle).contains(find, true)) Column(
                Modifier.zIndex(if (draggedIndex == index) 1f else 0f)
                    .graphicsLayer { translationY = if (draggedIndex == index) dragOffset else 0f }
                    .background(if (draggedIndex == index) surface else Color.Transparent)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val indexNow by rememberUpdatedState(index)
                    Icon(Icons.Rounded.DragHandle, "Drag " + song.title, Modifier.padding(start = 8.dp).size(48.dp).pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                draggedIndex = indexNow
                                dragOffset = 0f
                                val row = state.layoutInfo.visibleItemsInfo.find { it.index == indexNow + 1 }
                                startCenter = row?.let { it.offset + it.size / 2f } ?: 0f
                            },
                            onDragCancel = { draggedIndex = -1; dragOffset = 0f },
                            onDragEnd = {
                                val from = draggedIndex
                                val rows = state.layoutInfo.visibleItemsInfo.filter { it.index > 0 && it.index <= currentSongs.size && it.size > 0 }
                                    .map { QueueDragRow(it.index - 1, it.offset, it.size) }
                                val to = queueDropIndex(rows, startCenter + dragOffset, from)
                                draggedIndex = -1; dragOffset = 0f
                                if (from in currentSongs.indices && to in currentSongs.indices && from != to) currentMove(from, to)
                            },
                            onDrag = { change, amount -> change.consume(); dragOffset += amount.y }
                        )
                    }, tint = if (draggedIndex == index) accent(vm.theme) else Color.Gray)
                    SongRow(song, vm, songs, Modifier.weight(1f), onPlay = if (queue) { { vm.playQueued(index) } } else null)
                }
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { move(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move " + song.title + " up", Modifier.size(18.dp)) }
                    IconButton(onClick = { move(index, index + 1) }, enabled = index < songs.lastIndex, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move " + song.title + " down", Modifier.size(18.dp)) }
                    if (remove != null) IconButton(onClick = { remove(index) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Remove " + song.title + " from list", Modifier.size(18.dp)) }
                }
            }
        }
        if (more && vm.canLoadMore) item { LoadMore(vm) }
    }
}

@Composable
private fun Control(icon: ImageVector, description: String, active: Boolean = false, action: () -> Unit) {
    IconButton(onClick = action) { Icon(icon, description, tint = if (active) MaterialTheme.colorScheme.primary else Color.White) }
}

@Composable
private fun LyricsPanel(vm: MusicViewModel) {
    val lyrics = vm.lyrics
    val active = lyrics.lines.indexOfLast { it.first <= vm.position }
    val state = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) state.animateScrollToItem((active - 1).coerceAtLeast(0)) }
    if (lyrics.lines.isNotEmpty()) LazyColumn(state = state, contentPadding = PaddingValues(vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(lyrics.lines.size) { i -> Text(lyrics.lines[i].second.ifBlank { "♪" }, Modifier.fillMaxWidth().clickable { vm.seek(lyrics.lines[i].first) }, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = if (i == active) MaterialTheme.colorScheme.primary else Color(0xFF827989)) }
        item { Text("Lyrics via LRCLib", color = Color.Gray, fontSize = 12.sp) }
    } else Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 30.dp)) { Text(lyrics.plain.ifBlank { lyrics.message.ifBlank { "No lyrics available" } }, fontSize = 22.sp, lineHeight = 34.sp, color = muted) }
}

@Composable
private fun SwitchSetting(title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { changed(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, color = muted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, changed)
    }
}

@Composable
private fun Settings(vm: MusicViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Make it yours", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Google account", style = MaterialTheme.typography.titleLarge)
        Text(if (vm.signedIn) "A Google session is saved. Open your library to check account access." else "Sign in to load your YouTube Music account feed and library. Local listening history also personalizes Home.", color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { context.startActivity(Intent(context, AccountActivity::class.java)) }) { Text(if (vm.signedIn) "Manage account" else "Sign in") }
            if (vm.signedIn) TextButton(onClick = vm::signOut) { Text("Sign out") }
        }
        HorizontalDivider(color = surface)
        Text("Listening", style = MaterialTheme.typography.titleLarge)
        SwitchSetting("Autoplay", "Continue with related music when the queue ends", vm.autoplay, vm::setAutoplay)
        SwitchSetting("Volume normalization", "Balance loudness between recordings", vm.normalization, vm::setNormalization)
        SwitchSetting("Skip non-music parts", "Use SponsorBlock segments for music videos", vm.sponsorBlock, vm::setSponsorBlock)
        SwitchSetting("Hide explicit songs", "Filter lists, search, queue and autoplay", vm.hideExplicit, vm::setHideExplicit)
        SwitchSetting("Restricted Mode", "Ask YouTube to filter mature content", vm.restricted, vm::setRestricted)
        SwitchSetting("Podcasts", "Show podcast search and library categories", vm.podcasts, vm::setPodcasts)
        Text("Playback speed · ${"%.2f".format(vm.speed)}×", style = MaterialTheme.typography.titleSmall)
        Slider(vm.speed, vm::setSpeed, valueRange = .5f..2f, steps = 5)
        Text("Pitch follows the speed, like a record", color = muted, style = MaterialTheme.typography.bodySmall)
        Text("Crossfade · ${"%.0f".format(vm.crossfadeSeconds)} seconds", style = MaterialTheme.typography.titleSmall)
        Slider(vm.crossfadeSeconds, vm::setCrossfade, valueRange = 0f..12f, steps = 11)
        OutlinedButton(onClick = vm::selectOutput, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Speaker, null); Text("Choose output device", Modifier.padding(start = 8.dp)) }
        Text(vm.audioQuality, color = muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = surface)
        SwitchSetting("10-band equalizer", "Adjust the sound for your headphones or speakers", vm.eqEnabled, vm::setEqEnabled)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Flat", "Bass boost", "Vocal", "Rock").forEach { name -> AssistChip(onClick = { vm.setEqPreset(name) }, label = { Text(name) }) } }
        if (vm.eqEnabled) {
            val labels = listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
            vm.eqBands.take(10).forEachIndexed { i, gain -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels[i], Modifier.width(58.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                Slider(gain, { vm.setEqBand(i, it) }, Modifier.weight(1f), valueRange = -12f..12f)
                Text("${"%.0f".format(gain)} dB", Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall)
            } }
        }
        HorizontalDivider(color = surface)
        Text("Accent", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Rose", "Iris", "Mint", "Amber").forEach { name -> FilterChip(selected = vm.theme == name, onClick = { vm.chooseTheme(name) }, label = { Text(name) }, leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(accent(name))) }) }
        }
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text("ytm-rsk for Android\nBased on grapedjuice/ytm-rs · GPL-3.0\nLyrics by LRCLib\nUnofficial client, not affiliated with Google.", color = muted, lineHeight = 24.sp)
    }
}

internal fun time(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
