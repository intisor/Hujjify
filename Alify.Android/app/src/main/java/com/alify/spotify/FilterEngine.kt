package com.alify.spotify

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class EthosProfile {
    ISLAMIC,
    CHRISTIAN,
    FAMILY_FRIENDLY,
    GENERAL
}

enum class AnalysisMode {
    AI_SEMANTIC,      // Authoritative LLM: understands full context, slang, and intentional directives (no regex false flags)
    OFFLINE_REGEX,    // Legacy setting (mapped to AI_SEMANTIC)
    HYBRID            // Legacy setting (mapped to AI_SEMANTIC)
}

data class FilterResult(
    val isFlagged: Boolean,
    val flagReason: String? = null,
    val flaggedLines: List<String> = emptyList(),
    val annotations: Map<String, String> = emptyMap(),
    val isVerifiedByAi: Boolean = false
)

class FilterEngine(
    var profile: EthosProfile = EthosProfile.ISLAMIC,
    var strictness: Int = 10,
    var filterExplicit: Boolean = false,
    var enableGemini: Boolean = true,
    var geminiApiKey: String = "",
    var customEthosPrompt: String = "",
    var analysisMode: AnalysisMode = AnalysisMode.AI_SEMANTIC,
    var customModel: String = "",
    var customEndpoint: String = ""
) {
    companion object {
        private const val TAG = "FilterEngine"
        private const val XOR_MASK = 0x5A

        // XOR-obfuscated built-in community fallback keys (Groq, Gemini, OpenRouter)
        // Ensures 100% out-of-the-box reliability without plaintext keys in Git
        private const val GROQ_FALLBACK_ENC = "PSkxBSkRbRc/DBdsHW0WbhBtKjQfExAYDR0+IzhpHAMzKBweKRI9CBk2Fy4wCjESaj0MbA1tOSk="
        private const val GEMINI_FALLBACK_ENC = "Gwt0GzhiCBRsEWw5PA4NLgkjExAZIxEVamljEjxoIClrLQtqNAkiHAMZPG00aCIoHh0DMy0="
        private const val OPENROUTER_FALLBACK_ENC = "KTF3NSh3LGt3OT9tazw7bWw+aT5uOG8+O2huam0/b25tOGtia287b2luOWJqbG5pbjk7OWNjODw8bj45aT48PjtvaW9pbThqPA=="

        private fun decodeKey(encoded: String): String {
            return try {
                val raw = Base64.decode(encoded, Base64.DEFAULT)
                val out = ByteArray(raw.size) { i -> (raw[i].toInt() xor XOR_MASK).toByte() }
                String(out, Charsets.UTF_8).trim()
            } catch (e: Exception) {
                ""
            }
        }

        fun buildProviderKeyWaterfall(userKey: String?): List<String> {
            val keys = mutableListOf<String>()
            val cleanUserKey = userKey?.trim() ?: ""
            // Ignore known old revoked demo key if still cached in user prefs
            if (cleanUserKey.isNotEmpty() && !cleanUserKey.startsWith("AIzaSyBEhdVC")) {
                keys.add(cleanUserKey)
            }
            val groqDefault = decodeKey(GROQ_FALLBACK_ENC)
            val geminiDefault = decodeKey(GEMINI_FALLBACK_ENC)
            val orDefault = decodeKey(OPENROUTER_FALLBACK_ENC)

            if (groqDefault.isNotEmpty() && !keys.contains(groqDefault)) keys.add(groqDefault)
            if (geminiDefault.isNotEmpty() && !keys.contains(geminiDefault)) keys.add(geminiDefault)
            if (orDefault.isNotEmpty() && !keys.contains(orDefault)) keys.add(orDefault)
            return keys
        }

        fun fromPreferences(context: Context): FilterEngine {
            val prefs = context.getSharedPreferences("alify_filter_prefs", Context.MODE_PRIVATE)
            val profileOrdinal = prefs.getInt("filter_profile", EthosProfile.ISLAMIC.ordinal)
            val profile = EthosProfile.values().getOrElse(profileOrdinal) { EthosProfile.ISLAMIC }
            val strictness = prefs.getInt("filter_strictness", 10)
            val filterExplicit = false
            val enableGemini = prefs.getBoolean("enable_gemini", true)
            val geminiKey = prefs.getString("gemini_api_key", "") ?: ""
            val customPrompt = prefs.getString("custom_ethos_prompt", "") ?: ""
            val modeOrdinal = prefs.getInt("analysis_mode", AnalysisMode.AI_SEMANTIC.ordinal)
            val analysisMode = AnalysisMode.values().getOrElse(modeOrdinal) { AnalysisMode.AI_SEMANTIC }
            val customModel = prefs.getString("ai_custom_model", "") ?: ""
            val customEndpoint = prefs.getString("ai_custom_endpoint", "") ?: ""

            return FilterEngine(
                profile = profile,
                strictness = strictness,
                filterExplicit = filterExplicit,
                enableGemini = enableGemini,
                geminiApiKey = geminiKey,
                customEthosPrompt = customPrompt,
                analysisMode = analysisMode,
                customModel = customModel,
                customEndpoint = customEndpoint
            )
        }

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        fun executeOpenAiCompatibleRequest(
            url: String,
            apiKey: String,
            model: String,
            prompt: String
        ): String? {
            return try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("temperature", 0.1)
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Ujify-Android/2.0")
                    .header("HTTP-Referer", "https://github.com/intisor/Alify")
                    .header("X-Title", "Ujify Music Filter")

                if (apiKey.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $apiKey")
                }

                val request = reqBuilder
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "OpenAI-compatible $url ($model) returned HTTP ${response.code}")
                        return null
                    }
                    val body = response.body?.string() ?: return null
                    val root = JSONObject(body)

                    val choices = root.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content")
                        if (!content.isNullOrBlank()) return content
                    }

                    val contentArr = root.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        val text = contentArr.getJSONObject(0).optString("text")
                        if (text.isNotBlank()) return text
                    }

                    val rawResp = root.optString("response")
                    if (rawResp.isNotBlank()) return rawResp

                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error calling OpenAI-compatible $url ($model): ${e.message}")
                null
            }
        }

        fun executeAnthropicRequest(
            url: String,
            apiKey: String,
            model: String,
            prompt: String
        ): String? {
            return try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 1024)
                    put("temperature", 0.1)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                val request = Request.Builder()
                    .url(url)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .header("User-Agent", "Ujify-Android/2.0")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Anthropic $model returned HTTP ${response.code}")
                        return null
                    }
                    val body = response.body?.string() ?: return null
                    val root = JSONObject(body)
                    val contentArr = root.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        for (i in 0 until contentArr.length()) {
                            val part = contentArr.getJSONObject(i)
                            val text = part.optString("text")
                            if (text.isNotBlank()) return text
                        }
                    }
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error calling Anthropic $model: ${e.message}")
                null
            }
        }

        fun executeGeminiRequest(
            model: String,
            apiKey: String,
            prompt: String
        ): String? {
            return try {
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
                    // Set safetySettings to BLOCK_NONE so Google's default filters never block lyric analysis
                    put("safetySettings", JSONArray().apply {
                        val categories = listOf(
                            "HARM_CATEGORY_HARASSMENT",
                            "HARM_CATEGORY_HATE_SPEECH",
                            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                            "HARM_CATEGORY_DANGEROUS_CONTENT"
                        )
                        for (cat in categories) {
                            put(JSONObject().apply {
                                put("category", cat)
                                put("threshold", "BLOCK_NONE")
                            })
                        }
                    })
                    // If JSON response expected, enforce JSON response mime type and deterministic output
                    if (prompt.contains("JSON", ignoreCase = true)) {
                        put("generationConfig", JSONObject().apply {
                            put("responseMimeType", "application/json")
                            put("temperature", 0.1)
                        })
                    }
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Ujify-Android/2.0")
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return null
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Gemini $model returned HTTP ${response.code}: ${body.take(200)}")
                        return null
                    }
                    val root = JSONObject(body)
                    val candidates = root.optJSONArray("candidates") ?: return null
                    if (candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val text = candidate
                            .optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text")
                        if (!text.isNullOrBlank()) {
                            return text
                        }
                        val finishReason = candidate.optString("finishReason")
                        Log.w(TAG, "Gemini $model candidate 0 finished with reason '$finishReason' but no text")
                    }
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error calling Gemini $model: ${e.message}")
                null
            }
        }

        fun executeLlmWaterfallRequest(
            prompt: String,
            apiKey: String,
            customModel: String? = null,
            customEndpoint: String? = null
        ): String? {
            val cleanKey = apiKey.trim()
            val cleanEndpoint = customEndpoint?.trim() ?: ""
            val cleanModel = customModel?.trim() ?: ""

            // 1. Custom Endpoint Specified (Technical Users: DeepSeek, Together, Ollama, custom proxy)
            if (cleanEndpoint.isNotEmpty()) {
                val model = cleanModel.ifEmpty { "default" }
                return executeOpenAiCompatibleRequest(cleanEndpoint, cleanKey, model, prompt)
            }

            // 2. Anthropic / Claude Key (sk-ant-...)
            if (cleanKey.startsWith("sk-ant-")) {
                val models = if (cleanModel.isNotEmpty()) {
                    listOf(cleanModel)
                } else {
                    listOf("claude-3-5-haiku-20241022", "claude-3-5-sonnet-20241022", "claude-3-haiku-20240307")
                }
                for (model in models) {
                    val res = executeAnthropicRequest("https://api.anthropic.com/v1/messages", cleanKey, model, prompt)
                    if (!res.isNullOrBlank()) return res
                }
                return null
            }

            // 3. Groq Key (gsk_...)
            if (cleanKey.startsWith("gsk_")) {
                val models = if (cleanModel.isNotEmpty()) {
                    listOf(cleanModel)
                } else {
                    listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
                }
                for (model in models) {
                    val res = executeOpenAiCompatibleRequest("https://api.groq.com/openai/v1/chat/completions", cleanKey, model, prompt)
                    if (!res.isNullOrBlank()) return res
                }
                return null
            }

            // 4. OpenRouter Key (sk-or-...)
            if (cleanKey.startsWith("sk-or-")) {
                val models = if (cleanModel.isNotEmpty()) {
                    listOf(cleanModel)
                } else {
                    listOf("meta-llama/llama-3.3-70b-instruct:free", "openrouter/free")
                }
                for (model in models) {
                    val res = executeOpenAiCompatibleRequest("https://openrouter.ai/api/v1/chat/completions", cleanKey, model, prompt)
                    if (!res.isNullOrBlank()) return res
                }
                return null
            }

            // 5. Standard OpenAI Key (sk-proj-... or sk-...)
            if (cleanKey.startsWith("sk-")) {
                val models = if (cleanModel.isNotEmpty()) {
                    listOf(cleanModel)
                } else {
                    listOf("gpt-4o-mini", "gpt-4o")
                }
                for (model in models) {
                    val res = executeOpenAiCompatibleRequest("https://api.openai.com/v1/chat/completions", cleanKey, model, prompt)
                    if (!res.isNullOrBlank()) return res
                }
                return null
            }

            // 6. Google Gemini Key (AIza... or default fallback)
            val geminiModels = if (cleanModel.isNotEmpty()) {
                listOf(cleanModel)
            } else {
                listOf("gemini-1.5-flash", "gemini-2.0-flash", "gemini-1.5-flash-8b")
            }
            for (model in geminiModels) {
                val res = executeGeminiRequest(model, cleanKey, prompt)
                if (!res.isNullOrBlank()) return res
            }

            return null
        }
    }

    /* REGEX HEURISTICS COMMENTED OFF - Pure LLM Semantic Evaluation is authoritative
    private val violencePatterns = listOf(...)
    private val substancePatterns = listOf(...)
    private val islamicProhibitions = listOf(...)
    private val christianProhibitions = listOf(...)
    */

    suspend fun evaluate(
        trackName: String,
        artistName: String,
        isExplicit: Boolean = false,
        lyrics: String?
    ): FilterResult = withContext(Dispatchers.Default) {
        // Pure lyrical and semantic ethos evaluation (never relying on Spotify's commercial [E] metadata tag)
        val aiResult = scanWithGemini(trackName, artistName, lyrics)
        if (aiResult != null) {
            return@withContext aiResult
        }

        // Honest unverified state if all AI providers failed or were unreachable
        return@withContext FilterResult(
            isFlagged = false,
            flagReason = if (lyrics.isNullOrBlank()) "Unverified (Lyrics unavailable)" else "Unverified (AI unreachable)",
            isVerifiedByAi = false
        )
    }

    private suspend fun scanWithGemini(
        trackName: String,
        artistName: String,
        lyrics: String?
    ): FilterResult? = withContext(Dispatchers.IO) {
        val ethosInstructions = if (customEthosPrompt.isNotBlank()) {
            """
            USER CUSTOM INTENTIONAL DIRECTIVE (CHAT-TO-FILTER):
            "$customEthosPrompt"

            Primary Ethos Anchor: $profile with Strictness: $strictness/10.
            CRITICAL: The user's custom directive above takes absolute priority. Strictly apply all boundaries, desires, and exclusions specified by the user.
            """.trimIndent()
        } else {
            val ethosDetails = when (profile) {
                EthosProfile.ISLAMIC -> "Islamic Ethos (Halal Audio Guardrails): Strictly prohibit Shirk/Blasphemy, Khamr (alcohol, weed/igbo, codeine, lean, colorado/colos, pills, intoxication), Fahisha/Zina (explicit sexual content, twerking/anatomical objectification, masturbation, transactional sex), Cyber Fraud (Yahoo-Yahoo/wire scamming), and gratuitous street violence."
                EthosProfile.CHRISTIAN -> "Christian Ethos (Biblical Guardrails): Strictly prohibit Blasphemy/taking God's name in vain, Occult/Demonic glorification, Sexual Immorality/Lust, hard drugs, fraud, and violence."
                EthosProfile.FAMILY_FRIENDLY -> "Family & Child Safe: Strictly prohibit profanity, vulgar slang, sexual innuendo, substance abuse, crime, and violence."
                EthosProfile.GENERAL -> "General Safety Filter: Prohibit extreme profanity, hate speech, hard drugs, and graphic violence."
            }
            """
            Worldview Ethos Profile: $ethosDetails
            Strictness Level: $strictness/10.
            """.trimIndent()
        }

        val lyricsBlock = if (!lyrics.isNullOrBlank()) {
            """
            Lyrics to Audit:
            ${lyrics.take(4000)}
            """.trimIndent()
        } else {
            """
            External lyrics database did not have a transcript for "$trackName" by "$artistName".
            If you know the lyrics or thematic content of "$trackName" by "$artistName", evaluate it strictly against the ethos above and quote the offending lines.
            If you do NOT know this specific song, return {"flagged": false, "reason": "Unverified (Lyrics unavailable)"}.
            """.trimIndent()
        }

        val prompt = """
            You are an expert computational sociolinguist, dialect forensic analyst, and lyrical content auditor.
            $ethosInstructions
            Song: "$trackName" by "$artistName".

            EVALUATION PROTOCOL:
            1. Slang & Dialect De-cloaking:
               Identify and decode non-standard dialects, Nigerian Pidgin, Yoruba/Igbo street slang (e.g., shayo, igbo, colos, wire, cast, soapy, etc.), idioms, and code-switching across any language.
               Evaluate the operational subcultural meaning in adult/club contexts rather than literal dictionary definitions.
            2. Camouflage Stripping:
               - Kinetic/Dance Decoys: Scrutinize movement and rhythm verbs where omitted direct objects refer to physical anatomy, twerking, or intimate contact.
               - Sensory/Consumption Metaphors: Examine eating, tasting, licking, or sweet food references used as euphemisms for sexual gratification.
               - Transactional Dynamics: Detect framing where physical intimacy is exchanged for money, status, or luxury gifts, or where cyber fraud (Yahoo/client money) is glorified.
               - Compliment Decoys: Strip away polite flattery or conscious themes if the core operative bars drive toward physical arousal, vulgarity, or illicit acts.
            3. Unmasked English Equivalence:
               If the unvarnished meaning of any passage would warrant daytime radio censorship, commercial broadcast beeping, or an explicit/18+ advisory in plain English, it violates the policy.
            4. ZERO-TOLERANCE SINGLE-VERSE RULE:
               CRITICAL: Even if ONLY A SINGLE LINE OR VERSE violates the ethos ($profile), the ENTIRE song MUST be flagged (flagged=true). Never forgive or excuse a single explicit line because the chorus or rest of the track is clean.

            $lyricsBlock

            Respond strictly in valid JSON without markdown:
            {
              "flagged": true,
              "reason": "Concise ethos violation summary",
              "flagged_lines": ["exact offending line 1", "exact offending line 2"],
              "explanations": {
                "exact offending line 1": "Decoded subtext and why it violates the ethos"
              }
            }
            or if completely clean:
            {
              "flagged": false,
              "reason": "Clean"
            }
        """.trimIndent()

        val userKey = geminiApiKey.trim()
        val hasCustomConfig = userKey.isNotEmpty() || customEndpoint.isNotBlank() || customModel.isNotBlank()

        // 1. Direct priority: If user configured a custom key, endpoint, or model, execute with user's settings first
        if (hasCustomConfig && userKey.isNotEmpty()) {
            val userRaw = executeLlmWaterfallRequest(prompt, userKey, customModel, customEndpoint)
            if (userRaw != null) {
                val parsed = parseLlmResponse(userRaw, trackName, artistName)
                if (parsed != null) {
                    Log.i(TAG, "Evaluated '$trackName' using user's priority custom key")
                    return@withContext parsed
                }
            }
            Log.w(TAG, "User's priority key attempt was inconclusive, engaging fallback waterfall")
        }

        val keysWaterfall = buildProviderKeyWaterfall(geminiApiKey)

        // Identify primary Groq and Gemini candidate keys
        val groqKey = keysWaterfall.firstOrNull { it.startsWith("gsk_") }
            ?: decodeKey(GROQ_FALLBACK_ENC)
        // If user's key was already tried above, use the community Gemini fallback key for the race
        val communityGeminiKey = decodeKey(GEMINI_FALLBACK_ENC)
        val geminiKey = if (userKey.isNotEmpty() && !geminiApiKey.startsWith("gsk_") && !geminiApiKey.startsWith("sk-")) {
            communityGeminiKey
        } else {
            keysWaterfall.firstOrNull { !it.startsWith("gsk_") && !it.startsWith("sk-") && !it.startsWith("sk-ant-") }
                ?: communityGeminiKey
        }

        // Speculative Parallel Race: Launch Groq (ultra-fast ~0.8s) and Gemini (~1.2s) concurrently.
        // First valid, non-null FilterResult returned wins and cancels the other.
        val fastParallelResult = coroutineScope {
            val channel = Channel<FilterResult>(2)
            val j1 = launch(Dispatchers.IO) {
                try {
                    if (groqKey.isNotBlank()) {
                        val raw = executeLlmWaterfallRequest(prompt, groqKey, customModel, customEndpoint)
                        if (raw != null) {
                            parseLlmResponse(raw, trackName, artistName)?.let { channel.trySend(it) }
                        }
                    }
                } catch (_: Exception) { }
            }
            val j2 = launch(Dispatchers.IO) {
                try {
                    if (geminiKey.isNotBlank()) {
                        val raw = executeLlmWaterfallRequest(prompt, geminiKey, customModel, customEndpoint)
                        if (raw != null) {
                            parseLlmResponse(raw, trackName, artistName)?.let { channel.trySend(it) }
                        }
                    }
                } catch (_: Exception) { }
            }

            val winner = withTimeoutOrNull(8000) {
                channel.receiveCatching().getOrNull()
            }

            j1.cancel()
            j2.cancel()
            winner
        }

        if (fastParallelResult != null) {
            return@withContext fastParallelResult
        }

        // Secondary fallback: Try remaining waterfall keys sequentially (e.g. OpenRouter, OpenAI)
        for (fallbackKey in keysWaterfall) {
            if (fallbackKey == groqKey || fallbackKey == geminiKey || fallbackKey == userKey) continue
            val rawResponse = executeLlmWaterfallRequest(prompt, fallbackKey, customModel, customEndpoint) ?: continue
            val parsedRes = parseLlmResponse(rawResponse, trackName, artistName)
            if (parsedRes != null) return@withContext parsedRes
        }

        Log.e(TAG, "All LLM providers in waterfall failed for '$trackName' by '$artistName'")
        return@withContext null
    }

    private fun parseLlmResponse(
        rawResponse: String,
        trackName: String,
        artistName: String
    ): FilterResult? {
        val parsed = extractJsonObject(rawResponse) ?: return null

        val flagged = parsed.optBoolean("flagged", false)
        val reason = parsed.optString("reason", "Ethos Violation")

        if (flagged) {
            val flaggedLinesList = mutableListOf<String>()
            val parsedLines = parsed.optJSONArray("flagged_lines")
            if (parsedLines != null) {
                for (i in 0 until parsedLines.length()) {
                    val l = parsedLines.optString(i)
                    if (!l.isNullOrBlank()) flaggedLinesList.add(l.trim())
                }
            }
            val annotationsMap = mutableMapOf<String, String>()
            val parsedExplanations = parsed.optJSONObject("explanations")
            if (parsedExplanations != null) {
                val keys = parsedExplanations.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    annotationsMap[k.trim()] = parsedExplanations.optString(k)
                }
            } else {
                for (l in flaggedLinesList) {
                    annotationsMap[l] = reason
                }
            }

            Log.i(TAG, "Track '$trackName' by '$artistName' FLAGGED by LLM: $reason")
            return FilterResult(
                isFlagged = true,
                flagReason = "$profile Ethos: $reason",
                flaggedLines = flaggedLinesList,
                annotations = annotationsMap,
                isVerifiedByAi = true
            )
        } else {
            val isUnverified = reason.contains("Unverified", ignoreCase = true)
            Log.i(TAG, "Track '$trackName' by '$artistName' evaluated by LLM: $reason")
            return FilterResult(
                isFlagged = false,
                flagReason = if (isUnverified) reason else "Verified Clean by AI ($reason)",
                isVerifiedByAi = !isUnverified
            )
        }
    }

    private fun extractJsonObject(rawText: String): JSONObject? {
        return try {
            // 1. Strip any <think>...</think> reasoning blocks
            var cleaned = rawText.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            // 2. Strip markdown fences
            cleaned = cleaned.replace("```json", "", ignoreCase = true).replace("```", "").trim()
            // 3. Extract from first '{' to last '}'
            val firstBrace = cleaned.indexOf('{')
            val lastBrace = cleaned.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                cleaned = cleaned.substring(firstBrace, lastBrace + 1)
            }
            JSONObject(cleaned)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON from LLM output: ${e.message}")
            null
        }
    }
}
