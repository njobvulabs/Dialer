package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

/**
 * Foreground media service: keeps audio playing when the app is backgrounded
 * and exposes a MediaSession so the lockscreen / notification shade shows a
 * full mini player with Play/Pause + Prev/Next + Stop.
 */
class AudioPlaybackService : Service() {

    private var session: MediaSessionCompat? = null

    /**
     * B11: 1 s heartbeat so the lockscreen scrubber, the notification and any

     * Bluetooth/car head unit advance instead of freezing at 0:00. It only runs
     * while something is actually playing, and is torn down in [onDestroy] — a
     * permanent 1 s poll on a foreground service is a real battery cost.
     */
    private val ticker = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private val tick = object : Runnable {
        override fun run() {
            if (!AudioEngine.playing.value) {
                tickerRunning = false
                return
            }
            updateSession()
            updateNotification()
            ticker.postDelayed(this, 1_000L)
        }
    }

    /** Start/stop the heartbeat to match playback state. */
    private fun syncTicker() {
        val want = AudioEngine.playing.value
        if (want && !tickerRunning) {
            tickerRunning = true
            ticker.postDelayed(tick, 1_000L)
        } else if (!want && tickerRunning) {
            ticker.removeCallbacks(tick)
            tickerRunning = false
        }
    }

    /** Re-post the foreground notification in place (no new id, no flicker). */
    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AudioEngine.init(applicationContext)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        session = MediaSessionCompat(this, "NJDialerAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (!AudioEngine.playing.value) AudioEngine.onToggle?.invoke()
                }

                override fun onPause() {
                    if (AudioEngine.playing.value) AudioEngine.onToggle?.invoke()
                }

                override fun onSkipToNext() { AudioEngine.onNext?.invoke() }
                override fun onSkipToPrevious() { AudioEngine.onPrev?.invoke() }

                /** B11: dragging the lockscreen scrubber. */
                override fun onSeekTo(pos: Long) {
                    AudioEngine.seekTo(pos)
                    updateSession()
                }

                override fun onFastForward() {
                    AudioEngine.seekBy(SEEK_STEP_MS)
                    updateSession()
                }

                override fun onRewind() {
                    AudioEngine.seekBy(-SEEK_STEP_MS)
                    updateSession()
                }
                override fun onStop() {
                    startService(newIntent(this@AudioPlaybackService, ACTION_STOP))
                }
            })
            isActive = true
        }
    }

    private fun newIntent(context: Context, action: String): Intent =
        Intent(context, AudioPlaybackService::class.java).setAction(action)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                // Route through the engine so the screen's transport hooks (when it
                // is open) and the background defaults both stay in sync, and so
                // the notification icon is refreshed below.
                val toggle = AudioEngine.onToggle
                if (toggle != null) toggle() else AudioEngine.toggleDefault()
            }
            ACTION_NEXT -> AudioEngine.onNext?.invoke()
            ACTION_PREV -> AudioEngine.onPrev?.invoke()
            ACTION_FF -> AudioEngine.seekBy(SEEK_STEP_MS)
            ACTION_REW -> AudioEngine.seekBy(-SEEK_STEP_MS)
            ACTION_STOP -> {
                AudioEngine.cancelSleepTimer()
                val p = AudioEngine.player
                runCatching { p?.stop(); p?.release() }
                AudioEngine.releaseEqualizer()
                AudioEngine.player = null
                AudioEngine.playing.value = false
                AudioEngine.active.value = false
                AudioEngine.serviceLive = false
                AudioEngine.clearHandlers()
                ticker.removeCallbacks(tick)
                tickerRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        AudioEngine.serviceLive = true
        updateSession()
        startForeground(NOTIF_ID, buildNotification())
        syncTicker()
        return START_NOT_STICKY
    }

    fun updateSession() {
        val s = session ?: return
        val displayTitle = AudioEngine.title.value.ifBlank { "NJ-Dialer Audio" }
        val duration = AudioEngine.durationMs.value
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(
                MediaMetadataCompat.METADATA_KEY_ARTIST,
                AudioEngine.artist.value.ifBlank { "NJ-Dialer" }
            )
        // Album is omitted when the file has no tag: an empty string shows as a
        // blank second line on the lockscreen, which looks like a bug.
        if (AudioEngine.album.value.isNotBlank()) {
            meta.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, AudioEngine.album.value)
        }
        // B11: the file's own cover art, when it has one.
        AudioEngine.artwork?.let { bmp ->
            runCatching {
                meta.putBitmap(
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                    bmp
                )
            }
        }
        if (duration > 0) {
            meta.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        }
        s.setMetadata(meta.build())

        val state = if (AudioEngine.playing.value)
            PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_STOP
                )
                // B11: real position, and speed 0 while paused — that is what makes
                // a head unit show the correct state instead of a running clock.
                .setState(state, AudioEngine.positionMs(), if (AudioEngine.playing.value) 1.0f else 0f)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val togglePi = PendingIntent.getService(
            this, 1, newIntent(this, ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextPi = PendingIntent.getService(
            this, 2, newIntent(this, ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevPi = PendingIntent.getService(
            this, 4, newIntent(this, ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ffPi = PendingIntent.getService(
            this, 6, newIntent(this, ACTION_FF), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rewPi = PendingIntent.getService(
            this, 7, newIntent(this, ACTION_REW), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 3, newIntent(this, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentPi = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 5, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val playing = AudioEngine.playing.value
        val displayTitle = AudioEngine.title.value.ifBlank { "NJ-Dialer Audio" }
        val duration = AudioEngine.durationMs.value
        val pos = AudioEngine.positionMs()
        val displaySubtitle = buildString {
            val who = AudioEngine.artist.value.ifBlank { "NJ-Dialer" }
            append(who)
            if (duration > 0) {
                append(" · ")
                append(formatMs(pos))
                append(" / ")
                append(formatMs(duration))
            }
        }

        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session?.sessionToken)
            // prev / play / next stay in the collapsed row; rewind+forward only
            // appear when the notification is expanded.
            .setShowActionsInCompactView(0, 2, 4)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(displayTitle)
            .setContentText(displaySubtitle)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { contentPi?.let { setContentIntent(it) } }
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevPi)
            .addAction(android.R.drawable.ic_media_rew, "Rewind", rewPi)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                togglePi
            )
            .addAction(android.R.drawable.ic_media_ff, "Forward", ffPi)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            // B11: cover art in the notification itself, not only on the lockscreen.
            .apply {
                AudioEngine.artwork?.let { bmp ->
                    runCatching { setLargeIcon(bmp) }
                }
            }
            .setStyle(style)
            .build()
    }

    private fun formatMs(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        tickerRunning = false
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "audio_playback"
        const val NOTIF_ID = 4242
        const val ACTION_TOGGLE = "com.example.audio.TOGGLE"
        const val ACTION_STOP = "com.example.audio.STOP"
        const val ACTION_NEXT = "com.example.audio.NEXT"
        const val ACTION_PREV = "com.example.audio.PREV"
        const val ACTION_FF = "com.example.audio.FF"
        const val ACTION_REW = "com.example.audio.REW"

        /** B11: the FF / REW step the lockscreen and headset buttons use. */
        const val SEEK_STEP_MS = 10_000L

        fun newIntent(context: Context, action: String): Intent =
            Intent(context, AudioPlaybackService::class.java).setAction(action)

        fun update(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, AudioPlaybackService::class.java)
            )
        }
    }
}
