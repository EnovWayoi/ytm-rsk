package dev.enov.ytm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal fun Song.toMediaItem(recommendations: Boolean = false, queueToken: String = ""): MediaItem {
    val uri = if (id.startsWith("content:") || id.startsWith("file:")) id else "ytm://track/$id"
    val extras = Bundle().apply { putString("song", json().toString()); putBoolean("recommendations", recommendations); putString("queueToken", queueToken) }
    return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title).setArtist(subtitle).setArtworkUri(art.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse))
        .setExtras(extras).build()).build()
}

internal fun MediaItem.song(): Song? = runCatching {
    Song.from(JSONObject(mediaMetadata.extras?.getString("song") ?: return null))
}.getOrNull()

object PlaybackDiagnostics {
    private val mutableQuality = MutableStateFlow("No song playing")
    val quality = mutableQuality.asStateFlow()
    internal fun resolved(stream: ResolvedStream) {
        val codec = when { stream.mime.contains("opus", true) || stream.mime.contains("webm", true) -> "Opus"; stream.mime.contains("mpegurl", true) -> "AAC · HLS"; else -> "AAC" }
        mutableQuality.value = codec + if (stream.bitrate > 0) " · ${stream.bitrate / 1000} kbps" else " · source quality"
    }
}

private class StreamResolver(private val repository: MusicRepository, private val scope: CoroutineScope) {
    private data class Entry(val at: Long, val stream: Deferred<ResolvedStream>)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val hlsFallback = ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()
    suspend fun resolve(song: Song): ResolvedStream {
        val entry = mutex.withLock {
            entries[song.id]?.takeIf { SystemClock.elapsedRealtime() - it.at < 600_000 } ?: Entry(SystemClock.elapsedRealtime(), scope.async(Dispatchers.IO) { repository.resolveStream(song, forceHls = song.id in hlsFallback) }).also {
                entries[song.id] = it
                if (entries.size > 20) entries.entries.filter { e -> e.key != song.id }.sortedBy { e -> e.value.at }.take(entries.size - 20).forEach { e -> entries.remove(e.key) }
            }
        }
        return try { entry.stream.await() } catch (e: Exception) { entries.remove(song.id, entry); throw e }
    }
    fun peek(id: String): ResolvedStream? = entries[id]?.stream?.takeIf { it.isCompleted && !it.isCancelled }?.let { runCatching { runBlocking { it.await() } }.getOrNull() }
    fun invalidate(id: String) { entries.remove(id) }
    fun useHlsFallback(id: String): Boolean { entries.remove(id); return hlsFallback.add(id) }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class ResolvingSource(private val item: MediaItem, private val sources: DefaultMediaSourceFactory, private val resolver: StreamResolver, private val onResolved: (String, ResolvedStream) -> Unit) : CompositeMediaSource<Int>() {
    private var child: MediaSource? = null
    private var failure: IOException? = null
    private var sourceScope: CoroutineScope? = null
    override fun getMediaItem() = item
    override fun prepareSourceInternal(listener: TransferListener?) {
        super.prepareSourceInternal(listener)
        val scope = CoroutineScope(SupervisorJob() + Handler(Looper.myLooper()!!).asCoroutineDispatcher())
        sourceScope = scope
        scope.launch {
            try {
                val song = item.song() ?: error("Song information is missing")
                val stream = resolver.resolve(song)
                onResolved(song.id, stream)
                child = sources.createMediaSource(item.buildUpon().setUri(stream.url).setMimeType(stream.mime).build())
                prepareChildSource(0, child!!)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = IOException(e.message ?: "Could not resolve audio", e) }
        }
    }
    override fun maybeThrowSourceInfoRefreshError() { failure?.let { throw it }; super.maybeThrowSourceInfoRefreshError() }
    override fun onChildSourceInfoRefreshed(id: Int, source: MediaSource, timeline: Timeline) { refreshSourceInfo(timeline) }
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod = checkNotNull(child).createPeriod(id, allocator, startPositionUs)
    override fun releasePeriod(period: MediaPeriod) { child?.releasePeriod(period) }
    override fun releaseSourceInternal() { sourceScope?.cancel(); sourceScope = null; super.releaseSourceInternal(); child = null; failure = null }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class ListeningSourceFactory(private val default: DefaultMediaSourceFactory, private val resolver: StreamResolver, private val onResolved: (String, ResolvedStream) -> Unit) : MediaSource.Factory {
    override fun createMediaSource(item: MediaItem): MediaSource = if (item.localConfiguration?.uri?.scheme == "ytm") ResolvingSource(item, default, resolver, onResolved) else default.createMediaSource(item)
    override fun getSupportedTypes() = default.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory { default.setDrmSessionManagerProvider(provider); return this }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory { default.setLoadErrorHandlingPolicy(policy); return this }
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var repository: MusicRepository
    private lateinit var cache: SimpleCache
    private lateinit var resolver: StreamResolver
    private lateinit var cacheFactory: CacheDataSource.Factory
    private lateinit var sources: ListeningSourceFactory
    private val effects = ListeningAudioProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prefs: android.content.SharedPreferences? = null
    private var prefetchJob: Job? = null
    private var radioJob: Job? = null
    private var radioRequestToken = ""
    private val recommendedQueues = LinkedHashSet<String>()
    private var sponsorJob: Job? = null
    private var segments: List<Pair<Long, Long>> = emptyList()
    private var loudness = 0f
    private var tail: ExoPlayer? = null
    private var tailId: String? = null
    private var fadeStarted = 0L
    private var fadeLength = 0L
    private var fadedFrom: String? = null
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scope.launch { applySettings() } }

    override fun onCreate() {
        super.onCreate()
        repository = MusicRepository(this)
        prefs = getSharedPreferences("music", Context.MODE_PRIVATE).also { it.registerOnSharedPreferenceChangeListener(settingsListener) }
        cache = SimpleCache(File(cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(120L * 1024 * 1024), StandaloneDatabaseProvider(this))
        val http = DefaultHttpDataSource.Factory().setUserAgent(MusicRepository.PLAYER_UA).setConnectTimeoutMs(15_000).setReadTimeoutMs(25_000)
        cacheFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DefaultDataSource.Factory(this, http)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        resolver = StreamResolver(repository, scope)
        val mainHandler = Handler(mainLooper)
        sources = ListeningSourceFactory(DefaultMediaSourceFactory(cacheFactory), resolver) { id, stream -> mainHandler.post { if (player.currentMediaItem?.mediaId == id) { loudness = stream.loudnessDb; PlaybackDiagnostics.resolved(stream); applyEffects(); player.currentMediaItem?.song()?.let { if (stream.videoId == it.id) loadSegments(it) else { sponsorJob?.cancel(); segments = emptyList() } } } } }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setAudioProcessors(arrayOf(effects)).build()
        }
        player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true); player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val song = item?.song() ?: return
                if (fadeStarted == 0L) clearTail()
                loudness = resolver.peek(song.id)?.loudnessDb ?: 0f; resolver.peek(song.id)?.let(PlaybackDiagnostics::resolved); applyEffects()
                repository.remember(song)
                prefetchNext(); sponsorJob?.cancel(); segments = emptyList(); maybeAutoplay()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { prefetchNext(); maybeAutoplay() }
            override fun onPlayerError(error: PlaybackException) {
                clearTail()
                val item = player.currentMediaItem ?: return
                val song = item.song() ?: return
                if (!song.local && resolver.useHlsFallback(song.id)) {
                    val index = player.currentMediaItemIndex; val position = player.currentPosition; val resume = player.playWhenReady
                    player.replaceMediaItem(index, item); player.prepare(); player.seekTo(index, position); if (resume) player.play() else player.pause()
                } else resolver.invalidate(song.id)
            }
            override fun onRepeatModeChanged(repeatMode: Int) { maybeAutoplay() }
        })
        applySettings()
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        scope.launch { while (isActive) { tick(); delay(100) } }
    }

    private fun applyEffects() { effects.configureEffects(repository.bool("eqEnabled", false), (0..9).map { repository.number("eqBand$it", 0f) }, repository.bool("normalization", true), loudness) }
    private fun applySettings() {
        if (!::player.isInitialized) return
        val speed = repository.number("speed", 1f).coerceIn(.5f, 2f)
        player.playbackParameters = PlaybackParameters(speed, speed); tail?.playbackParameters = PlaybackParameters(speed, speed); applyEffects()
        val outputId = repository.number("outputDevice", -1f).toInt()
        val outputs = (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        player.setPreferredAudioDevice(outputs.find { it.id == outputId })
        if (repository.number("crossfade", 0f) <= 0) clearTail()
        if (!repository.bool("sponsorBlock", false)) segments = emptyList()
    }

    private fun prefetchNext() {
        if (!::player.isInitialized) return
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET) return
        val song = player.getMediaItemAt(index).song() ?: return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                val stream = resolver.resolve(song)
                if (stream.mime.contains("mpegurl", true)) {
                    val playlist = DataSourceInputStream(cacheFactory.createDataSource(), DataSpec(android.net.Uri.parse(stream.url))).use { it.readBytes().toString(Charsets.UTF_8) }
                    val first = playlist.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                    if (first != null) CacheWriter(cacheFactory.createDataSource(), DataSpec.Builder().setUri(java.net.URL(java.net.URL(stream.url), first).toString()).build(), null, null).cache()
                } else CacheWriter(cacheFactory.createDataSource(), DataSpec.Builder().setUri(stream.url).setLength(256 * 1024L).build(), null, null).cache()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun maybeAutoplay() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return
        if (!extras.getBoolean("recommendations")) return
        val token = extras.getString("queueToken").orEmpty()
        if (token.isBlank()) return
        val initial = token !in recommendedQueues
        if ((!initial && (!repository.bool("autoplay", true) || player.repeatMode != Player.REPEAT_MODE_OFF)) || player.mediaItemCount - player.currentMediaItemIndex > 2) return
        if (radioJob?.isActive == true && radioRequestToken == token) return
        radioJob?.cancel(); radioRequestToken = token
        val seed = player.currentMediaItem?.song() ?: return
        radioJob = scope.launch {
            try {
                val tracks = repository.radio(seed)
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val additions = tracks.filter { it.playable && it.id !in existing && (!repository.bool("hideExplicit", false) || !it.explicit) }.take(25)
                if (player.currentMediaItem?.mediaMetadata?.extras?.getString("queueToken") == token && player.currentMediaItem?.mediaId == seed.id) {
                    recommendedQueues.add(token)
                    while (recommendedQueues.size > 50) recommendedQueues.remove(recommendedQueues.first())
                    val ended = player.playbackState == Player.STATE_ENDED
                    if (additions.isNotEmpty()) player.addMediaItems(additions.map { it.toMediaItem(true, token) })
                    if (ended && additions.isNotEmpty()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun loadSegments(song: Song) {
        segments = emptyList(); sponsorJob?.cancel()
        if (!repository.bool("sponsorBlock", false) || !song.musicVideo) return
        sponsorJob = scope.launch {
            try {
                val result = repository.sponsorSegments(song.id)
                if (player.currentMediaItem?.mediaId == song.id) segments = result
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun tick() {
        if (!player.isPlaying) { if (fadeStarted > 0) clearTail(); return }
        if (repository.bool("sponsorBlock", false)) segments.firstOrNull { player.currentPosition >= it.first && player.currentPosition < it.second }?.let { player.seekTo(it.second) }
        val fadeMs = (repository.number("crossfade", 0f).coerceIn(0f, 12f) * 1000).toLong()
        if (fadeStarted > 0) {
            if (player.playbackState != Player.STATE_READY) return
            val fraction = ((SystemClock.elapsedRealtime() - fadeStarted).toFloat() / fadeLength.coerceAtLeast(1)).coerceIn(0f, 1f)
            player.volume = fraction; tail?.volume = 1 - fraction
            if (fraction >= 1) clearTail()
            return
        }
        val duration = player.duration
        if (fadeMs <= 0 || duration <= 0 || player.repeatMode == Player.REPEAT_MODE_ONE || player.nextMediaItemIndex == C.INDEX_UNSET) return
        val remaining = duration - player.currentPosition
        if (remaining > fadeMs + 8000) fadedFrom = null
        val currentId = player.currentMediaItem?.mediaId ?: return
        if (remaining in 1..(fadeMs + 8000) && tailId != currentId) {
            clearTail(); tailId = currentId
            tail = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().also {
                it.setAudioAttributes(player.audioAttributes, false); it.volume = 0f; it.playbackParameters = player.playbackParameters
                it.setMediaItem(player.currentMediaItem!!, player.currentPosition); it.prepare()
            }
        }
        if (remaining in 1..fadeMs && tail?.playbackState == Player.STATE_READY && fadedFrom != currentId) {
            fadedFrom = currentId; fadeLength = remaining; fadeStarted = SystemClock.elapsedRealtime()
            android.util.Log.i("YtmPlayback", "Crossfade started")
            tail?.seekTo(player.currentPosition); tail?.volume = 1f; tail?.play(); player.volume = 0f; player.seekToNextMediaItem()
        }
    }
    private fun clearTail() { if (fadeStarted > 0) android.util.Log.i("YtmPlayback", "Crossfade ended"); tail?.release(); tail = null; tailId = null; fadeStarted = 0; if (::player.isInitialized) player.volume = 1f }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        prefs?.unregisterOnSharedPreferenceChangeListener(settingsListener)
        scope.cancel(); clearTail(); session?.run { player.release(); release() }; session = null
        cache.release(); super.onDestroy()
    }
}
