package com.saad.lockin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.UUID

object Util {
    fun isAccessibilityOn(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(c, LockAccessibilityService::class.java)
        return enabled.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }
}

/**
 * The PC link, as the rest of the app sees it.
 *  - asking the PC to lock too (opt-in, per session)
 *  - a request from the PC arrives -> notification -> the user taps "Lock phone" (nothing locks before that)
 * There is no way for the PC to end or shorten the phone's lock, and the phone can't release the PC either.
 */
object Link {
    const val ACTION_ACCEPT = "com.saad.lockin.LINK_ACCEPT"
    const val ACTION_DECLINE = "com.saad.lockin.LINK_DECLINE"
    private const val CH = "lockin_request"
    private const val NOTIF_REQ = 2
    private const val NOTIF_ERR = 4

    @Volatile var client: LinkClient? = null
    @Volatile var peerNote = ""
    @Volatile private var askedId: String? = null

    fun connected() = client?.connected == true
    fun status(): String = client?.status ?: "Off"

    fun restart(c: Context) {
        client?.stop()
        client = null
        LinkService.start(c)
    }

    private fun reply(id: String, ok: Boolean, why: String) {
        client?.send(mapOf("t" to "reply", "id" to id, "ok" to ok, "why" to why))
    }

    /** Phone -> PC: "please lock too". The PC asks its owner first. */
    fun ask(focus: Int, brk: Int, rounds: Int): Boolean {
        val cl = client ?: return false
        if (!cl.connected) return false
        val id = UUID.randomUUID().toString().take(12)
        askedId = id
        peerNote = "Asked the PC. Confirm it on the PC."
        cl.send(mapOf("t" to "start", "id" to id, "focus" to focus, "brk" to brk,
            "long" to maxOf(brk, 15), "rounds" to rounds, "every" to 4))
        return true
    }

    fun onMessage(c: Context, m: Map<String, Any?>) {
        when (m["t"]) {
            "start" -> onStart(c, m)
            "reply" -> if (m["id"] == askedId) {
                val why = (m["why"] as? String)?.takeIf { it.isNotBlank() } ?: "declined"
                peerNote = if (m["ok"] == true) "PC is locked too." else "PC didn't lock: $why."
            }
        }
    }

    // ------------------------------------------------------------ PC -> phone
    private fun onStart(c: Context, m: Map<String, Any?>) {
        val id = m["id"] as? String ?: return
        fun num(k: String) = (m[k] as? Number)?.toInt()
        val f = num("focus"); val b = num("brk"); val lg = num("long"); val r = num("rounds"); val ev = num("every")
        if (f == null || b == null || lg == null || r == null || ev == null) { reply(id, false, "bad request"); return }
        val now = System.currentTimeMillis()
        if (LockStore.sessionActive(c, now) || LockStore.pending(c, now) != null) { reply(id, false, "phone is busy"); return }
        if (f !in 1..240 || b !in 0..60 || r !in 1..20 || lg !in 0..90 || ev !in 0..12) { reply(id, false, "settings out of range"); return }
        val plan = LockStore.plan(now, f, b, r, lg, ev)
        if (plan.last().end - plan.first().start > LockStore.MAX_SESSION_MS) { reply(id, false, "longer than 4 hours"); return }
        LockStore.setPending(c, LockStore.LinkReq(id, f, b, lg, r, ev, now))
        showRequest(c, f, r)
    }

    private fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "PC lock requests", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun action(c: Context, a: String): PendingIntent = PendingIntent.getBroadcast(
        c, a.hashCode(), Intent(c, LinkActionReceiver::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun showRequest(c: Context, focus: Int, rounds: Int) {
        channel(c)
        val what = if (rounds > 1) "$focus min focus x $rounds rounds" else "$focus min focus"
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Your PC started a locked session")
            .setContentText("$what. Lock your phone too?")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Lock phone", action(c, ACTION_ACCEPT))
            .addAction(0, "Not now", action(c, ACTION_DECLINE))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(NOTIF_REQ, n)
    }

    fun notifyError(c: Context, text: String) {
        channel(c)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Phone not locked")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(NOTIF_ERR, n)
    }

    private fun clearNotif(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(NOTIF_REQ)

    /** The user tapped "Lock phone". Returns an error text, or null when the phone is now locked. */
    fun accept(c: Context): String? {
        val p = LockStore.pending(c) ?: return "That request expired."
        LockStore.clearPending(c)
        clearNotif(c)
        val now = System.currentTimeMillis()
        if (LockStore.sessionActive(c, now)) { reply(p.id, false, "phone is busy"); return "A session is already running on the phone." }
        if (!Util.isAccessibilityOn(c)) { reply(p.id, false, "accessibility is off on the phone"); return "Turn on the Lockin accessibility service first." }
        val apps = LockStore.cfgApps(c)
        val sites = LockStore.cfgSites(c)
        if (apps.isEmpty() && LockStore.normalizeSites(sites).isEmpty()) {
            reply(p.id, false, "no apps or sites chosen on the phone")
            return "Choose apps or websites to lock in Lockin first."
        }
        LockStore.startSession(c, LockStore.plan(now, p.f, p.b, p.r, p.lg, p.ev), apps, sites)
        LockService.start(c)
        peerNote = "PC started this session."
        reply(p.id, true, "")
        return null
    }

    fun decline(c: Context) {
        val p = LockStore.pendingAny(c) ?: return
        LockStore.clearPending(c)
        clearNotif(c)
        reply(p.id, false, "declined on the phone")
    }

    /** Requests nobody answered for 10 minutes are dropped (and the PC is told). */
    fun expire(c: Context) {
        val p = LockStore.pendingAny(c) ?: return
        if (System.currentTimeMillis() - p.at > LockStore.PENDING_MS) {
            LockStore.clearPending(c)
            clearNotif(c)
            reply(p.id, false, "expired")
        }
    }
}
