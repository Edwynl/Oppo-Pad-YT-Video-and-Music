package com.edwyn.padtube.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver.handleIntent
import com.edwyn.padtube.PlaybackLocks
import com.edwyn.padtube.R
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class MusicPlaybackService : Service() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var locks: PlaybackLocks
    private var inForeground = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var title: String = "YouTube Music"
    private var artist: String = "PadMusic"
    private var artworkUrl: String = ""
    private var artwork: Bitmap? = null
    private var loadedArtworkUrl: String = ""
    private var positionMs: Long = 0
    private var durationMs: Long = 0
    private var isPlaying: Boolean = false

    // Headphones unplugged or Bluetooth lost: stop rather than switch to the speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isPlaying) PlaybackBridge.dispatch(PlaybackCommand.PAUSE)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        locks = PlaybackLocks(this, "PadMusic")
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "PadMusicSession").apply {
            setSessionActivity(contentPendingIntent())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = PlaybackBridge.dispatch(PlaybackCommand.PLAY)
                override fun onPause() = PlaybackBridge.dispatch(PlaybackCommand.PAUSE)
                override fun onSkipToNext() = PlaybackBridge.dispatch(PlaybackCommand.NEXT)
                override fun onSkipToPrevious() = PlaybackBridge.dispatch(PlaybackCommand.PREVIOUS)
                override fun onSeekTo(pos: Long) = PlaybackBridge.dispatchSeek(pos)
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            handleIntent(mediaSession, intent)
            return START_NOT_STICKY
        }

        val contentBefore = displayedContent()
        when (intent?.action) {
            ACTION_UPDATE -> {
                title = intent.getStringExtra(EXTRA_TITLE)?.take(180).orEmpty()
                    .ifBlank { "YouTube Music" }
                artist = intent.getStringExtra(EXTRA_ARTIST)?.take(180).orEmpty()
                    .ifBlank { "PadMusic" }
                artworkUrl = intent.getStringExtra(EXTRA_ARTWORK).orEmpty()
                positionMs = intent.getLongExtra(EXTRA_POSITION, 0)
                durationMs = intent.getLongExtra(EXTRA_DURATION, 0)
                isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false)
            }
            ACTION_PLAY -> PlaybackBridge.dispatch(PlaybackCommand.PLAY)
            ACTION_PAUSE -> PlaybackBridge.dispatch(PlaybackCommand.PAUSE)
            ACTION_NEXT -> PlaybackBridge.dispatch(PlaybackCommand.NEXT)
            ACTION_PREVIOUS -> PlaybackBridge.dispatch(PlaybackCommand.PREVIOUS)
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
        }

        ensureArtwork()
        updatePlaybackState()
        locks.update(isPlaying)
        // Position pings arrive every few seconds and only need the playback
        // state; rebuild metadata and the notification when something visible
        // changes. The first pass must still call startForeground.
        if (!inForeground || displayedContent() != contentBefore) {
            updateMetadata()
            showForegroundNotification()
            inForeground = true
        }
        return START_NOT_STICKY
    }

    private fun displayedContent() = listOf(title, artist, artworkUrl, durationMs, isPlaying)

    /** Resolve the cover bitmap for the current track, loading it off the main thread. */
    private fun ensureArtwork() {
        if (artworkUrl == loadedArtworkUrl) return
        loadedArtworkUrl = artworkUrl
        // Drop the previous track's cover now so a failed download cannot leave
        // it on screen under the new title.
        artwork = ArtworkCache.get(artworkUrl)
        if (artwork != null || artworkUrl.isBlank()) return
        val requestedUrl = artworkUrl
        Thread {
            val bitmap = ArtworkCache.load(requestedUrl)
            mainHandler.post {
                // Ignore if the track changed while we were downloading.
                if (!running || requestedUrl != artworkUrl) return@post
                if (bitmap == null) {
                    // Let the next update from the page try again.
                    loadedArtworkUrl = ""
                    return@post
                }
                artwork = bitmap
                updateMetadata()
                showForegroundNotification()
            }
        }.start()
    }

    private fun updateMetadata() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .apply {
                    artwork?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                    }
                }
                .build()
        )
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    state,
                    positionMs.coerceAtLeast(0),
                    if (isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun showForegroundNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
    }

    private fun buildNotification(): Notification {
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlaying) "暂停" else "播放"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(artwork)
            .setContentIntent(contentPendingIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(
                R.drawable.ic_previous,
                "上一首",
                servicePendingIntent(ACTION_PREVIOUS, 1)
            )
            .addAction(
                playPauseIcon,
                playPauseLabel,
                servicePendingIntent(playPauseAction, 2)
            )
            .addAction(
                R.drawable.ic_next,
                "下一首",
                servicePendingIntent(ACTION_NEXT, 3)
            )
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(servicePendingIntent(ACTION_STOP, 5))
            )
            .build()
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MusicActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playback_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
    }

    private fun stopPlayback() {
        PlaybackBridge.dispatch(PlaybackCommand.PAUSE)
        locks.release()
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(noisyReceiver)
        locks.release()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "padmusic_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_UPDATE = "com.edwyn.padtube.music.UPDATE"
        private const val ACTION_PLAY = "com.edwyn.padtube.music.PLAY"
        private const val ACTION_PAUSE = "com.edwyn.padtube.music.PAUSE"
        private const val ACTION_NEXT = "com.edwyn.padtube.music.NEXT"
        private const val ACTION_PREVIOUS = "com.edwyn.padtube.music.PREVIOUS"
        private const val ACTION_STOP = "com.edwyn.padtube.music.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_ARTWORK = "artwork"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_PLAYING = "playing"

        @Volatile
        private var running = false

        // Snapshot of the last update actually forwarded to the service, so that
        // frequent position pings don't rebuild the notification every second.
        @Volatile private var lastTitle = ""
        @Volatile private var lastArtist = ""
        @Volatile private var lastArtwork = ""
        @Volatile private var lastPlaying = false
        @Volatile private var lastSentAt = 0L

        fun update(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String,
            playing: Boolean,
            positionMs: Long,
            durationMs: Long
        ) {
            if (!playing && !running) return

            val metaChanged = title != lastTitle || artist != lastArtist ||
                artworkUrl != lastArtwork || playing != lastPlaying
            val positionDue = SystemClock.elapsedRealtime() - lastSentAt >= 4000L
            if (running && !metaChanged && !positionDue) return

            lastTitle = title
            lastArtist = artist
            lastArtwork = artworkUrl
            lastPlaying = playing
            lastSentAt = SystemClock.elapsedRealtime()

            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ARTWORK, artworkUrl)
                putExtra(EXTRA_POSITION, positionMs)
                putExtra(EXTRA_DURATION, durationMs)
                putExtra(EXTRA_PLAYING, playing)
            }
            // Android 12+ refuses to start a foreground service from the
            // background (e.g. playback began just after leaving the app). The
            // page reports again every second, so a later update retries once
            // the app is back in the foreground.
            try {
                if (running) {
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (_: IllegalStateException) {
            }
        }

        fun stop(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, MusicPlaybackService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
