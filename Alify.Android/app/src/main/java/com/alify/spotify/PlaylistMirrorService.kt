package com.alify.spotify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
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

sealed class PlaylistFetchResult {
    data class Success(val playlists: List<SpotifyPlaylistItem>) : PlaylistFetchResult()
    data class PermissionRequired(val code: Int) : PlaylistFetchResult()
    data class Error(val message: String) : PlaylistFetchResult()
}

object PlaylistMirrorService {

    private const val TAG = "PlaylistMirror"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Application-Level Coroutine Scope so background mirroring persists across screen navigation
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val progressListeners = CopyOnWriteArraySet<(MirrorProgress) -> Unit>()
    private val completionListeners = CopyOnWriteArraySet<(newPlaylistId: String?, errorReason: String?) -> Unit>()

    @Volatile var isMirroringActive: Boolean = false
        private set
    @Volatile var activePlaylistId: String? = null
        private set
    @Volatile var activePlaylistName: String? = null
        private set
    @Volatile var currentProgress: MirrorProgress? = null
        private set
    @Volatile var lastMirrorResult: String? = null
        private set
    @Volatile var lastErrorReason: String? = null
        private set

    fun addProgressListener(listener: (MirrorProgress) -> Unit) {
        progressListeners.add(listener)
        currentProgress?.let { listener(it) }
    }

    fun removeProgressListener(listener: (MirrorProgress) -> Unit) {
        progressListeners.remove(listener)
    }

    fun addCompletionListener(listener: (newPlaylistId: String?, errorReason: String?) -> Unit) {
        completionListeners.add(listener)
        if (!isMirroringActive && (lastMirrorResult != null || lastErrorReason != null)) {
            listener(lastMirrorResult, lastErrorReason)
        }
    }

    fun removeCompletionListener(listener: (newPlaylistId: String?, errorReason: String?) -> Unit) {
        completionListeners.remove(listener)
    }

    /**
     * Triggers sanctuary playlist creation in a decoupled background scope.
     * User can freely navigate between pages or lock the screen without interrupting execution.
     */
    fun startSanctuaryMirrorBackground(
        context: Context,
        playlist: SpotifyPlaylistItem,
        token: String,
        filterEngine: FilterEngine,
        lrclibClient: LrclibClient
    ) {
        if (isMirroringActive) return

        isMirroringActive = true
        activePlaylistId = playlist.id
        activePlaylistName = playlist.name
        lastMirrorResult = null
        lastErrorReason = null

        val initProgress = MirrorProgress(0, playlist.trackCount.coerceAtLeast(1), "Starting background sanctification for '${playlist.name}'...")
        currentProgress = initProgress
        for (l in progressListeners) {
            try { l(initProgress) } catch (_: Exception) {}
        }

        serviceScope.launch {
            try {
                val newId = createSanctuaryMirror(
                    context = context,
                    sourcePlaylistId = playlist.id,
                    sourcePlaylistName = playlist.name,
                    token = token,
                    filterEngine = filterEngine,
                    lrclibClient = lrclibClient
                ) { prog ->
                    currentProgress = prog
                    for (l in progressListeners) {
                        try { l(prog) } catch (_: Exception) {}
                    }
                }

                lastMirrorResult = newId
                if (newId == null && lastErrorReason == null) {
                    lastErrorReason = "Spotify API returned no tracks or playlist creation was not authorized."
                }

                for (l in completionListeners) {
                    try { l(newId, lastErrorReason) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                lastErrorReason = e.message ?: "Unexpected error during background mirroring"
                for (l in completionListeners) {
                    try { l(null, lastErrorReason) } catch (_: Exception) {}
                }
            } finally {
                isMirroringActive = false
            }
        }
    }

    /**
     * Retrieves the current user's playlists from Spotify.
     * GET /v1/me/playlists?limit=50
     */
    suspend fun getUserPlaylists(token: String): PlaylistFetchResult = withContext(Dispatchers.IO) {
        val list = mutableListOf<SpotifyPlaylistItem>()
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                Log.d(TAG, "getUserPlaylists response code: ${resp.code}")
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext PlaylistFetchResult.PermissionRequired(resp.code)
                }
                if (!resp.isSuccessful) {
                    return@withContext PlaylistFetchResult.Error("Spotify API error (${resp.code})")
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: return@withContext PlaylistFetchResult.Success(emptyList())
                for (i in 0 until items.length()) {
                    val p = items.optJSONObject(i) ?: continue
                    val id = p.optString("id")
                    val name = p.optString("name")
                    val tracksObj = p.optJSONObject("tracks")
                    // Robust track count resolution: checks tracks.total, total_tracks, and total
                    val total = when {
                        tracksObj != null -> tracksObj.optInt("total", 0)
                        p.has("total_tracks") -> p.optInt("total_tracks", 0)
                        p.has("total") -> p.optInt("total", 0)
                        else -> 0
                    }
                    val images = p.optJSONArray("images")
                    val imageUrl = if (images != null && images.length() > 0) {
                        images.getJSONObject(0).optString("url")
                    } else null

                    if (id.isNotBlank() && name.isNotBlank()) {
                        list.add(SpotifyPlaylistItem(id, name, total, imageUrl))
                    }
                }
            }
            PlaylistFetchResult.Success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load playlists: ${e.message}", e)
            PlaylistFetchResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Audits an entire Spotify playlist track by track, finds clean sister tracks for any flagged song,
     * and generates an "Sanctuary: [Name]" playlist in the user's Spotify account.
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
                val err = "Spotify returned 0 playable tracks for '$sourcePlaylistName'. The playlist may be empty or restricted."
                lastErrorReason = err
                Log.w(TAG, err)
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
                    SanctuaryMemoryBank.recordCleanTrack(context, currentTrackName, currentArtist, currentUri, "playlist_mirror")
                } else {
                    // Track is flagged! Dynamically find a clean sister track with the same vibe
                    val profileName = when (filterEngine.profile) {
                        EthosProfile.ISLAMIC -> "Islamic"
                        EthosProfile.CHRISTIAN -> "Christian"
                        EthosProfile.FAMILY_FRIENDLY -> "Family Safe"
                        EthosProfile.GENERAL -> "Conscious"
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
                        SanctuaryMemoryBank.recordCleanTrack(context, substitute.title, substitute.artist, substitute.spotifyUri, "playlist_mirror_swap")
                        Log.i(TAG, "Replaced '$currentTrackName' with '${substitute.title}' (${substitute.spotifyUri})")
                    } else {
                        // If no substitute found, omit the dirty track to keep the sanctuary pure
                        Log.i(TAG, "Omitted dirty track '$currentTrackName' (no verified clean substitute found)")
                    }
                }
            }

