package com.alify.spotify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class CleanTrackSeed(
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val source: String = "listen", // "listen", "dj", "playlist_mirror", "override"
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * SanctuaryMemoryBank is the central cognitive hub of Hujjify.
 * It connects all decoupled features into a unified, self-reinforcing intelligence flywheel:
 *
 * 1. Live Sentinel records clean tracks listened to by the user.
 * 2. VibeSubstituteService saves accepted clean sister tracks here.
 * 3. PlaylistMirrorService saves clean playlist tracks here.
 * 4. LyricsActivity user overrides whitelist songs/artists directly into this bank.
 * 5. AiEthosDjService and Sanctuary Autoplay consume this memory bank to generate hyper-personalized
 *    sessions tailored to the user's real taste and ethos — outperforming Spotify's generic recommendations.
 */
object SanctuaryMemoryBank {

    private const val TAG = "SanctuaryMemory"
    private const val PREFS_NAME = "hujjify_sanctuary_memory"
    private const val KEY_SEEDS = "clean_track_seeds"
    private const val KEY_FAVORITE_ARTISTS = "clean_favorite_artists"
    private const val MAX_SEEDS = 150

    // In-memory cache of clean seeds: "artist::title".lowercase() -> CleanTrackSeed
    private val seedMap = ConcurrentHashMap<String, CleanTrackSeed>()
    private val cleanArtists = Collections.synchronizedSet(mutableSetOf<String>())
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val rawSeeds = prefs.getString(KEY_SEEDS, "[]") ?: "[]"
                val jsonArr = JSONArray(rawSeeds)
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.optJSONObject(i) ?: continue
                    val title = obj.optString("title")
                    val artist = obj.optString("artist")
                    val uri = obj.optString("uri").ifBlank { null }
                    val source = obj.optString("source", "listen")
                    val ts = obj.optLong("timestamp", System.currentTimeMillis())
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        val seed = CleanTrackSeed(title, artist, uri, source, ts)
                        val key = "${artist.trim().lowercase()}::${title.trim().lowercase()}"
                        seedMap[key] = seed
                        cleanArtists.add(artist.trim().lowercase())
                    }
                }

                val artistSet = prefs.getStringSet(KEY_FAVORITE_ARTISTS, emptySet()) ?: emptySet()
                cleanArtists.addAll(artistSet.map { it.lowercase() })
                isInitialized = true
                Log.d(TAG, "Initialized SanctuaryMemoryBank with ${seedMap.size} clean seeds and ${cleanArtists.size} clean artists")
            } catch (e: Exception) {
                Log.w(TAG, "Error initializing SanctuaryMemoryBank: ${e.message}")
            }
        }
    }

    fun recordCleanTrack(
        context: Context,
        title: String,
        artist: String,
        spotifyUri: String? = null,
        source: String = "listen"
    ) {
        if (title.isBlank() || artist.isBlank()) return
        init(context)

        val key = "${artist.trim().lowercase()}::${title.trim().lowercase()}"
        val seed = CleanTrackSeed(title.trim(), artist.trim(), spotifyUri, source, System.currentTimeMillis())
        seedMap[key] = seed
        cleanArtists.add(artist.trim().lowercase())

        persistAsync(context)
        Log.i(TAG, "Sanctuary memory recorded clean track: '$title' by '$artist' [source=$source]")
    }

    fun recordUserOverride(context: Context, title: String, artist: String, isAllowed: Boolean) {
        init(context)
        val key = "${artist.trim().lowercase()}::${title.trim().lowercase()}"
        if (isAllowed) {
            recordCleanTrack(context, title, artist, null, "override")
        } else {
            seedMap.remove(key)
            persistAsync(context)
        }
    }

    /**
     * Supplies verified clean tracks to the AI DJ or Autoplay Guardian to anchor sessions in real taste.
     */
    fun getRecentCleanSeeds(limit: Int = 10): List<CleanTrackSeed> {
        return seedMap.values
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /**
     * Top clean artists the user listens to, used to personalize AI prompts.
     */
    fun getTopCleanArtists(limit: Int = 8): List<String> {
        return seedMap.values
            .groupBy { it.artist }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
    }

    /**
     * Generates a concise context string for the AI DJ so it knows the user's proven clean taste profile.
     */
    fun buildTasteContext(): String {
        val topArtists = getTopCleanArtists(5)
        val recentTracks = getRecentCleanSeeds(6)
        if (topArtists.isEmpty() && recentTracks.isEmpty()) return ""

        val artistStr = if (topArtists.isNotEmpty()) "Top conscious artists listener enjoys: ${topArtists.joinToString(", ")}." else ""
        val trackStr = if (recentTracks.isNotEmpty()) "Recent verified clean songs in their sanctuary: " + recentTracks.joinToString("; ") { "'${it.title}' by ${it.artist}" } + "." else ""

        return "$artistStr $trackStr".trim()
    }

    private fun persistAsync(context: Context) {
        val appCtx = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val arr = JSONArray()
                val sorted = seedMap.values.sortedByDescending { it.timestamp }.take(MAX_SEEDS)
                for (s in sorted) {
                    val obj = JSONObject()
                    obj.put("title", s.title)
                    obj.put("artist", s.artist)
                    s.spotifyUri?.let { obj.put("uri", it) }
                    obj.put("source", s.source)
                    obj.put("timestamp", s.timestamp)
                    arr.put(obj)
                }
                prefs.edit()
                    .putString(KEY_SEEDS, arr.toString())
                    .putStringSet(KEY_FAVORITE_ARTISTS, cleanArtists)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Error persisting SanctuaryMemoryBank: ${e.message}")
            }
        }
    }
}
