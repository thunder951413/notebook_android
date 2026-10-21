package io.github.notebook.android.tts

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
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import io.github.notebook.android.MainActivity
import io.github.notebook.android.NotebookApp
import io.github.notebook.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TtsPhase { Idle, Loading, Playing, Paused }

data class TtsState(
    val phase: TtsPhase = TtsPhase.Idle,
    val title: String = "",
    /** Zero-based piece being read, and how many pieces the text was cut into. */
    val index: Int = 0,
    val total: Int = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

internal class TtsQueue(val title: String, val segments: List<String>, val settings: TtsSettings)

/** The reader's handle on reading aloud; the work itself runs in [TtsPlaybackService]. */
object TtsPlayer {
    private val mutableState = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = mutableState.asStateFlow()

    @Volatile internal var pending: TtsQueue? = null
    @Volatile internal var service: TtsPlaybackService? = null

    internal fun publish(update: (TtsState) -> TtsState) = mutableState.update(update)

    fun start(context: Context, title: String, segments: List<String>, settings: TtsSettings) {
        if (segments.isEmpty()) return
        pending = TtsQueue(title, segments, settings)
        mutableState.value = TtsState(TtsPhase.Loading, title, 0, segments.size, settings.speed)
        context.applicationContext.startForegroundService(
            Intent(context.applicationContext, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_START),
        )
    }

    fun toggle() { service?.toggle() }
    fun next() { service?.skip(1) }
    fun previous() { service?.skip(-1) }
    fun stop() { service?.stopReading() }
    fun setSpeed(speed: Float) { service?.setSpeed(speed) ?: mutableState.update { it.copy(speed = speed) } }
    fun dismissError() = mutableState.update { it.copy(error = null) }
}

/**
 * Foreground media service: synthesizes the queue piece by piece through the
 * configured service and plays it, so reading continues with the screen off.
 */
class TtsPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = TtsClient()
    private var queue: TtsQueue? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var ready = false
    private var index = 0
    private var speed = 1f
    private var userPaused = false
    private var focusPaused = false
    private var ducked = false
    private val prefetch = HashMap<Int, Deferred<File>>()
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Suppress("DEPRECATION") private var wifiLock: WifiManager.WifiLock? = null
    private var noisyRegistered = false

    private val cacheDirectory get() = File(cacheDir, "tts")

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseByUser()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TtsPlayer.service = this
        audio = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "NotebookTts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumeByUser()
                override fun onPause() = pauseByUser()
                override fun onStop() = stopReading()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
            })
            isActive = true
        }
        val channel = NotificationChannel(CHANNEL, "语音朗读", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        cacheDirectory.deleteRecursively()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> skip(1)
            ACTION_PREVIOUS -> skip(-1)
            ACTION_STOP -> stopReading()
            else -> if (queue == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val next = TtsPlayer.pending
        TtsPlayer.pending = null
        // The foreground notification has to appear whatever happens next.
        enterForeground(next?.title.orEmpty(), TtsPhase.Loading)
        if (next == null) { stopReading(); return }
        job?.cancel()
        releasePlayer()
        cancelPrefetch(keep = emptySet())
        queue = next
        speed = next.settings.speed
        userPaused = false
        focusPaused = false
        acquireLocks()
        requestFocus()
        if (!noisyRegistered) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
        runFrom(0)
    }

    private fun runFrom(start: Int) {
        val current = queue ?: return
        job?.cancel()
        releasePlayer()
        job = scope.launch {
            var i = start.coerceIn(0, current.segments.lastIndex)
            while (i < current.segments.size) {
                index = i
                publish()
                prefetchAround(i, current)
                val file = try {
                    fileFor(i, current)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fail(e)
                    return@launch
                }
                try {
                    play(file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fail(e)
                    return@launch
                } finally {
                    file.delete()
                    prefetch.remove(i)
                }
                i++
            }
            finishReading()
        }
    }

    private fun prefetchAround(i: Int, current: TtsQueue) {
        val wanted = (i..minOf(i + PREFETCH_AHEAD, current.segments.lastIndex)).toSet()
        cancelPrefetch(keep = wanted)
        for (n in wanted) fileDeferred(n, current)
    }

    private fun fileDeferred(n: Int, current: TtsQueue) = prefetch.getOrPut(n) {
        scope.async(Dispatchers.IO) { synthesizeWithRetry(current, n) }
    }

    private suspend fun fileFor(i: Int, current: TtsQueue): File = fileDeferred(i, current).await()

    private suspend fun synthesizeWithRetry(current: TtsQueue, n: Int): File {
        var attempt = 0
        while (true) {
            try {
                val audio = client.synthesize(current.settings, current.segments[n], cacheDirectory)
                (application as NotebookApp).repository.addTtsUsedChars(audio.characters.toLong())
                return audio.file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Settings and credentials will not fix themselves; a flaky network might.
                if (e is IllegalArgumentException || attempt >= RETRIES) throw e
                attempt++
                delay(1_000L * attempt * attempt)
            }
        }
    }

    private fun cancelPrefetch(keep: Set<Int>) {
        val iterator = prefetch.entries.iterator()
        while (iterator.hasNext()) {
            val (n, deferred) = iterator.next()
            if (n in keep) continue
            iterator.remove()
            if (deferred.isCompleted && !deferred.isCancelled) {
                runCatching { deferred.getCompleted().delete() }
            } else {
                deferred.cancel()
            }
        }
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        val mp = MediaPlayer()
        player = mp
        ready = false
        continuation.invokeOnCancellation { runCatching { mp.release() }; if (player === mp) { player = null; ready = false } }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(file.path)
            mp.setOnPreparedListener { ready = true; syncPlayback() }
            mp.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
            mp.setOnErrorListener { _, what, extra ->
                if (continuation.isActive) continuation.resumeWithException(IOException("音频播放失败（$what/$extra）"))
                true
            }
            mp.prepareAsync()
        } catch (e: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        publish()
    }

    private fun releasePlayer() {
        val mp = player
        player = null
        ready = false
        if (mp != null) runCatching { mp.release() }
    }

    /** Starts or pauses the player so it matches what the user and the system want. */
    private fun syncPlayback() {
        val mp = player
        if (mp != null && ready) {
            val shouldPlay = !userPaused && !focusPaused
            runCatching {
                if (shouldPlay && !mp.isPlaying) {
                    applySpeed(mp)
                    mp.setVolume(if (ducked) .3f else 1f, if (ducked) .3f else 1f)
                    mp.start()
                } else if (!shouldPlay && mp.isPlaying) {
                    mp.pause()
                }
            }
        }
        publish()
    }

    private fun applySpeed(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    private fun phase(): TtsPhase {
        val mp = player
        return when {
            queue == null -> TtsPhase.Idle
            mp == null || !ready -> if (userPaused) TtsPhase.Paused else TtsPhase.Loading
            userPaused || focusPaused -> TtsPhase.Paused
            else -> TtsPhase.Playing
        }
    }

    private fun publish() {
        val current = queue ?: return
        val phase = phase()
        TtsPlayer.publish { it.copy(phase = phase, title = current.title, index = index, total = current.segments.size, speed = speed, error = null) }
        updateSession(phase, current)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(current.title, phase))
    }

    fun toggle() { if (userPaused) resumeByUser() else pauseByUser() }

    private fun pauseByUser() { userPaused = true; syncPlayback() }

    private fun resumeByUser() {
        userPaused = false
        focusPaused = false
        requestFocus()
        syncPlayback()
    }

    fun skip(delta: Int) {
        val current = queue ?: return
        val target = index + delta
        if (target >= current.segments.size) { stopReading(); return }
        userPaused = false
        runFrom(target.coerceAtLeast(0))
    }

    fun setSpeed(value: Float) {
        speed = value
        player?.takeIf { ready && it.isPlaying }?.let(::applySpeed)
        TtsPlayer.publish { it.copy(speed = value) }
    }

    fun stopReading() {
        job?.cancel()
        finishReading()
    }

    private fun fail(error: Throwable) {
        val message = error.localizedMessage ?: "朗读失败"
        Toast.makeText(applicationContext, "朗读失败：$message", Toast.LENGTH_LONG).show()
        teardown()
        TtsPlayer.publish { it.copy(phase = TtsPhase.Idle, error = message) }
    }

    private fun finishReading() {
        teardown()
        TtsPlayer.publish { TtsState(speed = it.speed) }
    }

    private fun teardown() {
        job?.cancel()
        job = null
        releasePlayer()
        cancelPrefetch(keep = emptySet())
        queue = null
        releaseLocks()
        abandonFocus()
        if (noisyRegistered) { runCatching { unregisterReceiver(noisy) }; noisyRegistered = false }
        cacheDirectory.deleteRecursively()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        releasePlayer()
        releaseLocks()
        abandonFocus()
        if (noisyRegistered) { runCatching { unregisterReceiver(noisy) }; noisyRegistered = false }
        runCatching { session.release() }
        cacheDirectory.deleteRecursively()
        if (TtsPlayer.service === this) TtsPlayer.service = null
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notebook:tts").apply { setReferenceCounted(false); acquire(MAX_SESSION_MS) }
        }
        if (wifiLock == null) {
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "notebook:tts").apply { setReferenceCounted(false); acquire() }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pauseByUser()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { focusPaused = true; syncPlayback() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; player?.setVolume(.3f, .3f) }
            AudioManager.AUDIOFOCUS_GAIN -> { focusPaused = false; ducked = false; player?.setVolume(1f, 1f); syncPlayback() }
        }
    }

    private fun requestFocus() {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener).build().also { focusRequest = it }
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun updateSession(phase: TtsPhase, current: TtsQueue) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.title.ifBlank { "朗读" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "第 ${index + 1} / ${current.segments.size} 段")
                .build(),
        )
        val state = when (phase) {
            TtsPhase.Playing -> PlaybackState.STATE_PLAYING
            TtsPhase.Loading -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackState.STATE_PLAYING) speed else 0f)
                .build(),
        )
    }

    private fun action(icon: Int, label: String, name: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(this, code, Intent(this, TtsPlaybackService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()

    private fun notification(title: String, phase: TtsPhase): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val total = queue?.segments?.size ?: 0
        val status = when (phase) {
            TtsPhase.Loading -> "正在合成语音…"
            TtsPhase.Paused -> "已暂停 · 第 ${index + 1} / $total 段"
            else -> "正在朗读 · 第 ${index + 1} / $total 段"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tts)
            .setContentTitle(title.ifBlank { "朗读" })
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "上一段", ACTION_PREVIOUS, 1))
            .addAction(
                if (phase == TtsPhase.Paused) action(android.R.drawable.ic_media_play, "继续", ACTION_TOGGLE, 2)
                else action(android.R.drawable.ic_media_pause, "暂停", ACTION_TOGGLE, 2),
            )
            .addAction(action(android.R.drawable.ic_media_next, "下一段", ACTION_NEXT, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "停止", ACTION_STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun enterForeground(title: String, phase: TtsPhase) {
        val n = notification(title, phase)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_START = "io.github.notebook.android.tts.START"
        const val ACTION_TOGGLE = "io.github.notebook.android.tts.TOGGLE"
        const val ACTION_NEXT = "io.github.notebook.android.tts.NEXT"
        const val ACTION_PREVIOUS = "io.github.notebook.android.tts.PREVIOUS"
        const val ACTION_STOP = "io.github.notebook.android.tts.STOP"
        private const val CHANNEL = "tts_playback"
        private const val NOTIFICATION_ID = 7301
        private const val PREFETCH_AHEAD = 2
        private const val RETRIES = 3
        private const val MAX_SESSION_MS = 6L * 60 * 60 * 1000
    }
}
