package com.alify.spotify

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.*

data class DuckInterval(
    val startMs: Long,
    val endMs: Long,
    val lineText: String
)

object SurgicalDuckingManager {

    private const val TAG = "SurgicalDucking"
    private var duckJob: Job? = null
    private var isDucked = false
    private var originalVolume: Int = -1

    /**
     * Parses standard LRC timestamp format "[mm:ss.xx] Lyrics text" into milliseconds.
     */
    fun parseLrcTimestamps(syncedLrc: String): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)")
        for (line in syncedLrc.lines()) {
            val match = regex.find(line.trim()) ?: continue
            val mins = match.groupValues[1].toLongOrNull() ?: 0L
            val secs = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3]
            val ms = if (fraction.length == 2) fraction.toLong() * 10 else fraction.toLong()
            val totalMs = (mins * 60 + secs) * 1000 + ms
            val text = match.groupValues[4].trim()
            if (text.isNotBlank()) {
                result.add(Pair(totalMs, text))
            }
        }
        return result
    }

    /**
     * Calculates precise millisecond duck windows for flagged lines.
     * ZERO hardcoded data — derived strictly from the synced lyrics and evaluated lines.
     */
    fun calculateDuckIntervals(
        syncedLrc: String,
        flaggedLines: List<String>
    ): List<DuckInterval> {
        val intervals = mutableListOf<DuckInterval>()
        val parsed = parseLrcTimestamps(syncedLrc)
        if (parsed.isEmpty() || flaggedLines.isEmpty()) return emptyList()

        for (i in parsed.indices) {
            val (timestampMs, text) = parsed[i]
            val isLineFlagged = flaggedLines.any { flagged ->
                text.contains(flagged, ignoreCase = true) || flagged.contains(text, ignoreCase = true)
            }
            if (isLineFlagged) {
                val nextTimestamp = if (i + 1 < parsed.size) parsed[i + 1].first else timestampMs + 2500L
                val duration = (nextTimestamp - timestampMs).coerceIn(800L, 3000L)
                val start = (timestampMs - 100L).coerceAtLeast(0L)
                val end = start + duration
                intervals.add(DuckInterval(start, end, text))
            }
        }
        return intervals
    }

    /**
     * Monitors active playback position and ducks audio strictly during explicit intervals.
     * Song continues playing seamlessly without skipping.
     */
    fun scheduleSurgicalDucking(
        context: Context,
        scope: CoroutineScope,
        intervals: List<DuckInterval>,
        getCurrentPositionMs: () -> Long
    ) {
        duckJob?.cancel()
        if (intervals.isEmpty()) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        duckJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "Scheduled ${intervals.size} surgical duck intervals")
            while (isActive) {
                val pos = getCurrentPositionMs()
                val activeInterval = intervals.firstOrNull { pos in it.startMs..it.endMs }

                if (activeInterval != null && !isDucked) {
                    isDucked = true
                    originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    Log.d(TAG, "Surgical Duck Engaged: muted explicit line '${activeInterval.lineText}'")
                } else if (activeInterval == null && isDucked) {
                    isDucked = false
                    val targetVol = if (originalVolume > 0) originalVolume else 8
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                    Log.d(TAG, "Surgical Duck Restored: volume restored to $targetVol")
                }

                delay(120)
            }
        }
    }

    fun stop() {
        duckJob?.cancel()
        duckJob = null
        isDucked = false
    }
}
