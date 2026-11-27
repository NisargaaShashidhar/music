package com.localbeats.app

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
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import java.io.File

class PlayerService : Service() {

    companion object {
        const val CH = "localbeats_playback"
        const val NID = 1
        const val A_TOGGLE = "localbeats.TOGGLE"
        const val A_NEXT = "localbeats.NEXT"
        const val A_PREV = "localbeats.PREV"
        const val A_CLOSE = "localbeats.CLOSE"
    }

    inner class LocalBinder : Binder() {
        fun get(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    private var mp: MediaPlayer? = null
    private var fails = 0
    var queue: List<Song> = emptyList()
    var index = -1
    var listener: (() -> Unit)? = null

    val current: Song? get() = queue.getOrNull(index)
    val isPlaying: Boolean get() = try { mp?.isPlaying == true } catch (e: Exception) { false }
    val position: Int get() = try { mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }

    private val attrs = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()

    private val am: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val focusReq: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { c ->
                if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
            .build()
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisy, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> toggle()
            A_NEXT -> next()
            A_PREV -> prev()
            A_CLOSE -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    fun playQueue(q: List<Song>, i: Int) {
        queue = q
        index = i
        fails = 0
        startCurrent()
    }

    private fun startCurrent() {
        val s = current ?: return
        releasePlayer()
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(attrs)
            p.setDataSource(this, s.uri)
            p.setOnCompletionListener { next() }
            p.setOnErrorListener { _, _, _ -> onFail(); true }
            p.prepare()
            mp = p
            am.requestAudioFocus(focusReq)
            p.start()
            fails = 0
            goForeground()
        } catch (e: Exception) {
            onFail()
        }
        listener?.invoke()
    }

    private fun onFail() {
        fails++
        if (queue.size > 1 && fails < queue.size) {
            index = (index + 1) % queue.size
            startCurrent()
        } else {
            releasePlayer()
            listener?.invoke()
        }
    }

    fun toggle() {
        if (mp == null) {
            if (current != null) startCurrent()
        } else if (isPlaying) pause() else resume()
    }

    fun pause() {
        try { mp?.pause() } catch (e: Exception) { }
        refreshNotification()
        listener?.invoke()
    }

    private fun resume() {
        am.requestAudioFocus(focusReq)
        try { mp?.start() } catch (e: Exception) { }
        refreshNotification()
        listener?.invoke()
    }

    fun next() {
        if (queue.isEmpty()) return
        index = (index + 1) % queue.size
        startCurrent()
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (position > 3000) {
            seekTo(0)
            return
        }
        index = (index - 1 + queue.size) % queue.size
        startCurrent()
    }

    fun seekTo(ms: Int) {
        try { mp?.seekTo(ms) } catch (e: Exception) { }
    }

    private fun releasePlayer() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    private fun stopPlayback() {
        releasePlayer()
        queue = emptyList()
        index = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.invoke()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NID, n)
    }

    private fun refreshNotification() {
        if (mp == null) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NID, buildNotification())
    }

    private fun act(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun action(icon: Int, label: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()

    private fun buildNotification(): Notification {
        val s = current
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s?.title ?: "LocalBeats")
            .setContentText(s?.artist ?: "")
            .setContentIntent(open)
            .setOngoing(isPlaying)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", act(A_PREV, 1)))
            .addAction(
                action(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    "Play/Pause", act(A_TOGGLE, 2)
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", act(A_NEXT, 3)))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", act(A_CLOSE, 4)))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        if (s != null) {
            val f = File(filesDir, "art/${s.id}.jpg")
            if (f.exists()) {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(f.path, o)?.let { b.setLargeIcon(it) }
            }
        }
        return b.build()
    }

    override fun onDestroy() {
        releasePlayer()
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        try { am.abandonAudioFocusRequest(focusReq) } catch (e: Exception) { }
        super.onDestroy()
    }
}
