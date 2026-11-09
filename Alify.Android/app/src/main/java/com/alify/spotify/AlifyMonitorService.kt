package com.alify.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AlifyMonitorService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var monitorLoopJob: Job? = null
    private var lyricsRecoveryJob: Job? = null
    private val httpClient = OkHttpClient()

    private lateinit var authManager: SpotifyAuthManager
    private val lrclibClient = LrclibClient()
    private var filterEngine = FilterEngine()

    private var audioManager: AudioManager? = null
    private var preDuckVolume: Int? = null
    private var fadeJob: Job? = null

    private var lastTrackId: String? = null
    private var lastRecordedTrackId: String? = null
    private var currentPlaybackProgressMs: Long = 0L
    private var progressTimestamp: Long = 0L

    private var lastSanctuaryAutoplayTimeMs = 0L
    private val preSwappedQueueTrackIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun getCurrentPositionMs(): Long {
        return currentPlaybackProgressMs + (System.currentTimeMillis() - progressTimestamp)
    }

    companion object {
        private const val TAG = "AlifyMonitorService"
        const val CHANNEL_ID = "alify_playback_protection"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SKIP = "com.alify.spotify.ACTION_SKIP"

        var isRunning = false
            private set

        var currentTrackId: String? = null
        var lastTrackName: String = "No track playing"
        var lastArtistName: String = ""
        var lastStatusBadge: String = "✓ Ready"
        var isLastFlagged: Boolean = false
        var lastLyrics: String? = null
        var lastFlagReason: String? = null
        var lastFlaggedLines: List<String> = emptyList()
        var lastAnnotations: Map<String, String> = emptyMap()

        // In-memory cache of evaluated tracks: trackKey -> Pair(FilterResult, lyrics)
        val auditCache = java.util.concurrent.ConcurrentHashMap<String, Pair<FilterResult, String?>>()

        fun invalidateCurrentTrack() {
            // Forces re-audit when user changes settings or requests re-check
            auditCache.clear()
            lastStatusBadge = "⏳ Re-auditing..."
        }

        fun skipCurrentTrack(context: Context) {
            val intent = Intent(context, AlifyMonitorService::class.java).apply {
                action = ACTION_SKIP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, AlifyMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlifyMonitorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        authManager = SpotifyAuthManager(this)
        filterEngine = FilterEngine.fromPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Active - Hujjify AI Protection On"))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SKIP) {
            scope.launch {
                val token = authManager.getValidAccessToken()
                if (token != null) {
                    skipTrack(token)
                }
            }
            return START_STICKY
        }

        filterEngine = FilterEngine.fromPreferences(this)
        lastTrackId = null // Force fresh audit on service start/restart
        lastRecordedTrackId = null
        if (monitorLoopJob?.isActive != true) {
            monitorLoopJob = scope.launch {
                monitorLoop()
            }
        }
        return START_STICKY
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            try {
                val token = authManager.getValidAccessToken()
                if (token != null) {
                    checkCurrentPlayback(token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitorLoop: ${e.message}", e)
            }
            delay(3000) // Poll every 3 seconds
        }
    }

    private suspend fun checkCurrentPlayback(token: String) {
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me/player")
            .header("Authorization", "Bearer $token")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 204 || response.body == null) return

            val body = response.body?.string() ?: return
            val json = JSONObject(body)
            val isPlaying = json.optBoolean("is_playing", false)
            if (!isPlaying) return

            currentPlaybackProgressMs = json.optLong("progress_ms", 0L)
            progressTimestamp = System.currentTimeMillis()

            val item = json.optJSONObject("item") ?: return
            val trackId = item.optString("id")
            val trackName = item.optString("name")
            val isExplicit = item.optBoolean("explicit", false)
            val artists = item.optJSONArray("artists")
            val artistName = if (artists != null && artists.length() > 0) {
                artists.getJSONObject(0).optString("name")
            } else "Unknown Artist"

            currentTrackId = trackId
            lastTrackName = trackName
            lastArtistName = artistName

            if (trackId == lastTrackId && lastStatusBadge != "⏳ Re-auditing...") return

            // Reload fresh preferences before every track audit
            filterEngine = FilterEngine.fromPreferences(this)

            val cacheKey = if (!trackId.isNullOrBlank()) trackId else "$artistName::$trackName".lowercase()
            val userOverride = SilentCorpusHarvester.getUserOverride(this, artistName, trackName)
            val cached = auditCache[cacheKey]

            val result: FilterResult
            val lyrics: String?

            if (userOverride != null && lastStatusBadge != "⏳ Re-auditing...") {
                result = userOverride
                lyrics = cached?.second ?: LrclibClient.getCachedLyrics(artistName, trackName)
                lastLyrics = lyrics
                lastTrackId = trackId
            } else if (cached != null && !cached.second.isNullOrBlank() && lastStatusBadge != "⏳ Re-auditing...") {
                // Instant zero-latency full cache hit (lyrics present & verified)
                result = cached.first
                lyrics = cached.second
                lastLyrics = lyrics
                lastTrackId = trackId
                Log.d(TAG, "Instant full cache hit for track '$trackName': flagged=${result.isFlagged}")
            } else if (cached != null && cached.second.isNullOrBlank() && lastStatusBadge != "⏳ Re-auditing...") {
                // Cached from queue lookahead without lyrics yet: use provisional result & start active recovery
                result = cached.first
                lyrics = null
                lastLyrics = null
                lastTrackId = trackId
                Log.d(TAG, "Provisional cache hit (no lyrics) for '$trackName'. Launching recovery worker.")
                launchLyricsRecovery(trackId, trackName, artistName, isExplicit, cacheKey, token)
            } else {
                lastStatusBadge = "⏳ Auditing with AI..."
                updateNotification("Auditing: $trackName - $artistName")

                // 1. Fetch Lyrics via parallel waterfall (LRCLIB + Genius parallel race + LLM fallback)
                val aiKey = filterEngine.geminiApiKey
                lyrics = lrclibClient.getLyrics(artistName, trackName, aiKey)

                lastLyrics = lyrics
                lastTrackId = trackId

                // 2. Evaluate via Speculative Multi-Provider LLM Waterfall (Groq || Gemini parallel race)
                result = filterEngine.evaluate(trackName, artistName, isExplicit, lyrics)
                auditCache[cacheKey] = Pair(result, lyrics)

                // Silently harvest evaluation into persistent SFT ledger
                SilentCorpusHarvester.recordEvaluation(
                    context = this@AlifyMonitorService,
                    trackName = trackName,
                    artistName = artistName,
                    profile = filterEngine.profile,
                    strictness = filterEngine.strictness,
                    customDirective = filterEngine.customEthosPrompt,
                    lyrics = lyrics,
                    result = result
                )

                // If lyrics were not found on this first pass, launch active background recovery worker!
                if (lyrics.isNullOrBlank()) {
                    launchLyricsRecovery(trackId, trackName, artistName, isExplicit, cacheKey, token)
                }
            }

            lastFlagReason = result.flagReason
            lastFlaggedLines = result.flaggedLines
            lastAnnotations = result.annotations

            // Record into Protection Statistics Dashboard (once per track playback)
            if (lastRecordedTrackId != trackId) {
                lastRecordedTrackId = trackId
                DashboardStatsManager.recordTrackAudit(
                    context = this@AlifyMonitorService,
                    trackId = trackId,
                    title = trackName,
                    artist = artistName,
                    isFlagged = result.isFlagged,
                    isSkipped = result.isFlagged,
                    hasLyrics = !lyrics.isNullOrBlank(),
                    flagReason = result.flagReason,
                    flaggedLines = result.flaggedLines,
                    lyrics = lyrics
                )
            }

            if (result.isFlagged) {
                val syncedLrc = lrclibClient.lastRawSyncedLyrics
                val filterPrefs = getSharedPreferences("alify_filter_prefs", Context.MODE_PRIVATE)
                val enableSurgicalDucking = filterPrefs.getBoolean("enable_surgical_ducking", true)

                // Surgical Synced Ducking (Radio Edit Mode):
                // If song only has 1 or 2 flagged lines and synced lyrics exist, duck the specific words instead of skipping!
                if (enableSurgicalDucking && !syncedLrc.isNullOrBlank() && result.flaggedLines.size in 1..2) {
                    val intervals = SurgicalDuckingManager.calculateDuckIntervals(syncedLrc, result.flaggedLines)
                    if (intervals.isNotEmpty()) {
                        isLastFlagged = false
                        lastStatusBadge = "🎧 Radio Edit: Ducking ${intervals.size} flagged words"
                        updateNotification("Radio Edit: Ducking words in $trackName")
                        SurgicalDuckingManager.scheduleSurgicalDucking(
                            context = this@AlifyMonitorService,
                            scope = scope,
                            intervals = intervals,
                            getCurrentPositionMs = { getCurrentPositionMs() }
                        )
                        return
                    }
                }

                SurgicalDuckingManager.stop()
                isLastFlagged = true
                lastStatusBadge = "✕ Flagged: ${result.flagReason}"
                updateNotification("Skipping: $trackName (${result.flagReason})")
                handleFlaggedTrack(trackName, artistName, result.flagReason ?: "Flagged", token)
            } else {
                isLastFlagged = false
                lastStatusBadge = when {
                    !lyrics.isNullOrBlank() && result.isVerifiedByAi -> "✓ Clean (AI Verified)"
                    lyrics.isNullOrBlank() -> "ℹ️ No lyrics indexed (Searching in background...)"
                    else -> "⚠️ ${result.flagReason ?: "Unverified"}"
                }
                updateNotification("Playing: $trackName - $artistName")
                SanctuaryMemoryBank.recordCleanTrack(this@AlifyMonitorService, trackName, artistName, trackId, "listen")
            }

            // Lookahead Queue Pre-Auditing: Quietly pre-audit the upcoming tracks in the background
            scope.launch {
                preAuditUpcomingQueue(token)
            }
        }
    }

    /**
     * Active Background Lyrics Recovery Worker:
     * Continues searching for missing lyrics in the background while the song is playing.
     * When lyrics are recovered:
     * 1. Re-evaluates immediately with pure AI.
     * 2. Updates auditCache & DashboardStats (converts NO_LYRICS to CLEAN or FLAGGED).
     * 3. If flagged: immediately engages 350ms Soft Glide Skip!
     */
    private fun launchLyricsRecovery(
        trackId: String,
        trackName: String,
        artistName: String,
        isExplicit: Boolean,
        cacheKey: String,
        token: String
    ) {
        lyricsRecoveryJob?.cancel()
        lyricsRecoveryJob = scope.launch {
            try {
                Log.i(TAG, "Starting background lyrics recovery worker for '$trackName' by '$artistName'")

                // Attempt 1: short 3s backoff to allow network jitter or external rate limits to clear
                delay(3000)
                if (!isActive || lastTrackId != trackId) return@launch

                LrclibClient.invalidateCache(artistName, trackName)
                val aiKey = filterEngine.geminiApiKey
                var recovered = lrclibClient.getLyrics(artistName, trackName, aiKey)

                // Attempt 2: wait 5s and retry with alternate search variants
                if (recovered.isNullOrBlank()) {
                    delay(5000)
                    if (!isActive || lastTrackId != trackId) return@launch
                    recovered = lrclibClient.getLyrics(artistName, trackName, aiKey)
                }

                if (!recovered.isNullOrBlank()) {
                    Log.i(TAG, "RECOVERY SUCCESS: Retrieved lyrics for '$trackName' (${recovered.length} chars)")

                    // Re-evaluate with AI using the full recovered lyrics
                    val newResult = filterEngine.evaluate(trackName, artistName, isExplicit, recovered)

                    // Update audit cache with the recovered lyrics
                    auditCache[cacheKey] = Pair(newResult, recovered)

                    // Update persistent Dashboard Statistics (replaces NO_LYRICS with verified status in-place)
                    DashboardStatsManager.updateTrackAudit(
                        context = this@AlifyMonitorService,
                        trackId = trackId,
                        title = trackName,
                        artist = artistName,
                        isFlagged = newResult.isFlagged,
                        isSkipped = newResult.isFlagged,
                        hasLyrics = true,
                        flagReason = newResult.flagReason,
                        flaggedLines = newResult.flaggedLines,
                        lyrics = recovered
                    )

                    SilentCorpusHarvester.recordEvaluation(
                        context = this@AlifyMonitorService,
                        trackName = trackName,
                        artistName = artistName,
                        profile = filterEngine.profile,
                        strictness = filterEngine.strictness,
                        customDirective = filterEngine.customEthosPrompt,
                        lyrics = recovered,
                        result = newResult
                    )

                    // If this song is still the currently playing track on Spotify:
                    if (lastTrackId == trackId) {
                        lastLyrics = recovered
                        lastFlagReason = newResult.flagReason
                        lastFlaggedLines = newResult.flaggedLines
                        lastAnnotations = newResult.annotations

                        if (newResult.isFlagged) {
                            isLastFlagged = true
                            lastStatusBadge = "✕ Flagged: ${newResult.flagReason}"
                            updateNotification("Skipping: $trackName (${newResult.flagReason})")
                            handleFlaggedTrack(trackName, artistName, newResult.flagReason ?: "Flagged", token)
                        } else {
                            isLastFlagged = false
                            lastStatusBadge = "✓ Clean (AI Verified)"
                            updateNotification("Playing: $trackName - $artistName")
                        }
                    }
                } else {
                    Log.d(TAG, "Background recovery concluded: no lyrics indexed for '$trackName'")
                    if (lastTrackId == trackId && lastStatusBadge.contains("Searching in background")) {
                        lastStatusBadge = "ℹ️ No lyrics indexed (Clean)"
                        updateNotification("Playing: $trackName - $artistName")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in background lyrics recovery: ${e.message}")
            }
        }
    }

    private suspend fun preAuditUpcomingQueue(token: String) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/queue")
                .header("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) return
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val queueArray = json.optJSONArray("queue") ?: return

                val countToPreAudit = minOf(4, queueArray.length())
                val filterPrefs = getSharedPreferences("alify_filter_prefs", Context.MODE_PRIVATE)
                val enableVibeSwap = filterPrefs.getBoolean("enable_vibe_swap", true)
                val profileName = when (filterEngine.profile) {
                    0 -> "Islamic"
                    1 -> "Christian"
                    2 -> "Family Safe"
                    else -> "Conscious"
                }

                for (i in 0 until countToPreAudit) {
                    val qItem = queueArray.optJSONObject(i) ?: continue
                    val qTrackId = qItem.optString("id")
                    val qTrackName = qItem.optString("name")
                    val qExplicit = qItem.optBoolean("explicit", false)
                    val qArtists = qItem.optJSONArray("artists")
                    val qArtistName = if (qArtists != null && qArtists.length() > 0) {
                        qArtists.getJSONObject(0).optString("name")
                    } else "Unknown Artist"

                    val qKey = if (qTrackId.isNotBlank()) qTrackId else "$qArtistName::$qTrackName".lowercase()
                    val (qResult, _) = auditCache.getOrPut(qKey) {
                        Log.d(TAG, "Pre-auditing upcoming track in queue: '$qTrackName' by '$qArtistName'")
                        val qLyrics = lrclibClient.getLyrics(qArtistName, qTrackName, filterEngine.geminiApiKey)
                        val evaluated = filterEngine.evaluate(qTrackName, qArtistName, qExplicit, qLyrics)
                        SilentCorpusHarvester.recordEvaluation(
                            context = this@AlifyMonitorService,
                            trackName = qTrackName,
                            artistName = qArtistName,
                            profile = filterEngine.profile,
                            strictness = filterEngine.strictness,
                            customDirective = filterEngine.customEthosPrompt,
                            lyrics = qLyrics,
                            result = evaluated
                        )
                        Pair(evaluated, qLyrics)
                    }

                    Log.i(TAG, "Upcoming track '$qTrackName' pre-audited: flagged=${qResult.isFlagged}")

                    // Lookahead Pre-Enqueue: If next immediate track (i == 0) is flagged, pre-enqueue clean sister track now!
                    if (i == 0 && qResult.isFlagged && enableVibeSwap && qTrackId.isNotBlank()) {
                        if (!preSwappedQueueTrackIds.contains(qTrackId)) {
                            preSwappedQueueTrackIds.add(qTrackId)
                            val sisterTrack = VibeSubstituteService.findCleanSisterTrack(
                                context = this@AlifyMonitorService,
                                flaggedTrack = qTrackName,
                                flaggedArtist = qArtistName,
                                flagReason = qResult.flagReason ?: "Flagged in upcoming queue",
                                profileName = profileName,
                                spotifyToken = token,
                                filterEngine = filterEngine
                            )
                            if (sisterTrack?.spotifyUri != null) {
                                VibeSubstituteService.enqueueTrack(sisterTrack.spotifyUri, token)
                                Log.i(TAG, "Autonomous Guardian: Pre-enqueued clean sister track '${sisterTrack.title}' for upcoming flagged track '$qTrackName'")
                            }
                        }
                    }
                }

                // Autonomous Sanctuary Autoplay:
                // When queue is low (<= 2 tracks remaining), prevent Spotify from drifting into unvetted explicit autoplay
                // by auto-extending with clean sister tracks matching the current session.
                val now = System.currentTimeMillis()
                if (queueArray.length() <= 2 && enableVibeSwap && (now - lastSanctuaryAutoplayTimeMs > 60000L)) {
                    val sessionTrack = lastTrackName
                    val sessionArtist = lastArtistName
                    if (sessionTrack.isNotBlank() && sessionTrack != "No track playing" && !isLastFlagged) {
                        lastSanctuaryAutoplayTimeMs = now
                        Log.i(TAG, "Autonomous Sanctuary Autoplay: Queue low (${queueArray.length()} tracks). Extending session based on '$sessionTrack' by '$sessionArtist'")
                        
                        val knownSeed = SanctuaryMemoryBank.getRecentCleanSeeds(5).firstOrNull { 
                            it.spotifyUri != null && !it.title.equals(sessionTrack, ignoreCase = true) 
                        }
                        if (knownSeed?.spotifyUri != null) {
                            val ok = VibeSubstituteService.enqueueTrack(knownSeed.spotifyUri, token)
                            if (ok) {
                                Log.i(TAG, "Autonomous Sanctuary Autoplay: Instant-enqueued verified clean seed '${knownSeed.title}' by '${knownSeed.artist}'")
                            }
                        } else {
                            val sisterTrack = VibeSubstituteService.findCleanSisterTrack(
                                context = this@AlifyMonitorService,
                                flaggedTrack = sessionTrack,
                                flaggedArtist = sessionArtist,
                                flagReason = "Extending session with matching clean sister vibes",
                                profileName = profileName,
                                spotifyToken = token,
                                filterEngine = filterEngine
                            )
                            if (sisterTrack?.spotifyUri != null) {
                                val ok = VibeSubstituteService.enqueueTrack(sisterTrack.spotifyUri, token)
                                if (ok) {
                                    Log.i(TAG, "Autonomous Sanctuary Autoplay: Successfully extended queue with '${sisterTrack.title}' by '${sisterTrack.artist}'")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Queue pre-auditing skipped or unavailable: ${e.message}")
        }
    }

    /**
     * Zero-Lag 350ms Soft Glide:
     * 1. Instantly ducks hardware STREAM_MUSIC to 0 (0ms latency, zero explicit leakage, no harsh audio pop).
     * 2. Issues Spotify skip command asynchronously (POST /v1/me/player/next).
     * 3. Once track switches, smoothly ramps volume back up to original level over ~200ms.
     */
    /**
     * AI Dynamic Vibe-Swap & Skip:
     * 1. Checks if AI Vibe-Swap is enabled in preferences.
     * 2. If enabled: prompts AI for clean sister tracks matching genre, BPM, and mood.
     * 3. Resolves the real Spotify track URI and injects it at the head of user's active Spotify queue.
     * 4. Dispatches zero-latency audio duck and skips the flagged track, creating a seamless transition.
     */
    private fun handleFlaggedTrack(
        trackName: String,
        artistName: String,
        flagReason: String,
        token: String
    ) {
        val filterPrefs = getSharedPreferences("alify_filter_prefs", Context.MODE_PRIVATE)
        val enableVibeSwap = filterPrefs.getBoolean("enable_vibe_swap", true)

        if (enableVibeSwap) {
            val profileName = when (filterEngine.profile) {
                0 -> "Islamic"
                1 -> "Christian"
                2 -> "Family Safe"
                else -> "Conscious"
            }

            scope.launch {
                try {
                    val alreadyPreEnqueued = currentTrackId != null && preSwappedQueueTrackIds.contains(currentTrackId)
                    if (!alreadyPreEnqueued) {
                        val sisterTrack = VibeSubstituteService.findCleanSisterTrack(
                            context = this@AlifyMonitorService,
                            flaggedTrack = trackName,
                            flaggedArtist = artistName,
                            flagReason = flagReason,
                            profileName = profileName,
                            spotifyToken = token,
                            filterEngine = filterEngine
                        )

                        if (sisterTrack?.spotifyUri != null) {
                            val enqueued = VibeSubstituteService.enqueueTrack(sisterTrack.spotifyUri, token)
                            if (enqueued) {
                                updateNotification("⚡ Swapped to: ${sisterTrack.title} - ${sisterTrack.artist}")
                                Log.i(TAG, "Dynamic vibe swap enqueued: '${sisterTrack.title}' by '${sisterTrack.artist}'")
                            }
                        }
                    } else {
                        Log.i(TAG, "Dynamic vibe swap: Sister track was already pre-enqueued by Autonomous Queue Guardian!")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Vibe substitution failed: ${e.message}")
                } finally {
                    skipTrack(token)
                }
            }
        } else {
            skipTrack(token)
        }
    }

    private fun skipTrack(token: String) {
        duckAudioImmediate()

        scope.launch {
            try {
                val request = Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/next")
                    .header("Authorization", "Bearer $token")
                    .post("".toRequestBody(null))
                    .build()

                httpClient.newCall(request).execute().close()
                Log.d(TAG, "Spotify skip dispatched successfully. Starting volume glide restore...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to skip track: ${e.message}", e)
            } finally {
                glideVolumeRestore()
            }
        }
    }

    private fun duckAudioImmediate() {
        try {
            val am = audioManager ?: return
            fadeJob?.cancel() // Stop any current fade-in coroutine

            // Cache user's volume level before ducking (only if not already ducked)
            if (preDuckVolume == null) {
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (currentVol > 0) {
                    preDuckVolume = currentVol
                }
            }

            // Mute music stream instantly without popping volume HUD (flags = 0)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            Log.d(TAG, "Instant hardware volume duck engaged (saved pre-duck volume: $preDuckVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not duck audio stream: ${e.message}")
        }
    }

    private fun glideVolumeRestore() {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            try {
                val am = audioManager ?: return@launch
                val target = preDuckVolume ?: return@launch
                if (target <= 0) {
                    preDuckVolume = null
                    return@launch
                }

                // Short 50ms buffer for Spotify audio subsystem to lock onto the new song stream
                delay(50)

                // Smoothly step volume back up over ~150-200ms
                val steps = minOf(4, target)
                val stepDelay = 150L / steps.coerceAtLeast(1)

                for (i in 1..steps) {
                    delay(stepDelay)
                    if (!isActive) return@launch
                    val intermediate = (target * i) / steps
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, intermediate, 0)
                }

                // Ensure exact restoration to user's original volume
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Log.d(TAG, "Audio volume smoothly restored to level $target")
            } catch (e: Exception) {
                Log.w(TAG, "Error during volume glide restore: ${e.message}")
            } finally {
                preDuckVolume = null
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hujjify Audio Protection")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        lyricsRecoveryJob?.cancel()
        serviceJob.cancel()
        // Safety: If service is destroyed while volume was ducked, restore user's volume
        try {
            preDuckVolume?.let { vol ->
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
            }
        } catch (_: Exception) {}
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
