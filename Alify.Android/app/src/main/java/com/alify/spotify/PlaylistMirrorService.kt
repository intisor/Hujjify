package com.alify.spotify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SpotifyPlaylistItem(
    val id: String,
    val name: String,
    val trackCount: Int,
    val imageUrl: String?
)

data class MirrorProgress(
    val current: Int,
    val total: Int,
    val statusText: String,
    val cleanCount: Int = 0,
    val replacedCount: Int = 0
)

object PlaylistMirrorService {

    private const val TAG = "PlaylistMirror"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Retrieves the current user's playlists from Spotify.
     * GET /v1/me/playlists?limit=50
     */
    suspend fun getUserPlaylists(token: String): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SpotifyPlaylistItem>()
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "")
                val items = json.optJSONArray("items") ?: return@withContext emptyList()
                for (i in 0 until items.length()) {
                    val p = items.optJSONObject(i) ?: continue
                    val id = p.optString("id")
                    val name = p.optString("name")
                    val tracksObj = p.optJSONObject("tracks")
                    val total = tracksObj?.optInt("total", 0) ?: 0
                    val images = p.optJSONArray("images")
                    val imageUrl = if (images != null && images.length() > 0) {
                        images.getJSONObject(0).optString("url")
                    } else null

                    if (id.isNotBlank() && name.isNotBlank()) {
                        list.add(SpotifyPlaylistItem(id, name, total, imageUrl))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load playlists: ${e.message}", e)
        }
        list
    }

    /**
     * Audits an entire Spotify playlist track by track, finds clean sister tracks for any flagged song,
     * and generates an "🛡️ Sanctuary: [Name]" playlist in the user's Spotify account.
     */
    suspend fun createSanctuaryMirror(
        context: Context,
        sourcePlaylistId: String,
        sourcePlaylistName: String,
        token: String,
        filterEngine: FilterEngine,
        lrclibClient: LrclibClient,
        onProgress: (MirrorProgress) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        try {
            onProgress(MirrorProgress(0, 0, "Fetching tracks from '$sourcePlaylistName'..."))

            // 1. Fetch tracks from the source playlist
            val trackItems = getPlaylistTracks(sourcePlaylistId, token)
            if (trackItems.isEmpty()) {
                Log.w(TAG, "Source playlist is empty")
                return@withContext null
            }

            val total = trackItems.size
            val cleanUris = mutableListOf<String>()
            var cleanCount = 0
            var replacedCount = 0

            // 2. Audit and substitute each track
            for ((index, item) in trackItems.withIndex()) {
                val currentTrackName = item.first
                val currentArtist = item.second
                val currentUri = item.third
                val isExplicit = item.fourth

                onProgress(
                    MirrorProgress(
                        current = index + 1,
                        total = total,
                        statusText = "Auditing ${index + 1}/$total: $currentTrackName - $currentArtist",
                        cleanCount = cleanCount,
                        replacedCount = replacedCount
                    )
                )

                // Quick heuristic + lyrics fetch
                val lyrics = lrclibClient.getLyrics(currentArtist, currentTrackName, filterEngine.geminiApiKey)
                val eval = filterEngine.evaluate(currentTrackName, currentArtist, isExplicit, lyrics)

                if (!eval.isFlagged) {
                    cleanUris.add(currentUri)
                    cleanCount++
                } else {
                    // Track is flagged! Dynamically find a clean sister track with the same vibe
                    val profileName = when (filterEngine.profile) {
                        0 -> "Islamic"
                        1 -> "Christian"
                        2 -> "Family Safe"
                        else -> "Conscious"
                    }

                    val substitute = VibeSubstituteService.findCleanSisterTrack(
                        context = context,
                        flaggedTrack = currentTrackName,
                        flaggedArtist = currentArtist,
                        flagReason = eval.flagReason ?: "Explicit lyrics",
                        profileName = profileName,
                        spotifyToken = token,
                        filterEngine = filterEngine
                    )

                    if (substitute?.spotifyUri != null) {
                        cleanUris.add(substitute.spotifyUri)
                        replacedCount++
                        Log.i(TAG, "Replaced '$currentTrackName' with '${substitute.title}' (${substitute.spotifyUri})")
                    } else {
                        // If no substitute found, omit the dirty track to keep the sanctuary pure
                        Log.i(TAG, "Omitted dirty track '$currentTrackName' (no verified clean substitute found)")
                    }
                }
            }

            // 3. Create the new Sanctuary playlist in user's Spotify account
            onProgress(
                MirrorProgress(
                    current = total,
                    total = total,
                    statusText = "Creating '🛡️ Sanctuary: $sourcePlaylistName' in Spotify...",
                    cleanCount = cleanCount,
                    replacedCount = replacedCount
                )
            )

            val mirrorName = "🛡️ Sanctuary: $sourcePlaylistName"
            val newPlaylistId = createEmptyPlaylist(mirrorName, token) ?: return@withContext null

            // 4. Populate clean tracks in batches of 100
            addTracksToPlaylist(newPlaylistId, cleanUris, token)

            onProgress(
                MirrorProgress(
                    current = total,
                    total = total,
                    statusText = "Done! Created '$mirrorName' ($cleanCount preserved, $replacedCount upgraded).",
                    cleanCount = cleanCount,
                    replacedCount = replacedCount
                )
            )

            newPlaylistId
        } catch (e: Exception) {
            Log.e(TAG, "Error generating sanctuary mirror: ${e.message}", e)
            null
        }
    }

    private suspend fun getPlaylistTracks(playlistId: String, token: String): List<Tuple4<String, String, String, Boolean>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Tuple4<String, String, String, Boolean>>()
        try {
            val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100&fields=items(track(name,artists(name),uri,explicit))"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "")
                val items = json.optJSONArray("items") ?: return@withContext emptyList()
                for (i in 0 until items.length()) {
                    val track = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                    val name = track.optString("name")
                    val uri = track.optString("uri")
                    val explicit = track.optBoolean("explicit", false)
                    val artistsArr = track.optJSONArray("artists")
                    val artist = if (artistsArr != null && artistsArr.length() > 0) {
                        artistsArr.getJSONObject(0).optString("name")
                    } else "Unknown Artist"

                    if (name.isNotBlank() && uri.isNotBlank()) {
                        list.add(Tuple4(name, artist, uri, explicit))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load playlist tracks: ${e.message}")
        }
        list
    }

    private suspend fun createEmptyPlaylist(name: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("name", name)
                put("description", "Ethically verified clean mirror created by Hujjify AI Sentinel.")
                put("public", false)
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: "")
                json.optString("id").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create empty playlist: ${e.message}")
            null
        }
    }

    private suspend fun addTracksToPlaylist(playlistId: String, uris: List<String>, token: String) = withContext(Dispatchers.IO) {
        // Spotify API allows at most 100 tracks per POST
        for (batch in uris.chunked(100)) {
            try {
                val body = JSONObject().apply {
                    put("uris", JSONArray(batch))
                }.toString().toRequestBody("application/json".toMediaType())

                val req = Request.Builder()
                    .url("https://api.spotify.com/v1/playlists/$playlistId/tracks")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                httpClient.newCall(req).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add batch of tracks: ${e.message}")
            }
        }
    }

    data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
