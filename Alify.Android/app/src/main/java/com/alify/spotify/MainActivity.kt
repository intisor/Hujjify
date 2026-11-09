package com.alify.spotify

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alify.spotify.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authManager: SpotifyAuthManager

    private val spotifySetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateAuthState()
            // If Client ID captured and not yet connected to Spotify, start OAuth flow immediately
            if (authManager.hasClientId && !authManager.isConnected()) {
                val authIntent = authManager.buildAuthIntent()
                if (authIntent != null) {
                    startActivity(authIntent)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = SpotifyAuthManager(this)

        if (!authManager.isConnected()) {
            val restored = PersistentVaultManager.restoreVaultIfAvailable(this)
            if (restored) {
                Toast.makeText(this, "Welcome back! Credentials restored from persistent vault.", Toast.LENGTH_LONG).show()
            }
        }

        requestNotificationPermission()
        com.streakkit.StreakKit.init(
            context = this,
            rules = listOf(
                com.streakkit.StreakRule(
                    slug = "hujjify_playback",
                    name = "Conscious Audio Streak",
                    target = 1,
                    cadence = "DAILY",
                    gracePeriodMinutes = 120,
                    maxFreezes = 3,
                    daysOff = listOf(5), // Friday rest day
                    freezeAutoEarnDays = 7, // Auto-earn +1 freeze every 7 consecutive days
                    restoreWindowDays = 90
                )
            )
        )
        com.streakkit.StreakKit.onStateChange { updateDashboardUI() }
        com.streakkit.StreakKit.ping(this, "hujjify_playback", value = 0)
        com.streakkit.StreakNotificationManager.createNotificationChannel(this)

        // If launched via Home Screen Widget or Urgent Notification, immediately open Streak Modal
        if (intent?.getBooleanExtra("OPEN_STREAKKIT_EMBED", false) == true) {
            com.streakkit.StreakKit.showModal(this, "hujjify_playback")
        }

        setupUI()
        handleRedirectIntent(intent)
        startUiPolling()
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        updateAuthState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirectIntent(intent)
        if (intent.getBooleanExtra("OPEN_STREAKKIT_EMBED", false)) {
            com.streakkit.StreakKit.showModal(this, "hujjify_playback")
        }
    }

    private fun handleRedirectIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if ((uri.scheme == "hujjify" || uri.scheme == "alify") && uri.host == "callback") {
            lifecycleScope.launch {
                val success = authManager.handleCallback(uri)
                if (success) {
                    Toast.makeText(this@MainActivity, "Connected to Spotify!", Toast.LENGTH_SHORT).show()
                    updateAuthState()
                } else {
                    Toast.makeText(this@MainActivity, "Spotify login failed. Please check your Client ID.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupUI() {
        // StreakKit Drop-In Modal (Tap -> Streak Modal, Long-Press -> Duolingo Winding Path UI)
        binding.cardStreak.setOnClickListener {
            com.streakkit.StreakKit.showModal(this, "hujjify_playback")
        }
        binding.cardStreak.setOnLongClickListener {
            com.streakkit.StreakKit.showDuolingoPath(
                activity = this,
                ruleSlug = "hujjify_playback",
                flag = "Music",
                unitTitle = "Conscious Listening",
                unitSubtitle = "Audit clean tracks & guard your ears"
            )
            true
        }

        // Settings Navigation
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Sanctuary Playlists Navigation
        val openSanctuaryPlaylists = {
            if (!authManager.isConnected()) {
                Toast.makeText(this, "Connect Spotify first to sanctify playlists", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, SanctuaryPlaylistActivity::class.java))
            }
        }
        binding.btnSanctuaryHeader.setOnClickListener { openSanctuaryPlaylists() }
        binding.btnSanctifyPlaylists.setOnClickListener { openSanctuaryPlaylists() }

        binding.btnAutoLinkSpotify.setOnClickListener {
            spotifySetupLauncher.launch(SpotifySetupActivity.createIntent(this))
        }

        binding.btnSetupClientId.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.tvChangeSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Unified AI Ethos Companion (DJ + Chat-to-Filter)
        fun isFilterDirective(input: String): Boolean {
            val lower = input.lowercase().trim()
            val directiveTriggers = listOf(
                "allow", "permit", "block", "filter", "don't skip", "dont skip",
                "do not skip", "stop skipping", "ignore", "strict", "rule", "censor", "ban"
            )
            return directiveTriggers.any { lower.startsWith(it) || lower.contains(" $it") }
        }

        binding.etDjPrompt.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val str = s?.toString()?.trim() ?: ""
                if (isFilterDirective(str)) {
                    binding.btnSpinDj.text = "Save Rule"
                    binding.tvDjStatus.text = "Filter rule detected"
                } else {
                    binding.btnSpinDj.text = "Spin"
                    binding.tvDjStatus.text = if (str.isNotBlank()) "Music vibe session" else "Vibe or filter rule"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        fun applyFilterDirective(directive: String) {
            val filterPrefs = getSharedPreferences("alify_filter_prefs", Context.MODE_PRIVATE)
            val current = filterPrefs.getString("custom_ethos_prompt", "") ?: ""
            val updated = if (current.isNotBlank()) "$current. $directive" else directive
            filterPrefs.edit().putString("custom_ethos_prompt", updated).apply()
            PersistentVaultManager.saveVault(this)
            AlifyMonitorService.invalidateCurrentTrack()

            binding.etDjPrompt.setText("")
            binding.tvDjStatus.text = "Rule applied"
            binding.tvActiveEthos.text = "Active Filter: ${updated.take(35)}..."
            Toast.makeText(this, "Ethos directive updated: Filter will now apply your rule.", Toast.LENGTH_SHORT).show()
        }

        // AI Ethos DJ Session Trigger
        fun triggerDj(prompt: String) {
            if (!authManager.isConnected()) {
                Toast.makeText(this, "Connect Spotify first to spin AI sessions", Toast.LENGTH_SHORT).show()
                return
            }
            binding.tvDjStatus.text = "Spinning session..."
            binding.btnSpinDj.isEnabled = false
            lifecycleScope.launch {
                val token = authManager.getValidAccessToken()
                if (token.isNullOrBlank()) {
                    binding.tvDjStatus.text = "Token error. Re-connect Spotify."
                    binding.btnSpinDj.isEnabled = true
                    return@launch
                }
                val filterEngine = FilterEngine.fromPreferences(this@MainActivity)
                val success = AiEthosDjService.generateAndPlaySession(
                    context = this@MainActivity,
                    userPrompt = prompt,
                    token = token,
                    filterEngine = filterEngine
                ) { status ->
                    runOnUiThread {
                        binding.tvDjStatus.text = status
                    }
                }
                binding.btnSpinDj.isEnabled = true
                if (success) {
                    binding.etDjPrompt.setText("")
                }
            }
        }

        binding.btnSpinDj.setOnClickListener {
            val text = binding.etDjPrompt.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                if (isFilterDirective(text)) {
                    applyFilterDirective(text)
                } else {
                    triggerDj(text)
                }
            } else {
                Toast.makeText(this, "Type a vibe ('Chill Afrobeat') or rule ('Allow romance')", Toast.LENGTH_SHORT).show()
            }
        }

        binding.chipWorkout.setOnClickListener { triggerDj("High-energy workout motivation & heavy beats") }
        binding.chipFocus.setOnClickListener { triggerDj("Calm lo-fi and acoustic instrumental focus") }
        binding.chipDrive.setOnClickListener { triggerDj("Peaceful night drive, melodic groove") }
        binding.chipGrateful.setOnClickListener { triggerDj("Uplifting soul & gratitude music") }

        // View Lyrics & Annotations Button
        binding.btnViewLyrics.setOnClickListener {
            val intent = LyricsActivity.createIntent(
                context = this,
                trackName = AlifyMonitorService.lastTrackName,
                artistName = AlifyMonitorService.lastArtistName,
                isFlagged = AlifyMonitorService.isLastFlagged,
                flagReason = AlifyMonitorService.lastFlagReason,
                flaggedLines = AlifyMonitorService.lastFlaggedLines,
                lyrics = AlifyMonitorService.lastLyrics,
                trackId = AlifyMonitorService.currentTrackId
            )
            startActivity(intent)
        }

        // Connect / Disconnect Button
        binding.btnLogin.setOnClickListener {
            if (authManager.isConnected()) {
                authManager.disconnect()
                AlifyMonitorService.stop(this)
                updateAuthState()
                Toast.makeText(this, "Disconnected from Spotify", Toast.LENGTH_SHORT).show()
            } else {
                if (!authManager.hasClientId) {
                    showClientIdRequiredDialog()
                    return@setOnClickListener
                }

                val authIntent = authManager.buildAuthIntent()
                if (authIntent != null) {
                    startActivity(authIntent)
                } else {
                    showClientIdRequiredDialog()
                }
            }
        }

        // Reset Dashboard Stats Button
        binding.btnResetStats.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset Protection Stats?")
                .setMessage("This will reset all audited song counters and clear your recent audit history.")
                .setPositiveButton("Reset") { _, _ ->
                    DashboardStatsManager.resetStats(this)
                    updateDashboardUI()
                    Toast.makeText(this, "Stats reset", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Toggle Audio Protection Service Button
        binding.btnToggleProtection.setOnClickListener {
            if (AlifyMonitorService.isRunning) {
                AlifyMonitorService.stop(this)
                binding.btnToggleProtection.text = "Start Protection"
                binding.btnToggleProtection.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
            } else {
                AlifyMonitorService.start(this)
                binding.btnToggleProtection.text = "Pause Protection"
                binding.btnToggleProtection.setBackgroundColor(ContextCompat.getColor(this, R.color.flag_red))
            }
        }

        updateAuthState()
    }

    private fun showClientIdRequiredDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Spotify Client ID Required")
            .setMessage(
                "Spotify requires each user to have their own free Developer Client ID so you have personal, unlimited access without developer whitelist restrictions.\n\n" +
                "Redirect URI: hujjify://callback\n\n" +
                "Tap '1-Tap Auto-Link' to automatically log in, create the app, and configure your keys in seconds."
            )
            .setPositiveButton("1-Tap Auto-Link") { _, _ ->
                spotifySetupLauncher.launch(SpotifySetupActivity.createIntent(this))
            }
            .setNeutralButton("Manual Setup") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAuthState() {
        val connected = authManager.isConnected()
        val hasClientId = authManager.hasClientId

        // Setup Notice Banner
        binding.cardSetupNotice.visibility = if (!hasClientId && !connected) View.VISIBLE else View.GONE

        // Active Ethos Summary
        val filterPrefs = getSharedPreferences("alify_filter_prefs", Context.MODE_PRIVATE)
        val profileOrdinal = filterPrefs.getInt("filter_profile", 0)
        val profileNames = listOf("Islamic", "Christian", "Family Safe", "General")
        val profileName = profileNames.getOrElse(profileOrdinal) { "Islamic" }
        val strictness = filterPrefs.getInt("filter_strictness", 10)
        val aiEnabled = filterPrefs.getBoolean("enable_gemini", true)
        val aiStatus = if (aiEnabled) "AI Enabled" else "Regex Only"
        binding.tvActiveEthos.text = "Active Filter: $profileName ($strictness/10) • $aiStatus"

        if (connected) {
            binding.tvConnectionStatus.text = "● Connected to Spotify"
            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.spotify_green))
            binding.btnLogin.text = "Disconnect Spotify"
            binding.btnToggleProtection.visibility = View.VISIBLE
            binding.btnSanctifyPlaylists.visibility = View.VISIBLE

            if (AlifyMonitorService.isRunning) {
                binding.btnToggleProtection.text = "Pause Protection"
                binding.btnToggleProtection.setBackgroundColor(ContextCompat.getColor(this, R.color.flag_red))
            } else {
                binding.btnToggleProtection.text = "Start Protection"
                binding.btnToggleProtection.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
            }
        } else {
            binding.tvConnectionStatus.text = if (hasClientId) "● Ready to Connect" else "● Setup Required"
            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.spotify_sub_text))
            binding.btnLogin.text = "Connect with Spotify"
            binding.btnToggleProtection.visibility = View.GONE
            binding.btnSanctifyPlaylists.visibility = View.GONE
            binding.tvCurrentTrack.text = "No track playing"
            binding.tvCurrentArtist.text = if (hasClientId) "Connect Spotify to start monitoring" else "Configure Spotify Developer Client ID first"
            binding.tvSafetyBadge.visibility = View.GONE
            binding.btnViewLyrics.visibility = View.GONE
        }
    }

    private fun startUiPolling() {
        lifecycleScope.launch {
            while (true) {
                if (AlifyMonitorService.isRunning) {
                    binding.tvCurrentTrack.text = AlifyMonitorService.lastTrackName
                    binding.tvCurrentArtist.text = AlifyMonitorService.lastArtistName
                    binding.tvSafetyBadge.visibility = View.VISIBLE
                    binding.tvSafetyBadge.text = AlifyMonitorService.lastStatusBadge

                    val hasTrack = AlifyMonitorService.lastTrackName.isNotEmpty() && AlifyMonitorService.lastTrackName != "No track playing"
                    if (hasTrack) {
                        binding.btnViewLyrics.visibility = View.VISIBLE
                        if (AlifyMonitorService.isLastFlagged) {
                            val flagCount = AlifyMonitorService.lastFlaggedLines.size
                            val countStr = if (flagCount > 0) " ($flagCount flagged)" else ""
                            binding.btnViewLyrics.text = "Inspect Flagged Verses$countStr"
                            binding.btnViewLyrics.strokeColor = ContextCompat.getColorStateList(this@MainActivity, R.color.flag_red)
                            binding.btnViewLyrics.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.flag_red_light))
                        } else {
                            binding.btnViewLyrics.text = "View Lyrics & Stance Audit"
                            binding.btnViewLyrics.strokeColor = ContextCompat.getColorStateList(this@MainActivity, R.color.spotify_green)
                            binding.btnViewLyrics.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.spotify_light_text))
                        }
                    } else {
                        binding.btnViewLyrics.visibility = View.GONE
                    }

                    if (AlifyMonitorService.isLastFlagged) {
                        binding.tvSafetyBadge.setBackgroundResource(R.drawable.badge_flagged)
                    } else {
                        binding.tvSafetyBadge.setBackgroundResource(R.drawable.badge_clean)
                    }
                }
                updateDashboardUI()
                delay(2000)
            }
        }
    }

    private fun updateDashboardUI() {
        val stats = DashboardStatsManager.getStats(this)
        binding.tvStatPlayed.text = stats.totalPlayed.toString()
        binding.tvStatClean.text = stats.totalClean.toString()
        binding.tvStatFlagged.text = stats.totalFlagged.toString()
        binding.tvStatNoLyrics.text = stats.totalNoLyrics.toString()

        // Update StreakKit Drop-In Indicators
        val streak = com.streakkit.StreakKit.getState(this)
        if (streak != null) {
            val count = streak.optInt("currentStreak", 0)
            binding.tvStreakCount.text = "$count Day Streak"
            val prog = streak.optJSONObject("todayProgress")
            val cur = prog?.optInt("current", 0) ?: 0
            val target = prog?.optInt("target", 3) ?: 3
            binding.streakProgressBar.max = target
            binding.streakProgressBar.progress = cur
            if (prog?.optBoolean("completed", false) == true) {
                binding.tvStreakStatusBadge.text = "Active Today"
                binding.tvStreakStatusBadge.setTextColor(0xFF22C55E.toInt())
                binding.tvStreakProgressSubtitle.text = "Goal Completed ($cur/$target songs)"
            } else {
                binding.tvStreakStatusBadge.text = "Pending ($cur/$target)"
                binding.tvStreakStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.spotify_sub_text))
                binding.tvStreakProgressSubtitle.text = "$cur / $target Clean songs observed today"
            }
            val freezes = streak.optInt("freezesAvailable", 2)
            binding.tvStreakFreezes.text = "Freezes: $freezes/2"
        }

        val recentTracks = DashboardStatsManager.getRecentTracks(this)
        if (recentTracks.isEmpty()) {
            binding.tvNoRecentActivity.visibility = View.VISIBLE
            binding.layoutRecentTracks.removeAllViews()
        } else {
            binding.tvNoRecentActivity.visibility = View.GONE
            binding.layoutRecentTracks.removeAllViews()

            for (entry in recentTracks.take(3)) {
                val row = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 8)
                    }
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 8, 16, 8)
                    setBackgroundResource(R.drawable.stat_box_background)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val intent = LyricsActivity.createIntent(
                            context = this@MainActivity,
                            trackName = entry.title,
                            artistName = entry.artist,
                            isFlagged = entry.statusBadge == "FLAGGED" || entry.statusBadge == "SKIPPED",
                            flagReason = entry.flagReason,
                            flaggedLines = entry.flaggedLines,
                            lyrics = entry.lyrics,
                            trackId = entry.trackId
                        )
                        startActivity(intent)
                    }
                }

                val textLayout = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    orientation = LinearLayout.VERTICAL
                }

                val titleTv = TextView(this).apply {
                    text = entry.title
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.spotify_light_text))
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                val artistTv = TextView(this).apply {
                    text = entry.artist
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.spotify_sub_text))
                    textSize = 10f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                textLayout.addView(titleTv)
                textLayout.addView(artistTv)

                val badgeTv = TextView(this).apply {
                    setPadding(12, 4, 12, 4)
                    textSize = 9f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.spotify_light_text))

                    when (entry.statusBadge) {
                        "SKIPPED" -> {
                            text = "Skipped"
                            setBackgroundResource(R.drawable.badge_skipped)
                        }
                        "FLAGGED" -> {
                            text = "Flagged"
                            setBackgroundResource(R.drawable.badge_flagged)
                        }
                        "NO_LYRICS" -> {
                            text = "No Lyrics"
                            setBackgroundResource(R.drawable.badge_no_lyrics)
                        }
                        else -> {
                            text = "Clean"
                            setBackgroundResource(R.drawable.badge_clean)
                        }
                    }
                }

                row.addView(textLayout)
                row.addView(badgeTv)
                binding.layoutRecentTracks.addView(row)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val currentVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "1.3.0"
                } catch (e: Exception) {
                    "1.3.0"
                }

                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/intisor/Alify/releases/latest")
                    .header("User-Agent", "Alify-Android")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = org.json.JSONObject(body)
                    val tagName = json.optString("tag_name")
                    val htmlUrl = json.optString("html_url")
                    if (tagName.isNotEmpty() && VersionUtil.isNewerVersion(tagName, currentVersion)) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.google.android.material.snackbar.Snackbar.make(
                                binding.root,
                                "Update available: $tagName (Current: v$currentVersion)",
                                com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                            ).setAction("Download") {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))
                                startActivity(browserIntent)
                            }.show()
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently ignore network failures on update check
            }
        }
    }
}
