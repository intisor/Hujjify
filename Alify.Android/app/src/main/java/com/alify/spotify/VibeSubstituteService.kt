package com.alify.spotify

import android.content.Context
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

data class SubstituteSuggestion(
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val matchReason: String? = null
)

object VibeSubstituteService {

    private const val TAG = "VibeSubstitute"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Dynamically asks the AI (Gemini/Groq waterfall) to recommend 3 clean sister tracks that match
     * the exact genre, acoustic energy, rhythm, and vibe of the flagged track.
     * ZERO hardcoded songs or artists — entirely generated on the fly.
     */
    suspend fun findCleanSisterTrack(
        context: Context,
        flaggedTrack: String,
        flaggedArtist: String,
        flagReason: String,
        profileName: String,
        spotifyToken: String,
        filterEngine: FilterEngine
    ): SubstituteSuggestion? = withContext(Dispatchers.IO) {
        try {
            SanctuaryMemoryBank.init(context)
            val tasteContext = SanctuaryMemoryBank.buildTasteContext()
            val tastePrompt = if (tasteContext.isNotBlank()) "\nListener's personal verified taste context: $tasteContext\nPrefer clean sister tracks that align with this aesthetic if suitable.\n" else ""

            val prompt = """
                You are an expert music curator and musicologist.
                The following song was flagged by an ethical music sentinel for violating the $profileName listening ethos:
                Track: "$flaggedTrack" by "$flaggedArtist"
                Violation Reason: "$flagReason"
                $tastePrompt
                Recommend 3 high-quality, real, verified alternative songs available on Spotify that:
                1. Match the EXACT SAME musical genre, acoustic energy, tempo/BPM, beat pattern, and vocal vibe.
                2. Have 100% CLEAN, positive, inspiring, or wholesome lyrics with ZERO explicit language, violence, or vulgarity.
                3. Are NOT by the same artist if that artist exclusively makes explicit music.
                4. NEVER suggest "$flaggedTrack".

                Return ONLY a valid JSON array of objects with "title", "artist", and "matchReason", like this:
                [
                  {"title": "Track Name", "artist": "Artist Name", "matchReason": "Same Afrobeat groove and 108 BPM with uplifting grateful lyrics"}
                ]
            """.trimIndent()

            val aiKey = filterEngine.geminiApiKey
            val keys = filterEngine.buildProviderKeyWaterfall(aiKey)
            val primaryKey = keys.firstOrNull() ?: ""

            if (primaryKey.isBlank()) {
                Log.w(TAG, "No AI API key available for dynamic vibe substitution")
                return@withContext null
            }

            val rawResponse = filterEngine.executeLlmWaterfallRequest(prompt, primaryKey)
            if (rawResponse.isNullOrBlank()) {
                Log.w(TAG, "Empty response from AI for vibe substitution")
                return@withContext null
            }

            val suggestions = parseSuggestions(rawResponse)
            Log.d(TAG, "AI generated ${suggestions.size} clean substitute suggestions for '$flaggedTrack'")

            // Search Spotify for each candidate until a verified Spotify URI is found
            for (candidate in suggestions) {
                val uri = searchSpotifyTrack(candidate.title, candidate.artist, spotifyToken)
                if (uri != null) {
                    Log.i(TAG, "Found Spotify match for sister track: '${candidate.title}' by '${candidate.artist}' -> $uri")
                    SanctuaryMemoryBank.recordCleanTrack(context, candidate.title, candidate.artist, uri, "vibe_swap")
                    return@withContext candidate.copy(spotifyUri = uri)
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving dynamic vibe substitute: ${e.message}", e)
            null
        }
    }

    /**
     * Resolves a real Spotify Track URI via Spotify's official Search API.
     * GET /v1/search?q=track:"..." artist:"..."&type=track&limit=1
     */
    suspend fun searchSpotifyTrack(title: String, artist: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = "track:\"$title\" artist:\"$artist\""
            val url = "https://api.spotify.com/v1/search?q=${Uri.encode(query)}&type=track&limit=1"

            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Fallback to looser query if exact quotes yielded no results
                    val looseQuery = "$title $artist"
                    val fallbackUrl = "https://api.spotify.com/v1/search?q=${Uri.encode(looseQuery)}&type=track&limit=1"
                    val fallbackReq = Request.Builder()
                        .url(fallbackUrl)
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()

                    httpClient.newCall(fallbackReq).execute().use { fallbackResp ->
                        if (!fallbackResp.isSuccessful) return@withContext null
                        val json = JSONObject(fallbackResp.body?.string() ?: "")
                        val items = json.optJSONObject("tracks")?.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            return@withContext items.getJSONObject(0).optString("uri")
                        }
                    }
                    return@withContext null
                }

                val json = JSONObject(resp.body?.string() ?: "")
                val items = json.optJSONObject("tracks")?.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    return@withContext items.getJSONObject(0).optString("uri")
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Spotify search failed for '$title' by '$artist': ${e.message}")
            null
        }
    }

    /**
     * Enqueues a track URI directly into the user's active Spotify playback queue.
     * POST /v1/me/player/queue?uri=spotify:track:...
     */
    suspend fun enqueueTrack(trackUri: String, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.spotify.com/v1/me/player/queue?uri=${Uri.encode(trackUri)}"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                Log.i(TAG, "Enqueued track $trackUri into Spotify queue: code=${resp.code}")
                resp.isSuccessful || resp.code == 204
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue track $trackUri: ${e.message}")
            false
        }
    }

    private fun parseSuggestions(raw: String): List<SubstituteSuggestion> {
        val list = mutableListOf<SubstituteSuggestion>()
        try {
            // Find JSON array bounds
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
            Log.w(TAG, "Could not parse vibe substitute JSON array: ${e.message}")
        }
        return list
    }
}
