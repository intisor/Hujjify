package com.alify.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
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

object AiEthosDjService {

    private const val TAG = "AiEthosDj"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Dynamically generates a tailored, clean music session based on natural language prompt.
     * Uses LLM waterfall with intelligent Spotify catalog fallback so recommendations ALWAYS succeed.
     */
    suspend fun generateAndPlaySession(
        context: Context,
        userPrompt: String,
        token: String,
        filterEngine: FilterEngine,
        onStatus: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onStatus("AI DJ is curating your '$userPrompt' session...")

            SanctuaryMemoryBank.init(context)
            val tasteContext = SanctuaryMemoryBank.buildTasteContext()
            val tastePrompt = if (tasteContext.isNotBlank()) "\nListener's personal verified taste context: $tasteContext\nHarmonize with their preferred sonic aesthetic while matching the requested vibe.\n" else ""

            val profileName = when (filterEngine.profile) {
                EthosProfile.ISLAMIC -> "Islamic"
                EthosProfile.CHRISTIAN -> "Christian"
                EthosProfile.FAMILY_FRIENDLY -> "Family Safe"
                EthosProfile.GENERAL -> "Conscious"
            }

            val prompt = """
                You are an elite musicologist and AI DJ curating an ethical, conscious music session on Spotify.
                The user requested: "$userPrompt"
                Ethos strictness: $profileName (Must have 100% wholesome, clean, positive, or uplifting lyrics with ZERO explicit language, vulgarity, violence, or obscenity).
                $tastePrompt
                Recommend 6 real, widely known, high-energy or vibe-matching tracks on Spotify that match the exact tempo, genre, and emotion requested.
                DO NOT recommend fictional songs. Must have clean, wholesome, or uplifting lyrics.

                Return strictly a JSON array of objects with "title", "artist", and "matchReason":
                [
                  {"title": "Track Title", "artist": "Artist Name", "matchReason": "Why this fits the vibe"}
                ]
            """.trimIndent()

            val aiKey = filterEngine.geminiApiKey
            val keys = FilterEngine.buildProviderKeyWaterfall(aiKey)

            var rawResponse: String? = null
            for (k in keys) {
                if (k.isNotBlank()) {
                    rawResponse = FilterEngine.executeLlmWaterfallRequest(prompt, k, filterEngine.customModel, filterEngine.customEndpoint)
                    if (!rawResponse.isNullOrBlank()) {
                        Log.d(TAG, "AI DJ recommendation generated using key: ${k.take(8)}...")
                        break
                    }
                }
            }

            var candidates: List<SubstituteSuggestion> = if (!rawResponse.isNullOrBlank()) {
                parseSuggestions(rawResponse)
            } else {
                emptyList()
            }

            // Intelligent Spotify Catalog & Ethos Fallback if LLM was unavailable or unparsed
            if (candidates.isEmpty()) {
                onStatus("Connecting with Spotify catalog for '$userPrompt' clean vibes...")
                candidates = fetchSpotifyCatalogFallback(userPrompt, token)
            }

            if (candidates.isEmpty()) {
                // If even search returned no items, pull from user's verified clean memory bank
                val memorySeeds = SanctuaryMemoryBank.getRecentCleanSeeds(6)
                candidates = memorySeeds.map {
                    SubstituteSuggestion(it.title, it.artist, spotifyUri = it.spotifyUri, matchReason = "Verified clean memory")
                }
            }

            if (candidates.isEmpty()) {
                onStatus("Could not curate tracks for this session. Try another vibe.")
                return@withContext false
            }

            onStatus("Resolving ${candidates.size} songs on Spotify...")

            val resolvedUris = mutableListOf<String>()
            for (c in candidates) {
                val uri = c.spotifyUri ?: VibeSubstituteService.searchSpotifyTrack(c.title, c.artist, token)
                if (uri != null && !resolvedUris.contains(uri)) {
                    resolvedUris.add(uri)
                    SanctuaryMemoryBank.recordCleanTrack(context, c.title, c.artist, uri, "dj")
                }
            }

            if (resolvedUris.isEmpty()) {
                onStatus("Could not find matching tracks on Spotify catalog")
                return@withContext false
            }

            onStatus("Starting playback in Spotify (${resolvedUris.size} tracks)...")
            val played = startPlayback(resolvedUris, token)
            if (played) {
                onStatus("Playing: $userPrompt (${resolvedUris.size} tracks queued)")
            } else {
                // Enqueue tracks into user's queue
                for (uri in resolvedUris) {
                    VibeSubstituteService.enqueueTrack(uri, token)
                }

                // If direct playback start failed (e.g. Spotify app is asleep / free tier), launch Spotify directly
                try {
                    val firstUri = resolvedUris.firstOrNull()
                    if (firstUri != null) {
                        val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse(firstUri)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            `package` = "com.spotify.music"
                        }
                        context.startActivity(spotifyIntent)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Direct Spotify intent fallback skipped: ${e.message}")
                }
                onStatus("Queued ${resolvedUris.size} clean tracks into Spotify!")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "AI DJ session generation failed: ${e.message}", e)
            onStatus("AI DJ error: ${e.message}")
            false
        }
    }

    /**
     * Intelligent catalog search fallback that queries Spotify directly for clean, vibe-aligned tracks.
     */
    private suspend fun fetchSpotifyCatalogFallback(userPrompt: String, token: String): List<SubstituteSuggestion> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SubstituteSuggestion>()
        try {
            val lower = userPrompt.lowercase()
            val query = when {
                lower.contains("workout") || lower.contains("hype") || lower.contains("energy") -> "genre:afrobeats clean workout"
                lower.contains("focus") || lower.contains("study") || lower.contains("chill") || lower.contains("lo-fi") || lower.contains("lofi") -> "lofi chill instrumental focus clean"
                lower.contains("drive") || lower.contains("night") -> "synthwave night drive melodic clean"
                lower.contains("soul") || lower.contains("grateful") || lower.contains("peace") || lower.contains("spirit") -> "uplifting gospel soul gratitude clean"
                else -> "clean $userPrompt"
            }

            val url = "https://api.spotify.com/v1/search?q=${Uri.encode(query)}&type=track&limit=15"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val root = JSONObject(body)
                val items = root.optJSONObject("tracks")?.optJSONArray("items") ?: return@withContext emptyList()

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue

                    val title = item.optString("name").trim()
                    val uri = item.optString("uri").trim()
                    val artistsArr = item.optJSONArray("artists")
                    val artist = if (artistsArr != null && artistsArr.length() > 0) {
                        artistsArr.getJSONObject(0).optString("name").trim()
                    } else "Unknown"

                    if (title.isNotBlank() && uri.isNotBlank()) {
                        list.add(SubstituteSuggestion(title, artist, spotifyUri = uri, matchReason = "Spotify clean vibe search"))
                    }
                    if (list.size >= 6) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify catalog fallback failed: ${e.message}")
        }
        list
    }

    /**
     * Starts playback on the active Spotify device with a list of track URIs.
     * PUT /v1/me/player/play
     */
    private suspend fun startPlayback(uris: List<String>, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("uris", JSONArray(uris))
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/play")
                .header("Authorization", "Bearer $token")
                .put(body)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 204) return@withContext true

                // If 404 or no active device, look for available devices
                if (resp.code == 404) {
                    val deviceId = getFirstAvailableDeviceId(token)
                    if (deviceId != null) {
                        val retryReq = Request.Builder()
                            .url("https://api.spotify.com/v1/me/player/play?device_id=$deviceId")
                            .header("Authorization", "Bearer $token")
                            .put(body)
                            .build()
                        httpClient.newCall(retryReq).execute().use { retryResp ->
                            return@withContext retryResp.isSuccessful || retryResp.code == 204
                        }
                    }
                }
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct play request failed: ${e.message}")
            false
        }
    }

    private suspend fun getFirstAvailableDeviceId(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/devices")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: "")
                val devices = json.optJSONArray("devices") ?: return@withContext null
                if (devices.length() > 0) {
                    return@withContext devices.getJSONObject(0).optString("id")
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSuggestions(raw: String): List<SubstituteSuggestion> {
        val list = mutableListOf<SubstituteSuggestion>()
        try {
            // 1. JSON Array format: [ { "title": "...", "artist": "..." } ]
            val startIdx = raw.indexOf('[')
            val endIdx = raw.lastIndexOf(']')
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                val jsonStr = raw.substring(startIdx, endIdx + 1)
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title").trim()
                    val artist = obj.optString("artist").trim()
                    val matchReason = obj.optString("matchReason").trim()
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        list.add(SubstituteSuggestion(title, artist, matchReason = matchReason))
                    }
                }
                if (list.isNotEmpty()) return list
            }

            // 2. JSON Object format: { "tracks": [ ... ] } or { "songs": [ ... ] }
            val objStart = raw.indexOf('{')
            val objEnd = raw.lastIndexOf('}')
            if (objStart != -1 && objEnd != -1 && objEnd > objStart) {
                val obj = JSONObject(raw.substring(objStart, objEnd + 1))
                val arr = obj.optJSONArray("tracks") ?: obj.optJSONArray("songs") ?: obj.optJSONArray("recommendations")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val title = item.optString("title").trim()
                        val artist = item.optString("artist").trim()
                        if (title.isNotBlank() && artist.isNotBlank()) {
                            list.add(SubstituteSuggestion(title, artist))
                        }
                    }
                    if (list.isNotEmpty()) return list
                }
            }

            // 3. Fallback: Parse bullet lines e.g. "1. 'Title' by Artist" or "- Title - Artist"
            val regex = Regex("""(?m)^\s*(?:\d+[\.\)]|\*|-)?\s*["“]?([^"”\n\-–—]+?)["”?]?\s*(?:by|–|-)\s*["“]?([^"”\n\(]+)""")
            for (match in regex.findAll(raw)) {
                val title = match.groupValues[1].trim()
                val artist = match.groupValues[2].trim()
                if (title.isNotBlank() && artist.isNotBlank() && !title.startsWith("Track Title", ignoreCase = true)) {
                    list.add(SubstituteSuggestion(title, artist, matchReason = "Text list recommendation"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DJ suggestions: ${e.message}")
        }
        return list
    }
}
