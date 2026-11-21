package com.saad.lockin

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
import android.os.VibrationEffect
import android.os.Vibrator

/** Foreground service: shows the countdown notification, plays the phase sounds, buzzes on changes,
 *  logs finished focus blocks to the stats, and closes the session when the last window ends.
 *  The lock itself is the accessibility service. */
class LockService : Service() {

    companion object {
        private const val CHANNEL = "lockin_timer"
        private const val ID = 1

        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, LockService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val loop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            LockStore.settleStats(this@LockService, now)
            val w = LockStore.currentWindow(this@LockService, now)
            if (w == null) {
                LockStore.finishSession(this@LockService, now)   // end sound + clears the session
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            announce(w)
            getSystemService(NotificationManager::class.java).notify(ID, build(w))
            handler.postDelayed(this, (w.end - now + 300).coerceAtLeast(500))
        }
    }

    /** Sound + buzz once per phase. The index is stored, so a service restart or a reboot does not repeat it. */
    private fun announce(w: Window) {
        val idx = LockStore.windows(this).indexOf(w)
        val last = LockStore.announced(this)
        if (idx < 0 || idx == last) return
        LockStore.setAnnounced(this, idx)
        when {
            idx == 0 -> Sounds.play(this, "start")
            w.focus -> { Sounds.play(this, "break_end"); buzz() }
            else -> { Sounds.play(this, "break_start"); buzz() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lockin timer", NotificationManager.IMPORTANCE_LOW))
        val w = LockStore.currentWindow(this, System.currentTimeMillis())
        startForeground(ID, build(w))          // must always be called after startForegroundService
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    private fun build(w: Window?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (w == null) {
            b.setContentTitle("Lockin")
        } else {
            b.setContentTitle(if (w.focus) "Focus - locked" else "Break - unlocked")
                .setContentText("Session ends " + LockStore.hm(LockStore.sessionEnd(this)))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(w.end)
        }
        return b.build()
    }

    private fun buzz() {
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
