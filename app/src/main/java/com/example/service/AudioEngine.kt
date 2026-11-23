package com.example.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

/**
 * Bridge between the audio UI, the mini player bar and the background service.
 * Observable states let Compose UIs react; transport callbacks are registered
 * by the Audio Player screen while it is open, and swapped to the built-in
 * [toggleDefault]/[stepDefault] handlers when the screen closes so lockscreen,
 * notification and mini-player controls keep working in the background.
 */
object AudioEngine {
    var player: android.media.MediaPlayer? = null

    private var appContext: android.content.Context? = null
    private var focusListener: android.media.AudioManager.OnAudioFocusChangeListener? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    /** Set when a transient focus loss paused us, so a focus gain can resume. */
    private var resumeOnFocusGain = false

    /** Set while another app is ducking us, so GAIN can lift the volume again. */
    private var ducked = false
    private var noisyReceiver: android.content.BroadcastReceiver? = null

    /** Cache an application context (call from any screen that touches audio). */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        loadEqualizerPrefs()
        registerNoisyReceiver(context.applicationContext)
    }

    /**
     * A headphone unplug raises ACTION_AUDIO_BECOMING_NOISY. Without this the
     * music keeps blasting out of the phone speaker (the video player already
     * guards this; audio used to not).
     */
    private fun registerNoisyReceiver(context: Context) {
        if (noisyReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                if (playing.value) pause()
            }
        }
        noisyReceiver = receiver
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager

    private fun acquireFocus() {
        val ctx = appContext ?: return
        val am = audioManager() ?: return
        if (focusListener == null) {
            focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnFocusGain = false
                        if (playing.value) pause()
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resumeOnFocusGain = true
                        if (playing.value) pause()
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        duck()
                    }
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                        unduck()
                        if (resumeOnFocusGain) {
                            resumeOnFocusGain = false
                            val p = player
                            runCatching {
                                if (p != null && !p.isPlaying) {
                                    p.start()
                                    playing.value = true
                                    refreshService()
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener!!)
                .build()
            am.requestAudioFocus(focusRequest!!)
        }
    }

    private fun abandonFocus() {
        val am = audioManager() ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusListener = null
        focusRequest = null
        ducked = false
        resumeOnFocusGain = false
    }

    /** Lower our own volume while a notification/voice prompt speaks over us. */
    private fun duck() {
        val p = player ?: return
        ducked = true
        runCatching { p.setVolume(0.2f, 0.2f) }
    }

    private fun unduck() {
        if (!ducked) return
        ducked = false
        runCatching { player?.setVolume(1f, 1f) }
    }

    private fun pause() {
        val p = player ?: return
        runCatching { if (p.isPlaying) p.pause() }
        playing.value = runCatching { p.isPlaying }.getOrDefault(false)
        // The 1 s service ticker stops as soon as we are paused, so a pause
        // triggered outside the UI (focus loss, headphone unplug, timer) must
        // refresh the notification itself or the shade keeps saying "playing".
        refreshService()
    }

    /**
     * Tear the session down completely (used when the currently playing file is
     * deleted). The player is only stopped/paused, not released: the screen's own
     * MediaPlayer instance is the same object and may be reused for the next track.
     */
    fun stopSession() {
        val p = player
        runCatching { if (p?.isPlaying == true) p.pause() }
        runCatching { p?.stop() }
        playing.value = false
        active.value = false
        serviceLive = false
        currentPath = null
        abandonFocus()
        cancelSleepTimer()
        val ctx = appContext
        if (ctx != null) {
            runCatching { ctx.stopService(Intent(ctx, AudioPlaybackService::class.java)) }
        }
    }

    val title = mutableStateOf("")
    /** B11: tag/artist/album read from the file's own tags, for the media session. */
    val artist = mutableStateOf("")
    val album = mutableStateOf("")
    /** Absolute path of the current track; the session resolves artwork/tags from it. */
    var currentPath: String? = null
    /** B11: track length in ms (0 when unknown), so the scrubber has a real end. */
    val durationMs = mutableStateOf(0L)
    /**
     * B11: a downscaled cover-art bitmap for the notification/lockscreen. Held as a
     * single small copy — an embedded 2000x2000 JPEG decoded at full size is ~16 MB,
     * which is a lot to keep alive on this device for a lock-screen decoration.
     */
    var artwork: android.graphics.Bitmap? = null

    /** Observable mirror of [artwork] for Compose screens (the field itself is not snapshot state). */
    val artworkState = mutableStateOf<android.graphics.Bitmap?>(null)

    val playing = mutableStateOf(false)

    /** True whenever a session exists (playing OR paused) — drives the mini bar. */
    val active = mutableStateOf(false)

    var serviceLive: Boolean = false

    /** Playback speed shared by the screen and background transport. */
    var playbackSpeed: Float = 1f

    private var equalizerEffect: Equalizer? = null
    private var equalizerSessionId = -1
    val equalizerPreset = mutableStateOf("Flat")
    val equalizerBands = mutableStateOf(FloatArray(5) { 0.5f })
    val equalizerRevision = mutableStateOf(0)

    /** Sleep timer: epoch deadline (0 = off) and "stop at the end of the queue". */
    var sleepDeadline = mutableStateOf(0L)
    var sleepAtEndOfQueue = mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    /** Snapshot of the playlist (file paths) + position for background control. */
    var queue: List<String> = emptyList()
    var queueIndex: Int = -1

    /** Background repeat mode (NONE / ONE / ALL) honoring the user's last choice. */
    enum class RepeatMode { NONE, ONE, ALL }

    var onToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null

    private fun refreshService() {
        val ctx = appContext ?: return
        if (!serviceLive) return
        runCatching { AudioPlaybackService.update(ctx) }
    }

    private fun notifyService() {
        val ctx = appContext ?: return
        runCatching { AudioPlaybackService.update(ctx) }
    }

    fun setQueue(paths: List<String>, index: Int) {
        // A different queue invalidates the shuffled pass: the bag holds indices
        // into the *previous* list, and reusing them picked (or repeated) tracks
        // from the new one.
        if (paths != queue) navigator.reset()
        queue = paths
        queueIndex = index
    }

    /**
     * Index of the playing track, resolved now rather than trusted.
     *
     * `queueIndex` is whatever the caller last passed and is legitimately -1 when
     * the track is not in that list (opened from the File Manager, or from a
     * filtered search that no longer contains it). Advancing from -1 silently
     * starts at the top of the queue, which is how a lock-screen Next could replay
     * the current song or jump somewhere arbitrary. Match on the path instead.
     */
    private fun currentQueueIndex(): Int {
        if (queueIndex in queue.indices) return queueIndex
        val path = currentPath ?: return 0
        val byPath = queue.indexOf(path)
        return if (byPath >= 0) byPath else 0
    }

    /** Play/Pause whatever is loaded; used when the UI screen is closed. */
    fun toggleDefault() {
        val p = player ?: return
        runCatching {
            if (p.isPlaying) p.pause() else p.start()
            playing.value = p.isPlaying
        }
        resumeOnFocusGain = false
        refreshService()
    }

    /**
     * Skip to the next/previous queue entry and start playing with a fresh player.
     * Used for background next/prev once the full-featured screen is gone.
     *
     * [auto] marks the track-ended path, which is the only one allowed to *stop*
     * (a manual press must always do something, so it wraps instead). Shuffle and
     * repeat are honoured independently by the shared [QueueNavigator] — same rule
     * as the open screen, so the two can't drift.
     */
    fun stepDefault(dir: Int, auto: Boolean = false) {
        if (queue.isEmpty()) return
        val cur = currentQueueIndex()
        if (lastRepeatMode == RepeatMode.ONE) {
            // Replay current track: restart from now, not from index stepping.
            playPath(queue[cur], cur)
            return
        }
        val repeatAll = lastRepeatMode == RepeatMode.ALL
        val next = if (dir >= 0) {
            navigator.next(queue.size, cur, lastShuffleOn, repeatAll, wrapAtEnd = !auto)
        } else {
            navigator.previous(queue.size, cur, lastShuffleOn) ?: cur
        }
        if (next == null || next !in queue.indices) {
            endQueue()
            return
        }
        playPath(queue[next], next)
    }

    private val navigator = QueueNavigator()

    /** Queue finished: hold at the end instead of silently looping. */
    private fun endQueue() {
        val p = player ?: return
        runCatching { if (p.isPlaying) p.pause() }
        playing.value = false
        if (sleepAtEndOfQueue.value) sleepAtEndOfQueue.value = false
        refreshService()
    }

    fun positionMs(): Long =
        runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    /** Seek by an absolute position, restarting if we were playing (MediaPlayer
     *  needs a start() after seekTo while paused or it silently stays silent). */
    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = ms.coerceIn(0L, durationMs.value.coerceAtLeast(0L))
        runCatching {
            val wasPlaying = p.isPlaying
            p.seekTo(target.toInt())
            if (wasPlaying) p.start()
        }
    }

    /** Fast-forward / rewind by a relative delta, as the lock-screen buttons send. */
    fun seekBy(deltaMs: Long) = seekTo(positionMs() + deltaMs)

    /** Apply the shared playback speed to whichever MediaPlayer is current. */
    fun applySpeed(target: android.media.MediaPlayer?) {
        if (target == null) return
        runCatching {
            target.playbackParams = target.playbackParams.setSpeed(playbackSpeed)
        }
    }

    fun setSpeed(value: Float) {
        playbackSpeed = value.coerceIn(0.5f, 3f)
        applySpeed(player)
        refreshService()
    }

    fun applyEqualizer() {
        syncEqualizer()
    }

    fun releaseEqualizer() {
        runCatching { equalizerEffect?.release() }
        equalizerEffect = null
        equalizerSessionId = -1
    }

    fun setEqualizerPreset(preset: String) {
        equalizerPreset.value = preset
        equalizerBands.value = bandsForPreset(preset)
        saveEqualizerPrefs()
        equalizerRevision.value = equalizerRevision.value + 1
        syncEqualizer()
    }

    fun setEqualizerBand(index: Int, value: Float) {
        if (index !in 0..4) return
        val updated = equalizerBands.value.copyOf()
        updated[index] = value.coerceIn(0f, 1f)
        equalizerBands.value = updated
        equalizerPreset.value = "Custom"
        saveEqualizerPrefs()
        equalizerRevision.value = equalizerRevision.value + 1
        syncEqualizer()
    }

    private fun bandsForPreset(preset: String): FloatArray = when (preset) {
        "Bass Boost" -> floatArrayOf(0.9f, 0.75f, 0.5f, 0.4f, 0.3f)
        "Vocal Booster" -> floatArrayOf(0.3f, 0.45f, 0.85f, 0.9f, 0.4f)
        "Rock" -> floatArrayOf(0.8f, 0.65f, 0.45f, 0.7f, 0.85f)
        "Pop" -> floatArrayOf(0.4f, 0.55f, 0.75f, 0.8f, 0.45f)
        "Jazz" -> floatArrayOf(0.7f, 0.6f, 0.45f, 0.55f, 0.75f)
        "Classical" -> floatArrayOf(0.45f, 0.5f, 0.55f, 0.65f, 0.8f)
        else -> FloatArray(5) { 0.5f }
    }

    private fun syncEqualizer(force: Boolean = false) {
        val p = player ?: return
        val session = runCatching { p.audioSessionId }.getOrDefault(0)
        if (session <= 0) return
        if (!force && equalizerEffect != null && equalizerSessionId == session) {
            applyEqualizerBands(equalizerEffect!!)
            return
        }
        releaseEqualizer()
        try {
            val eq = Equalizer(0, session)
            applyEqualizerBands(eq)
            equalizerEffect = eq
            equalizerSessionId = session
        } catch (_: Exception) {
            equalizerEffect = null
            equalizerSessionId = -1
        }
    }

    private fun applyEqualizerBands(eq: Equalizer) {
        runCatching {
            val bands = equalizerBands.value
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val min = range[0]
            val max = range[1]
            for (i in 0 until numBands) {
                val factor = if (i < bands.size) bands[i] else 0.5f
                val level = min + (factor.coerceIn(0f, 1f) * (max - min))
                eq.setBandLevel(i.toShort(), level.toInt().toShort())
            }
            eq.enabled = true
        }
    }

    private fun saveEqualizerPrefs() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
        val editor = prefs.edit().putString("eq_preset", equalizerPreset.value)
        equalizerBands.value.forEachIndexed { index, value ->
            editor.putFloat("eq_band$index", value)
        }
        editor.apply()
    }

    private fun loadEqualizerPrefs() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
        equalizerPreset.value = prefs.getString("eq_preset", "Flat") ?: "Flat"
        equalizerBands.value = FloatArray(5) { index ->
            prefs.getFloat("eq_band$index", 0.5f)
        }
    }

    // ---- Sleep timer -------------------------------------------------------

    /** Stop playback after [minutes]; 0 cancels. */
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        sleepAtEndOfQueue.value = false
        sleepDeadline.value = System.currentTimeMillis() + minutes * 60_000L
        scheduleSleepTick()
    }

    /** Stop when the current queue finishes instead of after a wall-clock delay. */
    fun startSleepAtEndOfQueue() {
        cancelSleepTimer()
        sleepAtEndOfQueue.value = true
    }

    fun cancelSleepTimer() {
        sleepDeadline.value = 0L
        sleepAtEndOfQueue.value = false
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
    }

    /**
     * Reads the "end of queue" flag and clears it, so the caller (screen or
     * engine) can stop exactly once when the last track ends.
     */
    fun consumeSleepAtEnd(): Boolean {
        val value = sleepAtEndOfQueue.value
        sleepAtEndOfQueue.value = false
        return value
    }

    private fun scheduleSleepTick() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val deadline = sleepDeadline.value
                if (deadline <= 0L) return
                if (System.currentTimeMillis() >= deadline) {
                    sleepDeadline.value = 0L
                    sleepRunnable = null
                    pause()
                } else {
                    mainHandler.postDelayed(this, 1_000L)
                }
            }
        }
        sleepRunnable = runnable
        mainHandler.postDelayed(runnable, 1_000L)
    }

    /**
     * B11: read the file's own tags + a small cover bitmap. Every call is guarded:
     * a malformed tag block must not take the foreground service down with it.
     */
    fun loadTagsFor(path: String) = loadTags(path)

    private fun loadTags(path: String) {
        artist.value = ""
        album.value = ""
        artwork = null
        artworkState.value = null
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                artist.value = retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
                    )
                    ?: ""
                album.value =
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?: ""
                val pic = retriever.embeddedPicture
                if (pic != null) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.size)
                    if (bmp != null) {
                        artwork = scaleArtwork(bmp)
                        artworkState.value = artwork
                    }
                }
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    /** Cap the long edge at 256 px — plenty for a lock screen, ~256 KB in ARGB_8888. */
    private fun scaleArtwork(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxEdge = 256
        if (src.width <= maxEdge && src.height <= maxEdge) return src
        val scale = maxEdge.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled != src) src.recycle()
        return scaled
    }

    private fun playPath(path: String, index: Int) {
        val old = player
        abandonFocus()
        releaseEqualizer()
        runCatching { old?.release() }
        try {
            val p = android.media.MediaPlayer()
            p.setDataSource(path)
            p.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setOnCompletionListener {
                playing.value = false
                stepDefault(1, auto = true)
            }
            p.prepare()
            p.start()
            acquireFocus()
            applySpeed(p)
            player = p
            syncEqualizer(force = true)
            queueIndex = index
            currentPath = path
            title.value = java.io.File(path).nameWithoutExtension
            durationMs.value = runCatching { p.duration.toLong().coerceAtLeast(0L) }
                .getOrDefault(0L)
            loadTags(path)
            playing.value = true
            active.value = true
            // The screen refreshes the notification from a LaunchedEffect, which is
            // cancelled once it leaves composition. An engine-driven advance (track
            // end, lock screen, notification) has to refresh it itself or the
            // notification/lockscreen keeps showing the previous track.
            notifyService()
        } catch (e: Exception) {
            player = null
            playing.value = false
            durationMs.value = 0L
        }
    }

    /** Install background-safe handlers after the audio screen disposes. */
    fun installDefaultHandlers() {
        onToggle = { toggleDefault() }
        onNext = { stepDefault(1) }
        onPrev = { stepDefault(-1) }
    }

    fun clearHandlers() {
        onToggle = null
        onNext = null
        onPrev = null
    }

    // ---- Persisted repeat/shuffle mirror (written by the Audio Player screen) ----

    private var lastRepeatMode: RepeatMode = RepeatMode.NONE
    private var lastShuffleOn: Boolean = false

    /** Call from the Audio Player screen whenever repeat/shuffle changes. */
    fun updateBackgroundMode(repeat: RepeatMode, shuffle: Boolean) {
        // The screen pushes this from a SideEffect, i.e. after *every*
        // recomposition (the position ticker alone fires several times a second).
        // Clearing the bag unconditionally threw away the whole shuffled pass
        // dozens of times a minute, so only react to an actual change.
        if (repeat == lastRepeatMode && shuffle == lastShuffleOn) return
        lastRepeatMode = repeat
        lastShuffleOn = shuffle
        navigator.reset()
    }
}
