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

object AiEthosDjService {

    private const val TAG = "AiEthosDj"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Dynamically generates a tailored, clean music session based on natural language prompt.
     * ZERO hardcoded songs or artists — entirely generated on the fly.
     */
    suspend fun generateAndPlaySession(
        context: Context,
        userPrompt: String,
        token: String,
        filterEngine: FilterEngine,
        onStatus: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onStatus("🎧 AI DJ is curating your '$userPrompt' session...")

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
                DO NOT recommend fictional songs. DO NOT recommend songs with explicit tags.

                Return strictly a JSON array of objects with "title", "artist", and "matchReason":
                [
                  {"title": "Track Title", "artist": "Artist Name", "matchReason": "Why this fits the vibe"}
                ]
            """.trimIndent()

            val aiKey = filterEngine.geminiApiKey
            val keys = FilterEngine.buildProviderKeyWaterfall(aiKey)
            val primaryKey = keys.firstOrNull() ?: ""

            if (primaryKey.isBlank()) {
                onStatus("⚠️ Please add an AI API key in Settings to use AI DJ")
                return@withContext false
            }

            val rawResponse = FilterEngine.executeLlmWaterfallRequest(prompt, primaryKey)
            if (rawResponse.isNullOrBlank()) {
                onStatus("⚠️ AI DJ could not generate recommendations")
                return@withContext false
            }

            val candidates = parseSuggestions(rawResponse)
            if (candidates.isEmpty()) {
                onStatus("⚠️ Could not parse song suggestions")
                return@withContext false
            }

            onStatus("🔍 Resolving ${candidates.size} songs on Spotify...")

            val resolvedUris = mutableListOf<String>()
            for (c in candidates) {
                val uri = VibeSubstituteService.searchSpotifyTrack(c.title, c.artist, token)
                if (uri != null) {
                    resolvedUris.add(uri)
                    SanctuaryMemoryBank.recordCleanTrack(context, c.title, c.artist, uri, "dj")
                }
            }

            if (resolvedUris.isEmpty()) {
                onStatus("⚠️ Could not match tracks on Spotify catalog")
                return@withContext false
            }

            onStatus("▶️ Starting playback in Spotify (${resolvedUris.size} tracks)...")
            val played = startPlayback(resolvedUris, token)
            if (played) {
                onStatus("🎧 Playing: $userPrompt (${resolvedUris.size} tracks queued)")
            } else {
                // If direct playback start failed (e.g. Spotify is in background), enqueue them instead
                for (uri in resolvedUris) {
                    VibeSubstituteService.enqueueTrack(uri, token)
                }
                onStatus("⚡ Added ${resolvedUris.size} tracks to your Spotify queue!")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "AI DJ session generation failed: ${e.message}", e)
            onStatus("⚠️ AI DJ error: ${e.message}")
            false
        }
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DJ suggestions: ${e.message}")
        }
        return list
    }
}