            if (cleanUris.isEmpty()) {
                lastErrorReason = "No clean tracks could be verified for '$sourcePlaylistName'."
                return@withContext null
            }

            // 3. Create the new Sanctuary playlist in user's Spotify account
            onProgress(
                MirrorProgress(
                    current = total,
                    total = total,
                    statusText = "Creating 'Sanctuary: $sourcePlaylistName' in Spotify...",
                    cleanCount = cleanCount,
                    replacedCount = replacedCount
                )
            )

            val mirrorName = "Sanctuary: $sourcePlaylistName"
            val newPlaylistId = createEmptyPlaylist(mirrorName, token)
            if (newPlaylistId == null) {
                lastErrorReason = "Spotify refused to create playlist '$mirrorName'. Please reconnect Spotify in Settings with playlist write permissions."
                return@withContext null
            }

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
            lastErrorReason = e.message ?: "Failed to generate sanctuary mirror"
            null
        }
    }

    private suspend fun getPlaylistTracks(playlistId: String, token: String): List<Tuple4<String, String, String, Boolean>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Tuple4<String, String, String, Boolean>>()
        try {
            // Clean standard endpoint with no fragile, unencoded fields parameter
            val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getPlaylistTracks failed with status code ${resp.code}: ${resp.message}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: return@withContext emptyList()
                for (i in 0 until items.length()) {
                    val itemObj = items.optJSONObject(i) ?: continue
                    val track = itemObj.optJSONObject("track") ?: itemObj
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
        val body = JSONObject().apply {
            put("name", name)
            put("description", "Ethically verified clean mirror created by Ujify AI Sentinel.")
            put("public", false)
        }.toString().toRequestBody("application/json".toMediaType())

        // 1. Attempt standard POST /v1/me/playlists
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val id = json.optString("id")
                    if (id.isNotBlank()) return@withContext id
                }
                Log.w(TAG, "POST /v1/me/playlists returned ${resp.code}. Trying user endpoint fallback...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create playlist via /me: ${e.message}")
        }

        // 2. Fallback: Query current user profile ID and POST /v1/users/{user_id}/playlists
        try {
            val userReq = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            var userId: String? = null
            httpClient.newCall(userReq).execute().use { userResp ->
                if (userResp.isSuccessful) {
                    val userJson = JSONObject(userResp.body?.string() ?: "")
                    userId = userJson.optString("id").takeIf { it.isNotBlank() }
                }
            }

            if (userId != null) {
                val userPlaylistReq = Request.Builder()
                    .url("https://api.spotify.com/v1/users/$userId/playlists")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                httpClient.newCall(userPlaylistReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "")
                        return@withContext json.optString("id").takeIf { it.isNotBlank() }
                    }
                    Log.e(TAG, "POST /v1/users/$userId/playlists failed with code ${resp.code}: ${resp.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback playlist creation failed: ${e.message}")
        }
        null
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
