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

    private val progressObserver: (MirrorProgress) -> Unit = { progress ->
        runOnUiThread { updateProgressUi(progress) }
    }

    private val completionObserver: (String?, String?) -> Unit = { newPlaylistId, errorReason ->
        runOnUiThread {
            if (newPlaylistId != null) {
                binding.tvProgressTitle.text = "Sanctuary Mirror Created!"
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
                loadPlaylists()
            } else if (errorReason != null) {
                binding.tvProgressTitle.text = "Sanctuary Creation Incomplete"
                binding.tvProgressStatus.text = errorReason
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySanctuaryPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = SpotifyAuthManager(this)

        binding.btnBack.setOnClickListener {
            // Background mirroring will safely continue regardless of navigation
            finish()
        }

        binding.btnRefresh.setOnClickListener {
            if (!PlaylistMirrorService.isMirroringActive) {
                loadPlaylists()
            } else {
                Toast.makeText(this, "Sanctuary mirror currently running in background...", Toast.LENGTH_SHORT).show()
            }
        }

        loadPlaylists()
    }

    override fun onResume() {
        super.onResume()
        PlaylistMirrorService.addProgressListener(progressObserver)
        PlaylistMirrorService.addCompletionListener(completionObserver)

        if (PlaylistMirrorService.isMirroringActive) {
            val activeName = PlaylistMirrorService.activePlaylistName ?: "Playlist"
            binding.cardMirrorProgress.visibility = View.VISIBLE
            binding.tvProgressTitle.text = "Sanctifying: $activeName"
            PlaylistMirrorService.currentProgress?.let { updateProgressUi(it) }
        } else if (authManager.isConnected()) {
            loadPlaylists()
        }
    }

    override fun onPause() {
        super.onPause()
        PlaylistMirrorService.removeProgressListener(progressObserver)
        PlaylistMirrorService.removeCompletionListener(completionObserver)
    }

    private fun updateProgressUi(progress: MirrorProgress) {
        binding.cardMirrorProgress.visibility = View.VISIBLE
        binding.tvProgressStatus.text = progress.statusText
        if (progress.total > 0) {
            binding.pbMirror.isIndeterminate = false
            binding.pbMirror.max = progress.total
            binding.pbMirror.progress = progress.current
        }
        binding.tvCleanPreserved.text = "Preserved: ${progress.cleanCount}"
        binding.tvVibeReplaced.text = "Swapped: ${progress.replacedCount}"
    }

    private fun loadPlaylists() {
        if (!authManager.isConnected()) {
            binding.pbLoadingPlaylists.visibility = View.GONE
            binding.tvEmptyPlaylists.visibility = View.VISIBLE
            binding.btnGrantPermissions.visibility = View.GONE
            binding.tvEmptyPlaylists.text = "Connect your Spotify account in Settings to view and sanctify your playlists."
            return
        }

        binding.pbLoadingPlaylists.visibility = View.VISIBLE
        binding.tvEmptyPlaylists.visibility = View.GONE
        binding.btnGrantPermissions.visibility = View.GONE
        binding.layoutPlaylistsContainer.removeAllViews()

        lifecycleScope.launch {
            val token = authManager.getValidAccessToken()
            if (token.isNullOrBlank()) {
                binding.pbLoadingPlaylists.visibility = View.GONE
                binding.tvEmptyPlaylists.visibility = View.VISIBLE
                binding.tvEmptyPlaylists.text = "Failed to authenticate with Spotify. Please check connection."
                return@launch
            }

            val result = PlaylistMirrorService.getUserPlaylists(token)
            binding.pbLoadingPlaylists.visibility = View.GONE

            when (result) {
                is PlaylistFetchResult.Success -> {
                    if (result.playlists.isEmpty()) {
                        binding.tvEmptyPlaylists.visibility = View.VISIBLE
                        binding.tvEmptyPlaylists.text = "No playlists found in your Spotify account."
                        binding.btnGrantPermissions.visibility = View.GONE
                    } else {
                        binding.tvEmptyPlaylists.visibility = View.GONE
                        binding.btnGrantPermissions.visibility = View.GONE
                        displayPlaylists(result.playlists, token)
                    }
                }
                is PlaylistFetchResult.PermissionRequired -> {
                    binding.tvEmptyPlaylists.visibility = View.VISIBLE
                    binding.tvEmptyPlaylists.text = "Spotify requires playlist permissions to read and clone your library. Tap below to grant access."
                    binding.btnGrantPermissions.visibility = View.VISIBLE
                    binding.btnGrantPermissions.setOnClickListener {
                        val authIntent = authManager.buildAuthIntent()
                        if (authIntent != null) {
                            startActivity(authIntent)
                        } else {
                            Toast.makeText(this@SanctuaryPlaylistActivity, "Configure Spotify Developer Client ID first", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                is PlaylistFetchResult.Error -> {
                    binding.tvEmptyPlaylists.visibility = View.VISIBLE
                    binding.tvEmptyPlaylists.text = "Error loading playlists: ${result.message}"
                    binding.btnGrantPermissions.visibility = View.GONE
                }
            }
        }
    }

    private fun displayPlaylists(playlists: List<SpotifyPlaylistItem>, token: String) {
        binding.layoutPlaylistsContainer.removeAllViews()

        for (playlist in playlists) {
            val itemBinding = ItemSanctuaryPlaylistBinding.inflate(layoutInflater, binding.layoutPlaylistsContainer, false)
            itemBinding.tvPlaylistName.text = playlist.name
            // Robust track display: if 0 or unknown, show 'Ready to Sanctify'
            itemBinding.tvPlaylistTrackCount.text = if (playlist.trackCount > 0) "${playlist.trackCount} tracks" else "Ready to Sanctify"

            // If it's already a Sanctuary playlist, style differently
            if (playlist.name.contains("Sanctuary:")) {
                itemBinding.btnSanctify.text = "Sanctified"
                itemBinding.btnSanctify.isEnabled = false
                itemBinding.btnSanctify.setBackgroundColor(0xFF2A2A2A.toInt())
                itemBinding.btnSanctify.setTextColor(0xFF888888.toInt())
            } else {
                itemBinding.btnSanctify.setOnClickListener {
                    if (!PlaylistMirrorService.isMirroringActive) {
                        startMirroring(playlist, token)
                    } else {
                        Toast.makeText(this, "A sanctuary mirror is currently running in the background", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            binding.layoutPlaylistsContainer.addView(itemBinding.root)
        }
    }

    private fun startMirroring(playlist: SpotifyPlaylistItem, token: String) {
        binding.cardMirrorProgress.visibility = View.VISIBLE
        binding.btnOpenInSpotify.visibility = View.GONE
        binding.tvProgressTitle.text = "Sanctifying: ${playlist.name}"
        binding.tvProgressStatus.text = "Starting background audit..."
        binding.pbMirror.isIndeterminate = true
        binding.tvCleanPreserved.text = "Preserved: 0"
        binding.tvVibeReplaced.text = "Swapped: 0"

        val filterEngine = FilterEngine.fromPreferences(this)
        PlaylistMirrorService.startSanctuaryMirrorBackground(
            context = applicationContext,
            playlist = playlist,
            token = token,
            filterEngine = filterEngine,
            lrclibClient = lrclibClient
        )
    }
}
