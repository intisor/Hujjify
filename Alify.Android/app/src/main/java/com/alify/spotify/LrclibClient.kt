package com.alify.spotify

import android.text.Html
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LrclibClient {

    var lastRawSyncedLyrics: String? = null
        private set

    companion object {
        private const val TAG = "LrclibClient"
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        // In-memory lyrics cache across service & activity calls
        private val lyricsCache = ConcurrentHashMap<String, String>()

        fun getCachedLyrics(artist: String, track: String): String? {
            val key = "${artist.trim().lowercase()}::${track.trim().lowercase()}"
            return lyricsCache[key]
        }

        fun invalidateCache(artist: String, track: String) {
            val rawKey = "${artist.trim().lowercase()}::${track.trim().lowercase()}"
            lyricsCache.remove(rawKey)
            val cleanKey = "${artist.trim().lowercase()}::${track.trim().replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), "").trim().lowercase()}"
            lyricsCache.remove(cleanKey)
        }

        fun isCandidateMatch(
            candidateArtist: String,
            candidateTitle: String,
            expectedArtist: String,
            expectedTitle: String
        ): Boolean {
            fun clean(s: String): String {
                return s.lowercase()
                    .replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), "")
                    .replace(Regex("""[^a-z0-9\s]"""), " ")
                    .split(Regex("""\s+"""))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }

            val cArt = clean(candidateArtist)
            val cTit = clean(candidateTitle)
            val eArt = clean(expectedArtist)
            val eTit = clean(expectedTitle)

            if (cArt.isBlank() || cTit.isBlank() || eArt.isBlank() || eTit.isBlank()) return false

            val cTitNoSpace = cTit.replace(" ", "").replace("0", "o")
            val eTitNoSpace = eTit.replace(" ", "").replace("0", "o")

            val titleMatches = cTit.contains(eTit) || eTit.contains(cTit) ||
                    cTitNoSpace.contains(eTitNoSpace) || eTitNoSpace.contains(cTitNoSpace)
            if (!titleMatches) return false

            val cArtNoSpace = cArt.replace(" ", "").replace("0", "o")
            val eArtNoSpace = eArt.replace(" ", "").replace("0", "o")

            val artMatches = cArt.contains(eArt) || eArt.contains(cArt) ||
                    cArtNoSpace.contains(eArtNoSpace) || eArtNoSpace.contains(cArtNoSpace) || run {
                val eFirst = eArt.split(" ").firstOrNull() ?: ""
                val cFirst = cArt.split(" ").firstOrNull() ?: ""
                eFirst.isNotBlank() && (cArt.contains(eFirst) || eFirst == cFirst || cArtNoSpace.contains(eFirst))
            }

            return artMatches
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Executes the comprehensive 6-stage Lyrics Waterfall:
     * 1. LRCLIB Direct Match (/api/get)
     * 2. LRCLIB Normalized Direct Match (/api/get with stripped 'feat.', '[Remaster]', etc.)
     * 3. LRCLIB Full-Text Search (/api/search?q=...)
     * 4. Direct Lightweight Genius Scraper (essential for Nigerian/Afrobeats & new releases)
     * 5. Public lyrics.ovh API Fallback
     * 6. Multi-Provider LLM Lyric Retrieval Fallback (Groq / Gemini / OpenRouter Oct 2026 models)
     */
    suspend fun getLyrics(
        artist: String,
        track: String,
        aiApiKey: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val rawArtist = artist.trim()
        val rawTrack = track.trim()

        if (rawArtist.isEmpty() || rawTrack.isEmpty()) return@withContext null

        val cacheKey = "${rawArtist.lowercase()}::${rawTrack.lowercase()}"
        lyricsCache[cacheKey]?.let {
            if (it.isNotBlank()) return@withContext it
        }

        val cleanTrack = normalizeTrackTitle(rawTrack)
        val cleanArtist = normalizeArtist(rawArtist)
        val cleanCacheKey = "${cleanArtist.lowercase()}::${cleanTrack.lowercase()}"
        lyricsCache[cleanCacheKey]?.let {
            if (it.isNotBlank()) return@withContext it
        }

        val baseTrack = cleanTrack.replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), "").trim()
        val dotFreeTrack = baseTrack.replace(".", "").trim()

        // Parallel Tier 1: Race LRCLIB and Genius Direct Scraper concurrently (first non-empty result wins)
        val tier1Lyrics = coroutineScope {
            val channel = Channel<String>(2)
            val j1 = launch(Dispatchers.IO) {
                try {
                    val res = fetchLrclibGet(rawArtist, rawTrack)
                        ?: (if (cleanTrack != rawTrack || cleanArtist != rawArtist) fetchLrclibGet(cleanArtist, cleanTrack) else null)
                        ?: (if (baseTrack != cleanTrack) fetchLrclibGet(cleanArtist, baseTrack) else null)
                        ?: (if (dotFreeTrack != baseTrack) fetchLrclibGet(cleanArtist, dotFreeTrack) else null)
                        ?: fetchLrclibSearch(cleanArtist, cleanTrack)
                        ?: (if (baseTrack != cleanTrack) fetchLrclibSearch(cleanArtist, baseTrack) else null)
                    if (!res.isNullOrBlank()) channel.trySend(res)
                } catch (_: Exception) { }
            }
            val j2 = launch(Dispatchers.IO) {
                try {
                    val res = fetchGeniusDirect(cleanArtist, cleanTrack)
                        ?: (if (baseTrack != cleanTrack) fetchGeniusDirect(cleanArtist, baseTrack) else null)
                        ?: (if (dotFreeTrack != baseTrack) fetchGeniusDirect(cleanArtist, dotFreeTrack) else null)
                    if (!res.isNullOrBlank()) channel.trySend(res)
                } catch (_: Exception) { }
            }

            val winner = withTimeoutOrNull(5500) {
                channel.receiveCatching().getOrNull()
            }

            j1.cancel()
            j2.cancel()
            winner
        }

        tier1Lyrics?.let {
            cacheAndReturn(cacheKey, cleanCacheKey, it)
            return@withContext it
        }

        // Tier 2 Fallback: Public lyrics.ovh
        fetchLyricsOvh(cleanArtist, cleanTrack)?.let {
            cacheAndReturn(cacheKey, cleanCacheKey, it)
            return@withContext it
        }

        // Tier 3 Fallback: Multi-Provider AI LLM Lyric Retrieval
        fetchLyricsFromLlm(cleanArtist, cleanTrack, aiApiKey)?.let {
            cacheAndReturn(cacheKey, cleanCacheKey, it)
            return@withContext it
        }

        Log.w(TAG, "No lyrics found across all 6 stages for '$rawTrack' by '$rawArtist'")
        return@withContext null
    }

    private fun cacheAndReturn(rawKey: String, cleanKey: String, lyrics: String) {
        if (lyrics.isNotBlank()) {
            lyricsCache[rawKey] = lyrics
            lyricsCache[cleanKey] = lyrics
        }
    }

    private fun normalizeTrackTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\s*[\(\[](feat|ft|with|prod)\.?\s+[^\)\]]+[\)\]]"""), "")
            .replace(Regex("""(?i)\s*[\(\[](remastered|remaster|live|deluxe|bonus|edit|radio edit|official audio|official video|version|mix|sped up|slowed)[^\)\]]*[\)\]]"""), "")
            .replace(Regex("""(?i)\s*-\s*(remastered|live|radio edit|single version|feat.*|ft\..*)"""), "")
            .trim()
    }

    private fun normalizeArtist(artist: String): String {
        return artist
            .split(Regex("""(?i)\s*(,|&|feat\.|ft\.|with|x)\s*"""))
            .firstOrNull()?.trim() ?: artist.trim()
    }

    private fun fetchLrclibGet(artist: String, track: String): String? {
        val url = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("artist_name", artist)
            ?.addQueryParameter("track_name", track)
            ?.build() ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Ujify-Android/2.0 (https://github.com/intisor/Alify)")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                extractLyricsFromJson(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchLrclibSearch(artist: String, track: String): String? {
        val query = "$artist $track".trim()
        val url = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.build() ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Ujify-Android/2.0 (https://github.com/intisor/Alify)")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val itemArtist = item.optString("artistName", "")
                    val itemTrack = item.optString("trackName", "")

                    if (isCandidateMatch(itemArtist, itemTrack, artist, track)) {
                        val lyrics = extractLyricsFromJsonObject(item)
                        if (!lyrics.isNullOrBlank()) {
                            Log.i(TAG, "Stage 3 (LRCLIB Search): Verified match '$itemTrack' by '$itemArtist'")
                            return lyrics
                        }
                    } else {
                        Log.d(TAG, "LRCLIB candidate rejected: '$itemTrack' by '$itemArtist' does not match '$track' by '$artist'")
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stage 4: Direct Lightweight Genius Scraper.
     * Queries Genius search API and extracts all data-lyrics-container="true" blocks.
     */
    private fun fetchGeniusDirect(artist: String, track: String): String? {
        return try {
            val queryVariants = mutableListOf("$artist $track".trim())
            val base = track.replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), "").trim()
            if (base != track && base.isNotBlank()) {
                queryVariants.add("$artist $base".trim())
            }
            val dotFree = base.replace(".", "").trim()
            if (dotFree != base && dotFree.isNotBlank()) {
                queryVariants.add("$artist $dotFree".trim())
            }

            var songUrl: String? = null

            for (query in queryVariants) {
                val searchUrl = "https://genius.com/api/search".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("q", query)
                    ?.build() ?: continue

                val searchRequest = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(searchRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val root = JSONObject(body)
                            val hits = root.optJSONObject("response")?.optJSONArray("hits")
                            if (hits != null) {
                                for (i in 0 until hits.length()) {
                                    val hit = hits.getJSONObject(i)
                                    val result = hit.optJSONObject("result") ?: continue
                                    val hitTitle = result.optString("title", "")
                                    val hitArtist = result.optJSONObject("primary_artist")?.optString("name", "") ?: ""
                                    val url = result.optString("url", "")

                                    if (url.isNotBlank() && url.contains("-lyrics") && isCandidateMatch(hitArtist, hitTitle, artist, track)) {
                                        Log.i(TAG, "Stage 4 (Genius Search): Verified candidate '$hitTitle' by '$hitArtist' (URL: $url)")
                                        songUrl = url
                                        break
                                    } else {
                                        Log.d(TAG, "Genius candidate rejected: '$hitTitle' by '$hitArtist' does not match '$track' by '$artist'")
                                    }
                                }
                            }
                        }
                    }
                }
                if (!songUrl.isNullOrBlank()) break
            }

            if (songUrl.isNullOrBlank()) return null

            val pageRequest = Request.Builder()
                .url(songUrl!!)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            httpClient.newCall(pageRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                val parsed = parseGeniusHtml(html)
                if (!parsed.isNullOrBlank() && parsed.length > 40) {
                    Log.i(TAG, "Stage 4 (Genius Direct): Fetched lyrics for '$track' by '$artist'")
                    return parsed
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Stage 4 Genius fallback error: ${e.message}")
            null
        }
    }

    private fun parseGeniusHtml(page: String): String? {
        val marker = "data-lyrics-container=\"true\""
        var idx = 0
        val extractedBlocks = mutableListOf<String>()

        while (true) {
            val pos = page.indexOf(marker, idx)
            if (pos == -1) break
            val startTagEnd = page.indexOf('>', pos)
            if (startTagEnd == -1) break

            var depth = 1
            var cur = startTagEnd + 1
            while (depth > 0 && cur < page.length) {
                val nextOpen = page.indexOf("<div", cur)
                val nextClose = page.indexOf("</div>", cur)
                if (nextClose == -1) break
                if (nextOpen != -1 && nextOpen < nextClose) {
                    depth++
                    cur = nextOpen + 4
                } else {
                    depth--
                    if (depth == 0) {
                        val blockHtml = page.substring(startTagEnd + 1, nextClose)
                        extractedBlocks.add(blockHtml)
                    }
                    cur = nextClose + 6
                }
            }
            idx = cur
        }

        if (extractedBlocks.isEmpty()) return null

        val cleanParts = mutableListOf<String>()
        for (block in extractedBlocks) {
            var b = block
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""<[^>]+>"""), "")
            @Suppress("DEPRECATION")
            b = Html.fromHtml(b.replace("\n", "<br>")).toString().trim()
            if (b.isNotBlank()) {
                cleanParts.add(b)
            }
        }

        return cleanParts.joinToString("\n\n").trim().takeIf { it.isNotBlank() }
    }

    private fun fetchLyricsOvh(artist: String, track: String): String? {
        val url = "https://api.lyrics.ovh/v1/$artist/$track".toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Ujify-Android/2.0")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val lyrics = json.optString("lyrics").trim()
                if (lyrics.isNotEmpty()) lyrics else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchLyricsFromLlm(artist: String, track: String, userApiKey: String?): String? {
        val baseTrack = track.replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), "").trim()
        val titleDescriptor = if (baseTrack != track && baseTrack.isNotBlank()) {
            "\"$baseTrack\" (also titled \"$track\")"
        } else {
            "\"$track\""
        }

        val prompt = """
            You are a comprehensive lyrical catalog.
            Provide the complete lyrics for:
            Song: $titleDescriptor
            Artist: "$artist"

            RULES:
            - If you know the song lyrics, output ONLY the lyrics text directly.
            - Do not include any explanations, greetings, or markdown code blocks.
            - If you are NOT confident or the lyrics are unknown, output ONLY: "LYRICS_NOT_FOUND".
        """.trimIndent()

        val keysToTry = FilterEngine.buildProviderKeyWaterfall(userApiKey)
        for (apiKey in keysToTry) {
            val result = FilterEngine.executeLlmWaterfallRequest(prompt, apiKey)
            if (!result.isNullOrBlank() && !result.contains("LYRICS_NOT_FOUND", ignoreCase = true) && result.length > 40) {
                return result.trim()
            }
        }
        return null
    }

    private fun tryFetchLyricsWithKey(prompt: String, apiKey: String): String? {
        val isGroq = apiKey.startsWith("gsk_")
        val isOpenRouter = apiKey.startsWith("sk-")

        if (isGroq || isOpenRouter) {
            val url = if (isGroq) {
                "https://api.groq.com/openai/v1/chat/completions"
            } else {
                "https://openrouter.ai/api/v1/chat/completions"
            }
            val models = if (isGroq) {
                listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
            } else {
                listOf("openrouter/free")
            }

            for (model in models) {
                try {
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            })
                        })
                        put("temperature", 0.0)
                    }

                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .header("User-Agent", "Ujify-Android/2.0")
                        .header("HTTP-Referer", "https://github.com/intisor/Alify")
                        .header("X-Title", "Ujify Lyrics Scraper")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        var rawText = json.optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("message")?.optString("content") ?: ""
                        rawText = rawText.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                        if (rawText.isNotEmpty() && !rawText.contains("LYRICS_NOT_FOUND", ignoreCase = true) && rawText.length > 40) {
                            return rawText
                        }
                    }
                } catch (e: Exception) {
                    // Try next model
                }
            }
        } else {
            val models = listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-flash-8b")
            for (model in models) {
                try {
                    val jsonPayload = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }

                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                        .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val rawText = json.optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")
                            ?.optJSONObject(0)?.optString("text")?.trim() ?: ""
                        if (rawText.isNotEmpty() && !rawText.contains("LYRICS_NOT_FOUND", ignoreCase = true) && rawText.length > 40) {
                            return rawText
                        }
                    }
                } catch (e: Exception) {
                    // Try next model
                }
            }
        }

        return null
    }

    private fun extractLyricsFromJson(jsonStr: String): String? {
        return try {
            extractLyricsFromJsonObject(JSONObject(jsonStr))
        } catch (e: Exception) {
            null
        }
    }

    private fun extractLyricsFromJsonObject(json: JSONObject): String? {
        val plain = json.optString("plainLyrics").trim()
        if (plain.isNotEmpty() && plain != "null") return plain

        val synced = json.optString("syncedLyrics").trim()
        if (synced.isNotEmpty() && synced != "null") {
            lastRawSyncedLyrics = synced
            return synced.replace(Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}]"), "").trim()
        }
        return null
    }
}
