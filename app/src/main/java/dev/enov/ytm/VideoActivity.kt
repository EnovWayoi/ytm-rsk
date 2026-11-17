package dev.enov.ytm

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import org.json.JSONObject

@androidx.annotation.OptIn(UnstableApi::class)
class VideoActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); fitsSystemWindows = true }
        val video = PlayerView(this)
        layout.addView(video, FrameLayout.LayoutParams(-1, -1))
        val status = TextView(this).apply { setTextColor(Color.WHITE); text = "Opening music video…"; setPadding(24, 24, 24, 24) }
        layout.addView(status); setContentView(layout)
        scope.launch {
            try {
                val song = Song.from(JSONObject(intent.getStringExtra("song") ?: error("No song selected")))
                val repository = MusicRepository(this@VideoActivity)
                val stream = repository.resolveStream(song, video = true)
                val data = DefaultHttpDataSource.Factory().setUserAgent(MusicRepository.PLAYER_UA)
                val engine = ExoPlayer.Builder(this@VideoActivity).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build()
                player = engine; video.player = engine
                engine.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { status.text = "Video unavailable: ${error.cause?.message ?: error.errorCodeName}" }
                })
                engine.setMediaItem(MediaItem.Builder().setUri(stream.url).setMimeType(stream.mime).build()); engine.prepare(); engine.play(); status.text = ""
                if (repository.bool("sponsorBlock", false)) {
                    val segments = repository.sponsorSegments(song.id)
                    while (isActive) { if (engine.isPlaying) segments.firstOrNull { engine.currentPosition >= it.first && engine.currentPosition < it.second }?.let { engine.seekTo(it.second) }; delay(250) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { status.text = e.message ?: "The video could not be opened" }
        }
    }
    override fun onStop() { player?.pause(); super.onStop() }
    override fun onDestroy() { scope.cancel(); player?.release(); player = null; super.onDestroy() }
}
