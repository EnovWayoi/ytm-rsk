package dev.enov.ytm

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

internal fun highResolutionArt(raw: String): String {
    val host = runCatching { java.net.URI(raw).host }.getOrNull().orEmpty()
    if (!(host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com"))) return raw
    val marker = raw.lastIndexOf('=')
    if (marker < 0) return raw
    val size = raw.substring(marker + 1).split('-').joinToString("-") { part ->
        if (Regex("[wh]\\d+").matches(part)) "${part.first()}1200" else part
    }
    return raw.substring(0, marker + 1) + size
}

data class Song(val id: String, val title: String, val subtitle: String, val art: String, val kind: String = "song", val explicit: Boolean = false, val durationMs: Long = 0, val artistId: String = "", val albumId: String = "", val playlistId: String = "", val musicVideo: Boolean = false, val setVideoId: String = "", val artistTitle: String = "", val albumTitle: String = "") {
    val playable get() = kind in listOf("song", "episode", "upload", "download")
    val local get() = id.startsWith("file:") || id.startsWith("content:")
    val artist get() = artistTitle.ifBlank { subtitle.split(" • ", " · ", "\u0007").firstOrNull().orEmpty().trim() }
    fun json() = JSONObject().put("id", id).put("title", title).put("subtitle", subtitle).put("art", art).put("kind", kind).put("explicit", explicit).put("durationMs", durationMs).put("artistId", artistId).put("albumId", albumId).put("playlistId", playlistId).put("musicVideo", musicVideo).put("setVideoId", setVideoId).put("artistTitle", artistTitle).put("albumTitle", albumTitle)
    companion object {
        fun from(j: JSONObject): Song {
            val subtitle = j.optString("subtitle")
            val albumId = j.optString("albumId")
            val albumTitle = j.optString("albumTitle").ifBlank {
                if (albumId.isNotBlank()) subtitle.split('\u2022', '\u00b7').getOrNull(1)?.trim().orEmpty().takeUnless { Regex("\\d+:\\d{2}").matches(it) }.orEmpty() else ""
            }
            return Song(j.getString("id"), j.optString("title"), subtitle, highResolutionArt(j.optString("art")), j.optString("kind", "song"), j.optBoolean("explicit"), j.optLong("durationMs"), j.optString("artistId"), albumId, j.optString("playlistId"), j.optBoolean("musicVideo"), j.optString("setVideoId"), j.optString("artistTitle"), albumTitle)
        }
        fun list(a: JSONArray) = (0 until a.length()).mapNotNull { runCatching { from(a.getJSONObject(it)) }.getOrNull() }
    }
}
data class FeedSection(val title: String, val items: List<Song>)
data class MoodChip(val title: String, val params: String, val browseId: String = "FEmusic_home")
data class FeedPage(val items: List<Song>, val sections: List<FeedSection> = emptyList(), val chips: List<MoodChip> = emptyList(), val continuation: String? = null)
data class ResolvedStream(val url: String, val mime: String, val bitrate: Int = 0, val loudnessDb: Float = 0f, val videoId: String = "", val size: Long = 0)
data class UserPlaylist(val id: String, val title: String, val songs: List<Song>) {
    fun json() = JSONObject().put("id", id).put("title", title).put("songs", JSONArray(songs.map { it.json() }))
    fun card() = Song(id, title, "${songs.size} songs · On this device", songs.firstOrNull()?.art.orEmpty(), "playlist")
}
data class Lyrics(val lines: List<Pair<Long, String>> = emptyList(), val plain: String = "", val message: String = "")

class MusicRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("music", Context.MODE_PRIVATE)
    private val account = SecureSession(app)
    private var version = "1.20261006.10.00"
    private var visitor = ""
    private var bootstrapAt = 0L
    private var bootstrapSignedIn = false
    val favorites get() = load("favorites")
    val history get() = load("history")
    val savedCollections get() = load("saved_collections")
    val visitedCollections get() = load("visited_collections")
    val uploads get() = load("uploads")
    val downloads get() = load("downloads").filter { File(Uri.parse(it.id).path.orEmpty()).isFile }
    val signedIn get() = account.cookie.isNotBlank()
    val playlists get() = runCatching {
        val a = JSONArray(prefs.getString("playlists", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { UserPlaylist(it.getString("id"), it.getString("title"), Song.list(it.getJSONArray("songs"))) } }
    }.getOrDefault(emptyList())
    var theme: String
        get() = prefs.getString("theme", "Rose") ?: "Rose"
        set(value) { prefs.edit().putString("theme", value).apply() }
    fun reloadLocal() = Unit
    fun bool(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun number(key: String, default: Float = 0f) = prefs.getFloat(key, default)
    fun setNumber(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
    fun clearAccount() { account.clear(); bootstrapAt = 0; visitor = "" }
    private fun load(key: String) = runCatching { Song.list(JSONArray(prefs.getString(key, "[]"))) }.getOrDefault(emptyList())
    private fun save(key: String, songs: List<Song>) { prefs.edit().putString(key, JSONArray(songs.map { it.json() }).toString()).apply() }
    fun toggle(song: Song) { save("favorites", if (favorites.any { it.id == song.id }) favorites.filterNot { it.id == song.id } else listOf(song) + favorites) }
    fun toggleCollection(song: Song) { save("saved_collections", if (savedCollections.any { it.id == song.id }) savedCollections.filterNot { it.id == song.id } else listOf(song) + savedCollections) }
    fun remember(song: Song) { save("history", (listOf(song) + history.filterNot { it.id == song.id }).take(300)) }
    fun recordCollection(song: Song) { if (!song.playable) save("visited_collections", (listOf(song) + visitedCollections.filterNot { it.id == song.id }).take(100)) }
    private fun savePlaylists(value: List<UserPlaylist>) { prefs.edit().putString("playlists", JSONArray(value.map { it.json() }).toString()).apply() }
    fun createPlaylist(title: String): UserPlaylist {
        require(title.trim().isNotEmpty()) { "Give the playlist a name" }
        val value = UserPlaylist("local:${UUID.randomUUID()}", title.trim().take(200), emptyList())
        savePlaylists(playlists + value); return value
    }
    fun deletePlaylist(id: String) { savePlaylists(playlists.filterNot { it.id == id }) }
    fun renamePlaylist(id: String, title: String) { require(title.trim().isNotEmpty()); savePlaylists(playlists.map { if (it.id == id) it.copy(title = title.trim().take(200)) else it }) }
    fun addToPlaylist(id: String, song: Song) { require(song.playable); savePlaylists(playlists.map { if (it.id == id) it.copy(songs = it.songs + song) else it }) }
    fun removeFromPlaylist(id: String, index: Int) { savePlaylists(playlists.map { if (it.id == id) it.copy(songs = it.songs.filterIndexed { i, _ -> i != index }) else it }) }
    fun movePlaylistSong(id: String, from: Int, to: Int) { savePlaylists(playlists.map { p -> if (p.id != id || from !in p.songs.indices || to !in p.songs.indices) p else p.copy(songs = p.songs.toMutableList().also { it.add(to, it.removeAt(from)) }) }) }

    private fun connection(url: String, headers: JSONObject = JSONObject()): HttpURLConnection {
        val target = URL(url); require(target.protocol == "https") { "An HTTPS connection is required" }
        return (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 25_000
            setRequestProperty("User-Agent", "ytm-rs-android/0.2.0")
            headers.keys().forEach { setRequestProperty(it, headers.getString(it)) }
        }
    }
    private fun checkResponse(c: HttpURLConnection) {
        val status = c.responseCode
        check(status in 200..299) { when (status) { 401, 403 -> "YouTube refused this request. Sign in again or try another network."; 429 -> "YouTube is limiting requests. Wait a moment and retry."; else -> "The music service returned HTTP $status" } }
    }
    private fun http(url: String, body: String? = null, headers: JSONObject = JSONObject()): String {
        val c = connection(url, headers)
        try {
            if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            checkResponse(c)
            if (signedIn && c.url.host.let { it == "youtube.com" || it.endsWith(".youtube.com") }) {
                val cookies = c.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.map { it.substringBefore(';') }.filter { it.contains('=') }
                if (cookies.isNotEmpty()) account.mergeCookies(cookies)
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    @Synchronized private fun bootstrap() {
        if (bootstrapAt > 0 && bootstrapSignedIn == signedIn && System.currentTimeMillis() - bootstrapAt < 86_400_000) return
        val html = http("https://music.youtube.com", headers = JSONObject().put("Cookie", account.cookie.ifBlank { "SOCS=CAISAiAD" }).put("User-Agent", WEB_UA))
        Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let { version = it.groupValues[1] }
        Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let { visitor = it.groupValues[1] }
        bootstrapAt = System.currentTimeMillis(); bootstrapSignedIn = signedIn
    }
    private fun api(endpoint: String, body: JSONObject, authenticated: Boolean = signedIn, webPlayer: Boolean = false): JSONObject {
        bootstrap()
        val spec = NativeCore.run("request", "endpoint" to endpoint, "body" to body, "version" to version, "visitor" to visitor, "cookie" to if (authenticated) account.cookie else "", "restricted" to bool("restricted"), "webPlayer" to webPlayer) as JSONObject
        val response = JSONObject(http(spec.getString("url"), spec.getJSONObject("body").toString(), spec.getJSONObject("headers")))
        if (response.has("error")) error(response.getJSONObject("error").optString("message", "YouTube rejected the request"))
        return response
    }
    fun allowed(song: Song) = (!bool("hideExplicit") || !song.explicit) && (bool("podcasts") || song.kind !in listOf("episode", "show"))
    private fun parse(data: JSONObject): FeedPage {
        val p = NativeCore.run("parse", "data" to data) as JSONObject
        val items = Song.list(p.optJSONArray("items") ?: JSONArray()).filter(::allowed)
        val rawSections = p.optJSONArray("sections") ?: JSONArray()
        val sections = (0 until rawSections.length()).mapNotNull { i -> rawSections.getJSONObject(i).let { s -> val songs = Song.list(s.optJSONArray("items") ?: JSONArray()).filter(::allowed); if (songs.isEmpty()) null else FeedSection(s.optString("title"), songs) } }
        val rawChips = p.optJSONArray("chips") ?: JSONArray()
        val chips = (0 until rawChips.length()).mapNotNull { i -> rawChips.getJSONObject(i).let { c -> val title = c.optString("title"); if (!bool("podcasts") && title == "Podcasts") null else MoodChip(title, c.optString("params"), c.optString("browseId").ifBlank { "FEmusic_home" }) } }
        return FeedPage(items, sections, chips, p.optString("continuation").takeIf { it.isNotBlank() && it != "null" })
    }
    suspend fun feed(id: String, params: String? = null, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        if (id.startsWith("local:")) return@withContext FeedPage(playlists.find { it.id == id }?.songs.orEmpty().filter(::allowed))
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("browseId", id)
        if (!params.isNullOrBlank()) body.put("params", params)
        if (id == "FEmusic_charts") body.put("formData", JSONObject().put("selectedValues", JSONArray(listOf("US"))))
        parse(api("browse", body))
    }
    suspend fun page(id: String): List<Song> = feed(id).items
    suspend fun search(query: String, filter: String): List<Song> = searchPage(query, filter).items
    suspend fun searchPage(query: String, filter: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val params = when (filter) {
            "Albums" -> "EgWKAQIYAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
            "Artists" -> "EgWKAQIgAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
            "Playlists" -> "EgeKAQQoADgBahIQAxAEEAkQDhAKEAUQERAQEBU%3D"
            "Videos" -> "EgWKAQIQAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
            "Podcasts" -> "EgWKAQJQAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
            "Episodes" -> "EgWKAQJIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
            else -> "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
        }
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("query", query.trim().take(300)).put("params", params)
        parse(api("search", body)).copy(chips = emptyList())
    }
    suspend fun radio(song: Song): List<Song> = withContext(Dispatchers.IO) {
        if (song.local) return@withContext emptyList()
        parse(api("next", JSONObject().put("videoId", song.id).put("playlistId", "RDAMVM${song.id}").put("params", "wAEB8gECeAE%3D").put("enablePersistentPlaylistPanel", true).put("isAudioOnly", true).put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL"))).items.filter { it.playable && it.id != song.id }
    }
    fun personalizedFeed(): FeedPage {
        val interests = history.flatMap { song -> listOfNotNull(
            song.artistId.takeIf { it.isNotBlank() && song.artist.isNotBlank() }?.let { Song(it, song.artist, "Artist · From your listening", song.art, "artist") },
            song.albumId.takeIf { it.isNotBlank() && song.albumTitle.isNotBlank() }?.let { Song(it, song.albumTitle, "Album · ${song.artist}", song.art, "album") }
        ) }
        val sections = listOf(FeedSection("Listen again", history.filter(::allowed).take(20)), FeedSection("Your albums, artists & playlists", (visitedCollections + savedCollections + interests).distinctBy { it.id }.filter(::allowed).take(40)), FeedSection("Your favorites", favorites.filter(::allowed).take(20))).filter { it.items.isNotEmpty() }
        return FeedPage(sections.flatMap { it.items }.distinctBy { it.id }, sections)
    }
    suspend fun personalized(): FeedPage = withContext(Dispatchers.IO) {
        val local = personalizedFeed(); val sections = local.sections.toMutableList()
        val seeds = history.filter { !it.local }.take(2).ifEmpty { visitedCollections.take(1).flatMap { runCatching { feed(it.id).items.filter(Song::playable).take(1) }.getOrDefault(emptyList()) } }
        if (seeds.isNotEmpty()) {
            val picks = seeds.flatMap { runCatching { radio(it) }.getOrDefault(emptyList()) }.distinctBy { it.id }.filterNot { song -> history.take(10).any { it.id == song.id } }.take(30)
            if (picks.isNotEmpty()) sections.add(1.coerceAtMost(sections.size), FeedSection("Quick picks · Based on your listening", picks))
        }
        val remote = runCatching { feed("FEmusic_home") }.getOrDefault(FeedPage(emptyList()))
        if (remote.sections.isNotEmpty()) sections.addAll(remote.sections) else if (remote.items.isNotEmpty()) sections.add(FeedSection(if (signedIn) "Your music feed" else "Discover", remote.items))
        FeedPage(sections.flatMap { it.items }.distinctBy { it.id }, sections, remote.chips, remote.continuation)
    }
    suspend fun library(category: String): FeedPage {
        if (!signedIn) return when (category) {
            "Liked songs", "Favorites" -> FeedPage(favorites.filter(::allowed)); "Albums" -> FeedPage(savedCollections.filter { it.kind == "album" }); "Artists" -> FeedPage(savedCollections.filter { it.kind == "artist" })
            "Playlists" -> FeedPage(playlists.map { it.card() } + savedCollections.filter { it.kind == "playlist" }); "History" -> FeedPage(history.filter(::allowed)); "Uploads" -> FeedPage(uploads); "Downloads" -> FeedPage(downloads); else -> FeedPage(emptyList())
        }
        when (category) {
            "Liked songs", "Favorites" -> return feed("VLLM")
            "Artists" -> return feed("FEmusic_library_corpus_artists")
            "History" -> return feed("FEmusic_history", "oggECgIIAQ%3D%3D")
            "Uploads" -> return feed("FEmusic_library_privately_owned_tracks").let { it.copy(items = uploads + it.items) }
            "Podcasts" -> return feed("FEmusic_library_non_music_audio_list")
            "Downloads" -> return FeedPage(downloads)
            "Albums", "Playlists" -> return withContext(Dispatchers.IO) {
                val token = if (category == "Albums") "4qmFsgIoEhRGRW11c2ljX2xpa2VkX2FsYnVtcxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D" else "4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D"
                parse(api("browse", JSONObject().put("continuation", token))).let { if (category == "Playlists") it.copy(items = playlists.map { p -> p.card() } + it.items) else it }
            }
            else -> return feed("FEmusic_library_landing")
        }
    }
    suspend fun syncLike(song: Song, liked: Boolean) = withContext(Dispatchers.IO) {
        require(signedIn) { "Sign in to sync likes with YouTube Music" }
        val r = api(if (liked) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", song.id)))
        checkWrite(r)
    }
    private fun checkWrite(response: JSONObject) { val status = response.optString("status"); check(status.isBlank() || status.contains("SUCCEEDED")) { "YouTube Music rejected the change" } }
    private fun remoteId(id: String) = id.removePrefix("VL")
    suspend fun createRemotePlaylist(title: String): Song = withContext(Dispatchers.IO) {
        require(signedIn) { "Sign in to create a YouTube Music playlist" }; require(title.trim().isNotBlank()) { "Give the playlist a name" }
        val name = title.trim().filterNot { it == '<' || it == '>' }.take(200)
        val response = api("playlist/create", JSONObject().put("title", name).put("description", "").put("privacyStatus", "PRIVATE")); checkWrite(response)
        val id = response.optString("playlistId"); check(id.isNotBlank()) { "YouTube Music did not confirm playlist creation" }
        Song("VL${remoteId(id)}", name, "Your playlist · YouTube Music", "", "playlist", playlistId = remoteId(id))
    }
    suspend fun addToRemotePlaylist(playlistId: String, song: Song) = withContext(Dispatchers.IO) {
        require(signedIn && !song.local) { "Sign in and choose a YouTube Music song" }
        val action = JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", song.id).put("dedupeOption", "DEDUPE_OPTION_SKIP")
        checkWrite(api("browse/edit_playlist", JSONObject().put("playlistId", remoteId(playlistId)).put("actions", JSONArray().put(action))))
    }
    suspend fun removeRemotePlaylistSong(playlistId: String, videoId: String, setVideoId: String) = withContext(Dispatchers.IO) {
        require(signedIn) { "Sign in to edit this playlist" }; require(setVideoId.isNotBlank()) { "Reload this playlist before editing it" }
        val action = JSONObject().put("action", "ACTION_REMOVE_VIDEO").put("removedVideoId", videoId).put("setVideoId", setVideoId)
        checkWrite(api("browse/edit_playlist", JSONObject().put("playlistId", remoteId(playlistId)).put("actions", JSONArray().put(action))))
    }
    suspend fun moveRemotePlaylistSong(playlistId: String, setVideoId: String, beforeSetVideoId: String?) = withContext(Dispatchers.IO) {
        require(signedIn) { "Sign in to reorder this playlist" }; require(setVideoId.isNotBlank()) { "Reload this playlist before editing it" }
        val action = JSONObject().put("action", "ACTION_MOVE_VIDEO_BEFORE").put("setVideoId", setVideoId)
        if (!beforeSetVideoId.isNullOrBlank()) action.put("movedSetVideoIdSuccessor", beforeSetVideoId)
        val response = api("browse/edit_playlist", JSONObject().put("playlistId", remoteId(playlistId)).put("actions", JSONArray().put(action)))
        checkWrite(response); check(response.optString("status").contains("SUCCEEDED")) { "YouTube Music did not confirm the reorder" }
    }
    suspend fun deleteRemotePlaylist(playlistId: String) = withContext(Dispatchers.IO) { require(signedIn); checkWrite(api("playlist/delete", JSONObject().put("playlistId", remoteId(playlistId)))) }
    suspend fun syncCollection(song: Song, saved: Boolean) = withContext(Dispatchers.IO) {
        require(signedIn) { "Sign in to save this to YouTube Music" }
        if (song.kind == "artist") {
            checkWrite(api(if (saved) "subscription/subscribe" else "subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(song.id))))
        } else {
            var id = song.playlistId.ifBlank { if (song.kind == "playlist") remoteId(song.id) else "" }
            if (id.isBlank()) id = firstString(api("browse", JSONObject().put("browseId", song.id)), "playlistId").orEmpty()
            require(id.isNotBlank()) { "YouTube Music did not return a saveable collection" }
            checkWrite(api(if (saved) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("playlistId", remoteId(id)))))
        }
    }
    private fun firstString(value: Any?, key: String): String? = when (value) {
        is JSONObject -> value.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: value.keys().asSequence().mapNotNull { firstString(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { firstString(value.opt(it), key) }.firstOrNull()
        else -> null
    }
    suspend fun resolve(id: String): String = resolveStream(Song(id, "", "", "")).url
    suspend fun resolveStream(song: Song, download: Boolean = false, video: Boolean = false, forceHls: Boolean = false): ResolvedStream = withContext(Dispatchers.IO) {
        if (song.local) return@withContext ResolvedStream(song.id, if (song.id.endsWith(".m4a")) "audio/mp4" else "audio/*", videoId = song.id)
        require(Regex("[A-Za-z0-9_-]{11}").matches(song.id)) { "Invalid song ID" }
        var chosen = song
        if (!video && song.musicVideo && bool("preferRecordings", true)) {
            val candidates = runCatching { search("${song.title} ${song.artist}", "Songs") }.getOrDefault(emptyList())
            candidates.firstOrNull { !it.musicVideo && normalizeTitle(it.title) == normalizeTitle(song.title) && it.artist.contains(song.artist.substringBefore(','), true) }?.let { chosen = it }
        }
        val body = JSONObject().put("videoId", chosen.id)
        // shortcut: signed web streams with decipher challenges require the desktop JS solver; only direct formats are accepted here.
        val signed = if (signedIn) runCatching { api("player", body, webPlayer = true) }.getOrNull() else null
        val args: Array<Pair<String, Any>> = arrayOf("preferProgressive" to (download || (!video && !forceHls)), "preferAac" to download, "preferred" to if (download) "aac" else "opus", "video" to video)
        val stream = signed?.let { runCatching { NativeCore.run("stream", "data" to it, *args) as JSONObject }.getOrNull() } ?: (NativeCore.run("stream", "data" to api("player", body, authenticated = false), *args) as JSONObject)
        val url = stream.getString("url")
        val result = if (stream.optBoolean("hls") && !video) {
            val manifest = http(url, headers = JSONObject().put("User-Agent", PLAYER_UA))
            val audio = (NativeCore.run("hls", "text" to manifest) as JSONObject).optString("url")
            check(audio.isNotBlank() && audio != "null") { "YouTube did not return an audio HLS playlist" }
            ResolvedStream(URL(URL(url), audio).toString(), "application/x-mpegURL", stream.optInt("bitrate"), stream.optDouble("loudnessDb", 0.0).toFloat(), chosen.id)
        } else ResolvedStream(url, stream.optString("mime").substringBefore(';').ifBlank { if (video) "application/x-mpegURL" else "audio/mp4" }, stream.optInt("bitrate"), stream.optDouble("loudnessDb", 0.0).toFloat(), chosen.id, stream.optLong("size"))
        if (download) check(result.mime.startsWith("audio/mp4")) { "YouTube did not offer a direct AAC file for this song. Try another song or account." }
        result
    }
    suspend fun sponsorSegments(id: String): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return@withContext emptyList()
        val categories = encode("[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"music_offtopic\"]")
        runCatching { val a = JSONArray(http("https://sponsor.ajay.app/api/skipSegments?videoID=$id&categories=$categories")); (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).optJSONArray("segment")?.let { it.getDouble(0).times(1000).toLong() to it.getDouble(1).times(1000).toLong() } } }.getOrDefault(emptyList())
    }
    fun importAudio(uri: Uri): Song {
        val folder = File(app.filesDir, "imports").apply { mkdirs() }
        val display = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        val ext = display.substringAfterLast('.', "audio").takeIf { Regex("[A-Za-z0-9]{1,8}").matches(it) } ?: "audio"
        val file = File(folder, "${UUID.randomUUID()}.$ext")
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> val b = ByteArray(65536); var size = 0L; while (true) { val n = input.read(b); if (n < 0) break; size += n; check(size <= 300L * 1024 * 1024) { "Audio files must be smaller than 300 MB" }; output.write(b, 0, n) } } } ?: error("Could not open this audio file")
            val r = MediaMetadataRetriever()
            val song = try {
                r.setDataSource(file.path)
                val cover = r.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { bytes -> File(folder, "${file.nameWithoutExtension}.jpg").also { it.writeBytes(bytes) }.toURI().toString() }.orEmpty()
                Song(file.toURI().toString(), r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().ifBlank { display.substringBeforeLast('.').ifBlank { "Imported audio" } }, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().ifBlank { "Local audio" }, cover, "upload", durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
            } finally { r.release() }
            save("uploads", listOf(song) + uploads); return song
        } catch (e: Exception) { file.delete(); throw e }
    }
    suspend fun download(song: Song): Song = withContext(Dispatchers.IO) {
        check(!song.local) { "This song is already on your device" }
        val stream = resolveStream(song, download = true)
        val folder = File(app.filesDir, "downloads").apply { mkdirs() }
        val temp = File(folder, "${song.id}.part"); val file = File(folder, "${song.id}.m4a"); val art = File(folder, "${song.id}.jpg")
        try {
            var received = 0L
            temp.outputStream().use { output ->
                if (stream.size > 0) {
                    check(stream.size <= 1024L * 1024 * 1024) { "This file is too large to download" }
                    while (received < stream.size) {
                        currentCoroutineContext().ensureActive()
                        val end = (received + 262143).coerceAtMost(stream.size - 1)
                        val c = connection(stream.url + (if (stream.url.contains('?')) "&" else "?") + "range=$received-$end", JSONObject().put("User-Agent", PLAYER_UA))
                        try { checkResponse(c); val bytes = readBounded(c, (end - received + 1).toInt()); check(bytes.size.toLong() == end - received + 1) { "YouTube interrupted the download. Please retry." }; output.write(bytes); received += bytes.size } finally { c.disconnect() }
                    }
                } else {
                    val c = connection(stream.url, JSONObject().put("User-Agent", PLAYER_UA))
                    try { checkResponse(c); val expected = c.contentLengthLong; c.inputStream.use { input -> val b = ByteArray(65536); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(b); if (n < 0) break; received += n; check(received <= 1024L * 1024 * 1024); output.write(b, 0, n) } }; check(received > 0 && (expected <= 0 || received == expected)) { "The download was incomplete" } } finally { c.disconnect() }
                }
            }
            if (song.art.startsWith("https://")) runCatching { val c = connection(song.art); try { checkResponse(c); art.writeBytes(readBounded(c, 8 * 1024 * 1024)) } finally { c.disconnect() } }
            NativeCore.run("tag_m4a", "path" to temp.path, "title" to song.title, "artist" to song.artist, "album" to song.albumTitle, "explicit" to song.explicit, "artPath" to if (art.isFile) art.path else "")
            currentCoroutineContext().ensureActive(); check(temp.renameTo(file)) { "Could not save the downloaded file" }
            val local = song.copy(id = file.toURI().toString(), kind = "download", art = if (art.isFile) art.toURI().toString() else song.art)
            save("downloads", listOf(local) + downloads.filterNot { it.id == local.id }); local
        } catch (e: Exception) { temp.delete(); if (!file.isFile) art.delete(); throw e }
    }
    private fun readBounded(c: HttpURLConnection, limit: Int): ByteArray = c.inputStream.use { input -> val output = java.io.ByteArrayOutputStream(); val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; check(output.size() + n <= limit) { "Response exceeded the expected size" }; output.write(b, 0, n) }; output.toByteArray() }
    fun removeDownload(song: Song) {
        val path = File(Uri.parse(song.id).path.orEmpty()); val root = File(app.filesDir, "downloads").canonicalFile
        require(path.canonicalFile.parentFile == root) { "Invalid download path" }; check(!path.exists() || path.delete()) { "Could not delete the download" }
        if (song.art.startsWith("file:")) File(Uri.parse(song.art).path.orEmpty()).takeIf { it.canonicalFile.parentFile == root }?.delete()
        save("downloads", downloads.filterNot { it.id == song.id })
    }
    suspend fun uploadToAccount(uri: Uri) = withContext(Dispatchers.IO) {
        require(signedIn) { "Sign in before uploading music to YouTube Music" }
        val imported = importAudio(uri); val file = File(Uri.parse(imported.id).path.orEmpty())
        val spec = NativeCore.run("request", "endpoint" to "browse", "body" to JSONObject(), "cookie" to account.cookie, "version" to version, "visitor" to visitor) as JSONObject
        val headers = spec.getJSONObject("headers").put("X-Origin", "https://music.youtube.com")
        fun uploadRequest(url: String) = connection(url, headers).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8") }
        val start = uploadRequest("https://upload.youtube.com/upload/usermusic/http?authuser=0")
        val uploadUrl = try {
            start.setRequestProperty("X-Goog-Upload-Command", "start"); start.setRequestProperty("X-Goog-Upload-Header-Content-Length", file.length().toString()); start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            start.outputStream.use { it.write("filename=${encode(imported.title)}".toByteArray()) }; checkResponse(start)
            start.getHeaderField("X-Goog-Upload-URL") ?: error("YouTube Music did not accept the upload")
        } finally { start.disconnect() }
        val target = URL(uploadUrl); require(target.protocol == "https" && (target.host == "upload.youtube.com" || target.host.endsWith(".youtube.com"))) { "Invalid upload server" }
        val c = uploadRequest(uploadUrl)
        try { c.readTimeout = 600_000; c.setRequestProperty("X-Goog-Upload-Command", "upload, finalize"); c.setRequestProperty("X-Goog-Upload-Offset", "0"); c.setFixedLengthStreamingMode(file.length()); file.inputStream().use { input -> c.outputStream.use { output -> val b = ByteArray(65536); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(b); if (n < 0) break; output.write(b, 0, n) } } }; checkResponse(c) } finally { c.disconnect() }
    }
    suspend fun lyrics(song: Song): Lyrics = withContext(Dispatchers.IO) {
        var results = JSONArray(http("https://lrclib.net/api/search?track_name=${encode(song.title)}&artist_name=${encode(song.artist)}"))
        if (results.length() == 0) results = JSONArray(http("https://lrclib.net/api/search?q=${encode(normalizeTitle(song.title) + " " + song.artist.substringBefore(','))}"))
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }
        val match = candidates.firstOrNull { !it.isNull("syncedLyrics") } ?: candidates.firstOrNull() ?: return@withContext Lyrics(message = "No lyrics found for this song")
        if (match.optBoolean("instrumental")) return@withContext Lyrics(message = "Instrumental")
        val synced = match.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty(); val lines = NativeCore.run("lrc", "text" to synced) as JSONArray
        Lyrics((0 until lines.length()).map { lines.getJSONObject(it).let { it.getLong("ms") to it.getString("text") } }, match.optString("plainLyrics").takeUnless { it == "null" }.orEmpty())
    }
    private fun normalizeTitle(value: String) = value.replace(Regex("(?i)\\s*[\\[(](?:feat\\.?|official|lyrics|audio|video).*?[\\])]"), "").trim().lowercase()
    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
    companion object {
        const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val PLAYER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    }
}
