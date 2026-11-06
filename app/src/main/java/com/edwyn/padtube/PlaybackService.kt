package com.edwyn.padtube

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
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Keeps PadTube alive while a video plays with the screen locked or the app in
 * the background, and exposes play/pause on the lock screen and notification
 * shade through a MediaSession. The WebView itself stays in MainActivity;
 * playback commands are forwarded to it through [commandHandler].
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private lateinit var locks: PlaybackLocks

    // Headphones unplugged or Bluetooth lost: stop rather than switch to the speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (playing) commandHandler?.invoke(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locks = PlaybackLocks(this, "PadTube")
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "PadTube").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { commandHandler?.invoke(true) }
                override fun onPause() { commandHandler?.invoke(false) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> commandHandler?.invoke(true)
            ACTION_PAUSE -> commandHandler?.invoke(false)
            ACTION_UPDATE -> {
                playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                title = intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank) ?: title
            }
        }
        updateSession()
        locks.update(playing)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun updateSession() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "PadTube")
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java)
                .setAction(if (playing) ACTION_PAUSE else ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                this,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            ),
            if (playing) "暂停" else "播放",
            toggle
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText("PadTube")
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(toggleAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        locks.release()
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_UPDATE = "com.edwyn.padtube.UPDATE"
        private const val ACTION_PLAY = "com.edwyn.padtube.PLAY"
        private const val ACTION_PAUSE = "com.edwyn.padtube.PAUSE"
        private const val EXTRA_PLAYING = "playing"
        private const val EXTRA_TITLE = "title"

        private var playing = false
        private var title = "YouTube"

        /** Set by MainActivity: true = play, false = pause. */
        var commandHandler: ((Boolean) -> Unit)? = null

        fun update(context: Context, isPlaying: Boolean, videoTitle: String?) {
            context.startForegroundService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_UPDATE)
                    .putExtra(EXTRA_PLAYING, isPlaying)
                    .putExtra(EXTRA_TITLE, videoTitle)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
