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

/** Keeps the PC link alive in the background (small silent notification showing the link status). */
class LinkService : Service() {

    companion object {
        private const val CH = "lockin_link"
        private const val ID = 3

        fun start(c: Context) {
            if (!LockStore.linkEnabled(c)) return
            try {
                c.startForegroundService(Intent(c, LinkService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(c: Context) {
            Link.client?.stop()
            Link.client = null
            c.stopService(Intent(c, LinkService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Link.expire(this@LinkService)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "PC link", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build(Link.status()))
        if (Link.client == null) Link.client = LinkClient(applicationContext) { update() }
        Link.client?.start()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun update() {
        getSystemService(NotificationManager::class.java).notify(ID, build(Link.status()))
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Lockin PC link")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
