package com.alify.spotify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alify.spotify.databinding.ActivitySanctuaryPlaylistBinding
import com.alify.spotify.databinding.ItemSanctuaryPlaylistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SanctuaryPlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySanctuaryPlaylistBinding
    private lateinit var authManager: SpotifyAuthManager
    private val lrclibClient = LrclibClient()
    private var isMirroring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySanctuaryPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = SpotifyAuthManager(this)

        binding.btnBack.setOnClickListener {
            if (isMirroring) {
                Toast.makeText(this, "Sanctuary mirror in progress...", Toast.LENGTH_SHORT).show()
            } else {
                finish()
            }
        }

        binding.btnRefresh.setOnClickListener {
            if (!isMirroring) {
                loadPlaylists()
            }
        }

        loadPlaylists()
    }

    private fun loadPlaylists() {
        if (!authManager.isConnected()) {
            binding.pbLoadingPlaylists.visibility = View.GONE
            binding.tvEmptyPlaylists.visibility = View.VISIBLE
            binding.tvEmptyPlaylists.text = "Connect your Spotify account in Settings to view and sanctify your playlists."
            return
        }

        binding.pbLoadingPlaylists.visibility = View.VISIBLE
        binding.tvEmptyPlaylists.visibility = View.GONE
        binding.layoutPlaylistsContainer.removeAllViews()

        lifecycleScope.launch {
            val token = authManager.getValidAccessToken()
            if (token.isNullOrBlank()) {
                binding.pbLoadingPlaylists.visibility = View.GONE
                binding.tvEmptyPlaylists.visibility = View.VISIBLE
                binding.tvEmptyPlaylists.text = "Failed to authenticate with Spotify. Please check connection."
                return@launch
            }

            val playlists = PlaylistMirrorService.getUserPlaylists(token)
            binding.pbLoadingPlaylists.visibility = View.GONE

            if (playlists.isEmpty()) {
                binding.tvEmptyPlaylists.visibility = View.VISIBLE
                binding.tvEmptyPlaylists.text = "No playlists found in your Spotify account."
            } else {
                binding.tvEmptyPlaylists.visibility = View.GONE
                displayPlaylists(playlists, token)
            }
        }
    }

    private fun displayPlaylists(playlists: List<SpotifyPlaylistItem>, token: String) {
        binding.layoutPlaylistsContainer.removeAllViews()

        for (playlist in playlists) {
            val itemBinding = ItemSanctuaryPlaylistBinding.inflate(layoutInflater, binding.layoutPlaylistsContainer, false)
            itemBinding.tvPlaylistName.text = playlist.name
            itemBinding.tvPlaylistTrackCount.text = "${playlist.trackCount} tracks"

            // If it's already a Sanctuary playlist, style differently
            if (playlist.name.startsWith("🛡️ Sanctuary:")) {
                itemBinding.btnSanctify.text = "Sanctified ✓"
                itemBinding.btnSanctify.isEnabled = false
                itemBinding.btnSanctify.setBackgroundColor(0xFF2A2A2A.toInt())
                itemBinding.btnSanctify.setTextColor(0xFF888888.toInt())
            } else {
                itemBinding.btnSanctify.setOnClickListener {
                    if (!isMirroring) {
                        startMirroring(playlist, token)
                    } else {
                        Toast.makeText(this, "Another mirror operation is currently running", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            binding.layoutPlaylistsContainer.addView(itemBinding.root)
        }
    }

    private fun startMirroring(playlist: SpotifyPlaylistItem, token: String) {
        isMirroring = true
        binding.cardMirrorProgress.visibility = View.VISIBLE
        binding.btnOpenInSpotify.visibility = View.GONE
        binding.tvProgressTitle.text = "🛡️ Sanctifying: ${playlist.name}"
        binding.tvProgressStatus.text = "Starting audit..."
        binding.pbMirror.isIndeterminate = true
        binding.tvCleanPreserved.text = "✓ Preserved: 0"
        binding.tvVibeReplaced.text = "⚡ Swapped: 0"

        val filterEngine = FilterEngine.fromPreferences(this)

        lifecycleScope.launch {
            val newPlaylistId = PlaylistMirrorService.createSanctuaryMirror(
                context = this@SanctuaryPlaylistActivity,
                sourcePlaylistId = playlist.id,
                sourcePlaylistName = playlist.name,
                token = token,
                filterEngine = filterEngine,
                lrclibClient = lrclibClient
            ) { progress ->
                runOnUiThread {
                    binding.tvProgressStatus.text = progress.statusText
                    if (progress.total > 0) {
                        binding.pbMirror.isIndeterminate = false
                        binding.pbMirror.max = progress.total
                        binding.pbMirror.progress = progress.current
                    }
                    binding.tvCleanPreserved.text = "✓ Preserved: ${progress.cleanCount}"
                    binding.tvVibeReplaced.text = "⚡ Swapped: ${progress.replacedCount}"
                }
            }

            isMirroring = false

            if (newPlaylistId != null) {
                binding.tvProgressTitle.text = "✓ Sanctuary Mirror Created!"
                binding.tvProgressStatus.text = "All explicit tracks were dynamically swapped with clean sister vibes."
                binding.btnOpenInSpotify.visibility = View.VISIBLE
                binding.btnOpenInSpotify.setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:playlist:$newPlaylistId"))
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                    } catch (e: Exception) {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/playlist/$newPlaylistId"))
                        startActivity(webIntent)
                    }
                }
                // Refresh list so user sees new mirror
                loadPlaylists()
            } else {
                binding.tvProgressTitle.text = "⚠️ Sanctuary Creation Incomplete"
                binding.tvProgressStatus.text = "Unable to complete mirror. Please check your connection and try again."
            }
        }
    }
}
