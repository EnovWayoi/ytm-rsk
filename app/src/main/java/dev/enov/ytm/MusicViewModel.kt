package dev.enov.ytm

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    val repository = MusicRepository(app)
    var items by mutableStateOf<List<Song>>(emptyList()); private set
    var sections by mutableStateOf<List<FeedSection>>(emptyList()); private set
    var chips by mutableStateOf<List<MoodChip>>(emptyList()); private set
    var category by mutableStateOf("All"); private set
    var queue by mutableStateOf<List<Song>>(emptyList()); private set
    var current by mutableStateOf<Song?>(null); private set
    var busy by mutableStateOf(false); private set
    var resolving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false); private set
    var position by mutableStateOf(0L); private set
    var duration by mutableStateOf(0L); private set
    var lyrics by mutableStateOf(Lyrics()); private set
    var favorites by mutableStateOf(repository.favorites); private set
    var history by mutableStateOf(repository.history); private set
    var savedCollections by mutableStateOf(repository.savedCollections); private set
    var playlists by mutableStateOf(repository.playlists); private set
    var uploads by mutableStateOf(repository.uploads); private set
    var downloads by mutableStateOf(repository.downloads); private set
    var signedIn by mutableStateOf(repository.signedIn); private set
    var theme by mutableStateOf(repository.theme); private set
    var shuffled by mutableStateOf(false); private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF); private set
    var pageTitle by mutableStateOf("Your daily soundtrack"); private set
    var audioQuality by mutableStateOf("No song playing"); private set
    var downloadBusy by mutableStateOf(false); private set
    var downloadStatus by mutableStateOf(""); private set
    var normalization by mutableStateOf(repository.bool("normalization", true)); private set
    var speed by mutableStateOf(repository.number("speed", 1f)); private set
    var crossfadeSeconds by mutableStateOf(repository.number("crossfade", 0f)); private set
    var autoplay by mutableStateOf(repository.bool("autoplay", true)); private set
    var sponsorBlock by mutableStateOf(repository.bool("sponsorBlock", false)); private set
    var hideExplicit by mutableStateOf(repository.bool("hideExplicit", false)); private set
    var restricted by mutableStateOf(repository.bool("restricted", false)); private set
    var podcasts by mutableStateOf(repository.bool("podcasts", false)); private set
    var eqEnabled by mutableStateOf(repository.bool("eqEnabled", false)); private set
    var eqBands by mutableStateOf((0..9).map { repository.number("eqBand$it", 0f) }); private set
    var canLoadMore by mutableStateOf(false); private set
    var activePlaylistId by mutableStateOf(""); private set
    var libraryCategory by mutableStateOf("Favorites"); private set
    val activePlaylistEditable get() = activePlaylistId.startsWith("local:") || (signedIn && activePlaylistId.isNotBlank() && items.any { it.setVideoId.isNotBlank() })
    private var controller: MediaController? = null
    private var loadJob: Job? = null
    private var lyricJob: Job? = null
    private var downloadNoticeJob: Job? = null
    private var generation = 0
    private var page = FeedPage(emptyList())
    private var activeBrowse = "FEmusic_home"
    private var activeQuery = ""
    private var activeFilter = "Songs"
    private var activeLibrary = ""
    private var activePlaylist = ""
    private var playbackOrigin = PlaybackOrigin.HOME
    private var dead = false
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()

    init {
        future.addListener({
            if (dead) return@addListener
            runCatching { future.get() }.onSuccess { player ->
                controller = player
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlaybackStateChanged(state: Int) { resolving = state == Player.STATE_BUFFERING; if (state == Player.STATE_READY) error = null }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) { changed(item) }
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) { syncQueue() }
                    override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffled = enabled }
                    override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
                    override fun onPlayerError(e: PlaybackException) { error = "${e.cause?.message ?: e.errorCodeName}. Tap the song to retry."; resolving = false }
                })
                playing = player.isPlaying; repeatMode = player.repeatMode; shuffled = player.shuffleModeEnabled
                changed(player.currentMediaItem); syncQueue()
                viewModelScope.launch { while (isActive) { position = player.currentPosition.coerceAtLeast(0); duration = player.duration.coerceAtLeast(0); delay(250) } }
            }.onFailure { error = "Could not connect to the audio service: ${it.message}" }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch { PlaybackDiagnostics.quality.collect { audioQuality = it } }
        home()
    }

    private fun changed(item: MediaItem?) {
        val song = item?.song()
        current = song; position = 0; duration = song?.durationMs ?: 0; refreshLocal(); lyricJob?.cancel()
        if (song?.local == true) audioQuality = "Offline audio"
        if (song == null) return
        lyrics = Lyrics(message = "Finding lyrics…")
        lyricJob = viewModelScope.launch {
            try { lyrics = repository.lyrics(song) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { lyrics = Lyrics(message = "Lyrics are unavailable for this song") }
        }
    }
    private fun syncQueue() { controller?.let { p -> queue = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).song() } } }
    fun refreshLocal() {
        repository.reloadLocal(); favorites = repository.favorites; history = repository.history; savedCollections = repository.savedCollections
        playlists = repository.playlists; uploads = repository.uploads; downloads = repository.downloads; signedIn = repository.signedIn
    }

    fun home() { playbackOrigin = PlaybackOrigin.HOME; category = "All"; activeLibrary = ""; activeQuery = ""; activePlaylist = ""; activePlaylistId = ""; activeBrowse = "FEmusic_home"; load("Your daily soundtrack") { repository.personalized() } }
    fun explore() { playbackOrigin = PlaybackOrigin.EXPLORE; category = "All"; activeLibrary = ""; activeQuery = ""; activePlaylist = ""; activePlaylistId = ""; activeBrowse = "FEmusic_explore"; load("Find your next favorite") { repository.feed(activeBrowse) } }
    fun browse(id: String, title: String, params: String? = null) {
        playbackOrigin = if (id == "FEmusic_home") PlaybackOrigin.HOME else PlaybackOrigin.COLLECTION
        val kind = when { id.startsWith("local:") || id.startsWith("VL") || id.startsWith("PL") || id.startsWith("RD") -> "playlist"; id.startsWith("MPRE") -> "album"; id.startsWith("UC") || id.startsWith("MPLA") -> "artist"; else -> "" }
        if (kind.isNotEmpty()) { repository.recordCollection((items + savedCollections + playlists.map { it.card() }).find { it.id == id } ?: Song(id, title, "", "", kind)); refreshLocal() }
        category = "All"; activeLibrary = ""; activeQuery = ""; activeBrowse = id; activePlaylist = if (id.startsWith("local:")) id else ""
        activePlaylistId = if (id.startsWith("local:") || id.startsWith("VL") || id.startsWith("PL")) id else ""
        load(title) { repository.feed(id, params) }
    }
    fun selectMood(chip: MoodChip) = browse(chip.browseId.ifBlank { "FEmusic_home" }, chip.title, chip.params)
    @JvmName("updateCategory") fun setCategory(value: String) { category = value; applyFilter() }
    private fun applyFilter() {
        fun matches(s: Song): Boolean = repository.allowed(s) && when (category) {
            "Songs" -> s.playable && s.kind != "episode"
            "Albums" -> s.kind == "album"
            "Artists" -> s.kind == "artist"
            "Playlists" -> s.kind == "playlist" || (s.kind == "collection" && (s.id.startsWith("VL") || s.id.startsWith("PL")))
            "Podcasts" -> s.kind == "episode" || s.kind == "show"
            else -> true
        }
        items = page.items.filter(::matches)
        sections = page.sections.map { it.copy(items = it.items.filter(::matches)) }.filter { it.items.isNotEmpty() }
        if (sections.isNotEmpty()) {
            val grouped = sections.flatMap { it.items }.map { "${it.id}:${it.setVideoId}" }.toSet()
            val remaining = items.filterNot { "${it.id}:${it.setVideoId}" in grouped }
            if (remaining.isNotEmpty()) sections = listOf(FeedSection(if (remaining.all { it.playable }) "Songs" else "Music", remaining)) + sections
        }
        chips = page.chips; canLoadMore = page.continuation != null
    }
    fun search(query: String, filter: String) {
        if (query.isBlank()) return
        playbackOrigin = PlaybackOrigin.SEARCH
        category = "All"; activeLibrary = ""; activePlaylist = ""; activePlaylistId = ""; activeQuery = query; activeFilter = filter
        load("Results for “${query.trim()}”") { repository.searchPage(query, filter) }
    }
    private fun load(title: String, block: suspend () -> FeedPage) {
        loadJob?.cancel(); val ticket = ++generation
        loadJob = viewModelScope.launch {
            busy = true; error = null; pageTitle = title; page = FeedPage(emptyList()); applyFilter()
            try { val result = block(); if (ticket == generation) { page = result; applyFilter() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == generation) error = e.message ?: "Could not load music" }
            finally { if (ticket == generation) busy = false }
        }
    }
    fun loadMore() {
        val continuation = page.continuation ?: return
        if (busy) return
        val before = page; val ticket = ++generation
        loadJob = viewModelScope.launch {
            busy = true
            try {
                val next = if (activeQuery.isNotBlank()) repository.searchPage(activeQuery, activeFilter, continuation) else repository.feed(activeBrowse, continuation = continuation)
                if (ticket == generation) {
                    val moreSections = next.sections.ifEmpty { if (before.sections.isNotEmpty() && next.items.isNotEmpty()) listOf(FeedSection("More", next.items)) else emptyList() }
                    page = next.copy(items = (before.items + next.items).distinctBy { "${it.id}:${it.setVideoId}" }, sections = before.sections + moreSections, chips = before.chips); applyFilter()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { if (ticket == generation) busy = false }
        }
    }
    fun openLibrary(name: String) { libraryCategory = if (name == "Downloads") "Offline songs" else name; playbackOrigin = PlaybackOrigin.LIBRARY; category = "All"; activeQuery = ""; activePlaylist = ""; activePlaylistId = ""; activeLibrary = if (name == "Offline songs") "Downloads" else name; refreshLocal(); load(libraryCategory) { repository.library(activeLibrary) } }
    fun openPlaylist(playlist: UserPlaylist) { activePlaylist = playlist.id; browse(playlist.id, playlist.title) }

    fun select(song: Song, from: List<Song>) {
        if (!song.playable) { repository.recordCollection(song); refreshLocal(); browse(song.id, song.title); return }
        val player = controller ?: run { error = "Audio is starting. Try again in a moment."; return }
        if (!repository.allowed(song)) { error = "This song is hidden by your listening settings"; return }
        val recommend = recommendsSongs(playbackOrigin, song.local)
        val tracks = if (recommend) listOf(song) else from.filter { it.playable && repository.allowed(it) }.ifEmpty { listOf(song) }
        val index = tracks.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val token = java.util.UUID.randomUUID().toString()
        error = null; player.setMediaItems(tracks.map { it.toMediaItem(recommend, token) }, index, 0); player.prepare(); player.play(); syncQueue()
    }
    fun enqueue(song: Song) { if (song.playable && repository.allowed(song)) { controller?.let { p -> val extras = p.currentMediaItem?.mediaMetadata?.extras; p.addMediaItem(song.toMediaItem(extras?.getBoolean("recommendations") == true, extras?.getString("queueToken").orEmpty())) }; syncQueue() } }
    fun playQueued(index: Int) { controller?.let { p -> if (index in 0 until p.mediaItemCount) { p.seekTo(index, 0); p.prepare(); p.play() } } }
    fun moveQueue(from: Int, to: Int) { if (from in queue.indices && to in queue.indices) { controller?.moveMediaItem(from, to); syncQueue() } }
    fun removeQueue(index: Int) { if (index in queue.indices) { controller?.removeMediaItem(index); syncQueue() } }
    fun radio(song: Song) { val before = playbackOrigin; playbackOrigin = PlaybackOrigin.RADIO; select(song, listOf(song)); playbackOrigin = before }
    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } }
    fun seek(ms: Long) { controller?.seekTo(ms.coerceIn(0, duration.coerceAtLeast(0))); position = ms.coerceIn(0, duration.coerceAtLeast(0)) }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; shuffled = it.shuffleModeEnabled } }
    fun cycleRepeat() { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; repeatMode = it.repeatMode } }
    fun toggleRepeat() = cycleRepeat()
    fun toggleFavorite(song: Song) {
        repository.toggle(song); refreshLocal()
        if (signedIn && !song.local) viewModelScope.launch { try { repository.syncLike(song, favorites.any { it.id == song.id }) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Saved locally; Google sync failed: ${e.message}" } }
    }
    fun toggleCollection(song: Song) { repository.toggleCollection(song); refreshLocal(); if (signedIn && song.kind in listOf("album", "playlist")) action { repository.syncCollection(song, savedCollections.any { it.id == song.id }) } }
    fun createPlaylist(title: String) { runCatching { repository.createPlaylist(title) }.onFailure { error = it.message }; refreshLocal(); if (activeLibrary == "Playlists") openLibrary("Playlists") }
    fun renamePlaylist(id: String, title: String) { runCatching { repository.renamePlaylist(id, title) }.onFailure { error = it.message }; refreshLocal() }
    fun deletePlaylist(id: String) { repository.deletePlaylist(id); refreshLocal(); if (activeLibrary == "Playlists") openLibrary("Playlists") }
    fun addToPlaylist(id: String, song: Song) { if (!id.startsWith("local:")) { addToRemotePlaylist(id, song); return }; runCatching { repository.addToPlaylist(id, song) }.onFailure { error = it.message }; refreshLocal() }
    fun removeFromPlaylist(id: String, index: Int) { repository.removeFromPlaylist(id, index); refreshLocal(); playlists.find { it.id == id }?.let(::openPlaylist) }
    fun movePlaylistSong(id: String, from: Int, to: Int) { repository.movePlaylistSong(id, from, to); refreshLocal(); if (activePlaylist == id) { page = FeedPage(playlists.find { it.id == id }?.songs.orEmpty()); applyFilter() } }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "The request failed" } } }
    fun openRemotePlaylist(song: Song) { repository.recordCollection(song); browse(song.id, song.title) }
    fun createRemotePlaylist(title: String) = action { val song = repository.createRemotePlaylist(title); repository.toggleCollection(song); refreshLocal(); browse(song.id, song.title) }
    fun addToRemotePlaylist(id: String, song: Song) = action { repository.addToRemotePlaylist(id, song) }
    fun removeRemotePlaylistSong(song: Song) { val id = activePlaylistId; action { repository.removeRemotePlaylistSong(id, song.id, song.setVideoId); browse(id, pageTitle) } }
    fun moveRemotePlaylistSong(from: Int, to: Int) {
        val id = activePlaylistId
        if (from !in items.indices || to !in items.indices || from == to) return
        val moved = items[from]
        val reordered = items.toMutableList().also { it.add(to, it.removeAt(from)) }
        val successor = reordered.getOrNull(to + 1)?.setVideoId
        action { repository.moveRemotePlaylistSong(id, moved.setVideoId, successor); if (activePlaylistId == id) { page = page.copy(items = reordered); applyFilter() } }
    }
    fun deleteRemotePlaylist(song: Song) = action { repository.deleteRemotePlaylist(song.id); refreshLocal(); openLibrary("Playlists") }
    fun importAudio(uri: Uri) { viewModelScope.launch { try { withContext(Dispatchers.IO) { repository.importAudio(uri) }; refreshLocal(); openLibrary("Uploads") } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }
    fun dismissDownloadNotice() { downloadNoticeJob?.cancel(); downloadStatus = "" }
    private fun showDownloadNotice(message: String) {
        downloadNoticeJob?.cancel(); downloadStatus = message
        downloadNoticeJob = viewModelScope.launch { delay(5000); downloadStatus = "" }
    }
    fun uploadToAccount(uri: Uri) { viewModelScope.launch { downloadNoticeJob?.cancel(); downloadBusy = true; downloadStatus = "Uploading to YouTube Music…"; try { repository.uploadToAccount(uri); showDownloadNotice("Upload complete"); refreshLocal(); openLibrary("Uploads") } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message; showDownloadNotice("Upload failed") } finally { downloadBusy = false } } }
    fun download(song: Song) { if (downloadBusy) return; viewModelScope.launch { downloadNoticeJob?.cancel(); downloadBusy = true; downloadStatus = "Downloading ${song.title}…"; try { repository.download(song); refreshLocal(); showDownloadNotice("Saved in Library → Offline songs"); if (activeLibrary == "Downloads") { page = FeedPage(downloads); applyFilter() } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message; showDownloadNotice("Download failed") } finally { downloadBusy = false } } }
    fun removeDownload(song: Song) { runCatching { repository.removeDownload(song) }.onFailure { error = it.message }; refreshLocal(); if (activeLibrary == "Downloads") openLibrary("Downloads") }
    fun signOut() { repository.clearAccount(); refreshLocal(); home() }
    fun chooseTheme(name: String) { theme = name; repository.theme = name }
    @JvmName("updateNormalization") fun setNormalization(value: Boolean) { normalization = value; repository.setBool("normalization", value) }
    @JvmName("updateSpeed") fun setSpeed(value: Float) { speed = value.coerceIn(.5f, 2f); repository.setNumber("speed", speed) }
    fun setCrossfade(value: Float) { crossfadeSeconds = value.coerceIn(0f, 12f); repository.setNumber("crossfade", crossfadeSeconds) }
    @JvmName("updateAutoplay") fun setAutoplay(value: Boolean) { autoplay = value; repository.setBool("autoplay", value) }
    @JvmName("updateSponsorBlock") fun setSponsorBlock(value: Boolean) { sponsorBlock = value; repository.setBool("sponsorBlock", value) }
    @JvmName("updateHideExplicit") fun setHideExplicit(value: Boolean) { hideExplicit = value; repository.setBool("hideExplicit", value); applyFilter(); if (value) controller?.let { p -> for (i in p.mediaItemCount - 1 downTo 0) if (p.getMediaItemAt(i).song()?.explicit == true) p.removeMediaItem(i) }; syncQueue() }
    @JvmName("updateRestricted") fun setRestricted(value: Boolean) { restricted = value; repository.setBool("restricted", value) }
    @JvmName("updatePodcasts") fun setPodcasts(value: Boolean) { podcasts = value; repository.setBool("podcasts", value); applyFilter() }
    @JvmName("updateEqEnabled") fun setEqEnabled(value: Boolean) { eqEnabled = value; repository.setBool("eqEnabled", value) }
    fun setEqBand(index: Int, value: Float) { if (index !in 0..9) return; eqBands = eqBands.toMutableList().also { it[index] = value.coerceIn(-12f, 12f) }; repository.setNumber("eqBand$index", eqBands[index]) }
    fun setEqPreset(name: String) {
        val gains = when (name) { "Bass boost" -> listOf(7f,6f,4f,2f,0f,0f,0f,0f,0f,0f); "Vocal" -> listOf(-3f,-2f,-1f,0f,3f,5f,4f,2f,0f,-1f); "Rock" -> listOf(5f,4f,2f,0f,-2f,-1f,2f,4f,5f,4f); else -> List(10) { 0f } }
        gains.forEachIndexed(::setEqBand); setEqEnabled(name != "Off")
    }
    fun selectOutput() { getApplication<Application>().startActivity(Intent(getApplication(), OutputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun playVideo(song: Song) { controller?.pause(); getApplication<Application>().startActivity(Intent(getApplication(), VideoActivity::class.java).putExtra("song", song.json().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    override fun onCleared() { dead = true; MediaController.releaseFuture(future); super.onCleared() }
}
