package com.alify.spotify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Universal Media Notification Listener and MediaSession Manager.
 *
 * Allows Ujify to detect what song is playing and issue transport control commands (such as skip)
 * across Spotify, YouTube Music, Apple Music, Deezer, Tidal, and local media players
 * WITHOUT requiring Spotify Web API tokens, Client IDs, or cloud approvals.
 */
class UjifyMediaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "UjifyMediaListener"

        // Known music streaming package identifiers
        val SUPPORTED_PLAYERS = mapOf(
            "com.spotify.music" to "Spotify",
            "com.spotify.lite" to "Spotify Lite",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.google.android.youtube" to "YouTube",
            "com.apple.android.music" to "Apple Music",
            "com.amazon.mp3" to "Amazon Music",
            "deezer.android.app" to "Deezer",
            "com.aspiro.tidal" to "Tidal",
            "com.soundcloud.android" to "SoundCloud"
        )

        val SPOTIFY_PACKAGES = setOf(
            "com.spotify.music",
            "com.spotify.lite"
        )

        @Volatile
        var activeController: MediaController? = null
            private set

        @Volatile
        var activePackageName: String? = null
            private set

        @Volatile
        var activePlayerName: String = "Standby"
            private set

        @Volatile
        var currentTrackTitle: String = ""
            private set

        @Volatile
        var currentArtistName: String = ""
            private set

        @Volatile
        var currentDurationMs: Long = 0L
            private set

        @Volatile
        var currentProgressMs: Long = 0L
            private set

        @Volatile
        var isPlaying: Boolean = false
            private set

        private val listeners = mutableListOf<() -> Unit>()

        fun addStateListener(listener: () -> Unit) {
            synchronized(listeners) {
                listeners.add(listener)
            }
        }

        fun removeStateListener(listener: () -> Unit) {
            synchronized(listeners) {
                listeners.remove(listener)
            }
        }

        private fun notifyStateChanged() {
            synchronized(listeners) {
                listeners.forEach { it.invoke() }
            }
        }

        /**
         * Checks if the user has granted Android Notification Listener access to Ujify.
         */
        fun isAccessGranted(context: Context): Boolean {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            return enabledPackages.contains(context.packageName)
        }

        /**
         * Returns an Intent to open the Android system screen where the user enables Notification Access.
         */
        fun createSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

        /**
         * Skips to the next track on whatever music player is currently active (Spotify, YT Music, etc.)
         * @return true if an active MediaController handled the command, false if none was active.
         */
        fun skipToNext(): Boolean {
            val controller = activeController ?: return false
            return try {
                controller.transportControls?.skipToNext()
                Log.d(TAG, "Native OS skip command dispatched to ${activePlayerName} ($activePackageName)")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dispatch skip to $activePackageName: ${e.message}")
                false
            }
        }

        /**
         * Pauses playback on the active music player.
         */
        fun pause(): Boolean {
            val controller = activeController ?: return false
            return try {
                controller.transportControls?.pause()
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Resumes playback on the active music player.
         */
        fun play(): Boolean {
            val controller = activeController ?: return false
            return try {
                controller.transportControls?.play()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private var sessionManager: MediaSessionManager? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        evaluateActiveControllers(controllers)
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Ujify Universal Media Notification Listener Connected")
        bindToActiveMediaSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Ujify Universal Media Notification Listener Disconnected")
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            // Ignore unregister errors on disconnect
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        notifyStateChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return

        if (SpotifyAuthManager.isSpotifyOnlyMode(this) && !SPOTIFY_PACKAGES.contains(pkg)) {
            return
        }

        // Check if the notification carries an active MediaSession Token
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }

        if (token != null) {
            try {
                val controller = MediaController(this, token)
                if (shouldPreferController(controller, pkg)) {
                    setActiveController(controller, pkg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not create MediaController from notification token: ${e.message}")
            }
        }
    }

    private fun bindToActiveMediaSessions() {
        try {
            val sm = sessionManager ?: return
            val componentName = ComponentName(this, UjifyMediaNotificationListener::class.java)
            sm.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            val controllers = sm.getActiveSessions(componentName)
            evaluateActiveControllers(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied accessing active sessions: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Error binding active media sessions: ${e.message}")
        }
    }

    private fun evaluateActiveControllers(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) return

        val spotifyOnly = SpotifyAuthManager.isSpotifyOnlyMode(this)
        val filtered = if (spotifyOnly) {
            controllers.filter { SPOTIFY_PACKAGES.contains(it.packageName) }
        } else {
            controllers
        }

        if (filtered.isEmpty()) {
            if (spotifyOnly && activePackageName != null && !SPOTIFY_PACKAGES.contains(activePackageName)) {
                try { activeController?.unregisterCallback(controllerCallback) } catch (_: Exception) {}
                activeController = null
                activePackageName = null
                activePlayerName = "Spotify"
                isPlaying = false
                notifyStateChanged()
            }
            return
        }

        // Prioritize actively playing sessions from known streaming apps (Spotify, YouTube Music, etc.)
        val playingController = filtered.firstOrNull { c ->
            val isPkgSupported = SUPPORTED_PLAYERS.containsKey(c.packageName)
            val isPlayingState = c.playbackState?.state == PlaybackState.STATE_PLAYING
            isPkgSupported && isPlayingState
        } ?: filtered.firstOrNull { c ->
            c.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: filtered.firstOrNull { c ->
            SUPPORTED_PLAYERS.containsKey(c.packageName)
        } ?: filtered.firstOrNull()

        if (playingController != null) {
            setActiveController(playingController, playingController.packageName)
        }
    }

    private fun shouldPreferController(newController: MediaController, pkg: String): Boolean {
        if (SpotifyAuthManager.isSpotifyOnlyMode(this) && !SPOTIFY_PACKAGES.contains(pkg)) {
            return false
        }
        val current = activeController ?: return true
        val currentPlaying = current.playbackState?.state == PlaybackState.STATE_PLAYING
        val newPlaying = newController.playbackState?.state == PlaybackState.STATE_PLAYING

        // If new controller is playing and current is not, prefer new
        if (newPlaying && !currentPlaying) return true

        // If both are playing, prefer known music players
        if (newPlaying && currentPlaying) {
            val newIsSupported = SUPPORTED_PLAYERS.containsKey(pkg)
            val currentIsSupported = SUPPORTED_PLAYERS.containsKey(activePackageName)
            if (newIsSupported && !currentIsSupported) return true
        }

        // Keep current if same package
        return current.packageName == pkg
    }

    private fun setActiveController(controller: MediaController, pkg: String) {
        if (activeController?.sessionToken == controller.sessionToken) {
            // Same controller, refresh states
            updateMetadata(controller.metadata)
            updatePlaybackState(controller.playbackState)
            return
        }

        try {
            activeController?.unregisterCallback(controllerCallback)
        } catch (e: Exception) {}

        activeController = controller
        activePackageName = pkg
        activePlayerName = SUPPORTED_PLAYERS[pkg] ?: (controller.packageName ?: "Music Player")

        try {
            controller.registerCallback(controllerCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register controller callback: ${e.message}")
        }

        updateMetadata(controller.metadata)
        updatePlaybackState(controller.playbackState)
        Log.i(TAG, "Active media controller bound to: $activePlayerName ($pkg)")
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        if (metadata != null) {
            currentTrackTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""
            currentArtistName = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: ""
            currentDurationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }
        notifyStateChanged()
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        if (state != null) {
            isPlaying = state.state == PlaybackState.STATE_PLAYING
            currentProgressMs = state.position
        } else {
            isPlaying = false
        }
        notifyStateChanged()
    }
}
