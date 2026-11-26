package com.saad.lockin

import android.content.Context
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** One block of time in a session. focus = locked, break = unlocked. */
data class Window(val start: Long, val end: Long, val focus: Boolean)

/** All lock state lives here (SharedPreferences) so the service, the accessibility service
 *  and the UI always agree, and a running lock survives app close and reboot. */
object LockStore {
    const val EXIT_DELAY_MS = 15 * 60 * 1000L          // emergency exit wait
    const val MAX_SESSION_MS = 4 * 60 * 60 * 1000L + 60_000L
    private const val PREFS = "lockin"

    val DEFAULT_SITES = listOf(
        "youtube.com", "instagram.com", "tiktok.com", "facebook.com",
        "x.com", "twitter.com", "reddit.com", "netflix.com", "twitch.tv"
    )

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ config (setup screen)
    fun focusMin(c: Context) = p(c).getInt("focus", 25)
    fun breakMin(c: Context) = p(c).getInt("break", 5)
    fun rounds(c: Context) = p(c).getInt("rounds", 4)
    fun cfgApps(c: Context): Set<String> = p(c).getStringSet("cfg_apps", emptySet())!!.toSet()
    fun cfgSites(c: Context): String = p(c).getString("cfg_sites", null) ?: DEFAULT_SITES.joinToString("\n")

    fun saveConfig(c: Context, f: Int?, b: Int?, r: Int?, apps: Set<String>, sitesRaw: String) {
        val e = p(c).edit()
        if (f != null) e.putInt("focus", f)
        if (b != null) e.putInt("break", b)
        if (r != null) e.putInt("rounds", r)
        e.putStringSet("cfg_apps", HashSet(apps))
        e.putString("cfg_sites", sitesRaw)
        e.apply()
    }

    // ------------------------------------------------------------ session
    private var cacheRaw: String? = null
    private var cacheWin: List<Window> = emptyList()

    fun windows(c: Context): List<Window> {
        val raw = p(c).getString("windows", "") ?: ""
        if (raw == cacheRaw) return cacheWin
        val list = if (raw.isEmpty()) emptyList() else raw.split(";").mapNotNull { s ->
            val a = s.split(",")
            val st = a.getOrNull(0)?.toLongOrNull()
            val en = a.getOrNull(1)?.toLongOrNull()
            if (st != null && en != null && a.size == 3) Window(st, en, a[2] == "f") else null
        }
        cacheRaw = raw
        cacheWin = list
        return list
    }

    /** Pomodoro plan. rounds = 1 gives a plain timer. Long break (15 min) after every 4th round. */
    fun plan(now: Long, focusMin: Int, breakMin: Int, rounds: Int, longMin: Int = -1, every: Int = 4): List<Window> {
        val out = ArrayList<Window>()
        var t = now
        for (i in 1..rounds) {
            val fe = t + focusMin * 60_000L
            out.add(Window(t, fe, true))
            t = fe
            if (i < rounds && breakMin > 0) {
                val lm = if (longMin > 0) longMin else maxOf(breakMin, 15)
                val len = if (every > 0 && i % every == 0) lm else breakMin
                val be = t + len * 60_000L
                out.add(Window(t, be, false))
                t = be
            }
        }
        return out
    }

    fun startSession(c: Context, plan: List<Window>, apps: Set<String>, sitesRaw: String) {
        val raw = plan.joinToString(";") { "${it.start},${it.end},${if (it.focus) "f" else "b"}" }
        p(c).edit()
            .putString("windows", raw)
            .putStringSet("s_apps", HashSet(apps))
            .putString("s_sites", normalizeSites(sitesRaw).joinToString("\n"))
            .putLong("exit_at", 0L)
            .putInt("announced", -1)
            .putInt("logged", 0)
            .commit()
    }

    fun hasSession(c: Context) = windows(c).isNotEmpty()

    fun sessionEnd(c: Context) = windows(c).lastOrNull()?.end ?: 0L

    fun sessionActive(c: Context, now: Long): Boolean {
        val w = windows(c)
        return w.isNotEmpty() && now < w.last().end
    }

    fun currentWindow(c: Context, now: Long): Window? =
        windows(c).firstOrNull { now >= it.start && now < it.end }

    fun isLockedNow(c: Context, now: Long) = currentWindow(c, now)?.focus == true

    fun clearSession(c: Context) {
        p(c).edit().putString("windows", "").putLong("exit_at", 0L).commit()
    }

    fun sessionApps(c: Context): Set<String> = p(c).getStringSet("s_apps", emptySet())!!.toSet()

    fun sessionSites(c: Context): List<String> =
        (p(c).getString("s_sites", "") ?: "").split("\n").filter { it.isNotBlank() }

    // ------------------------------------------------------------ PC link
    const val PENDING_MS = 10 * 60 * 1000L

    /** A "lock your phone too" request from the PC that is waiting for the user. */
    data class LinkReq(val id: String, val f: Int, val b: Int, val lg: Int, val r: Int, val ev: Int, val at: Long)

    fun linkEnabled(c: Context) = p(c).getBoolean("link_on", false)
    fun setLinkEnabled(c: Context, on: Boolean) { p(c).edit().putBoolean("link_on", on).commit() }
    fun linkSecret(c: Context): String = p(c).getString("link_secret", "") ?: ""
    fun linkHost(c: Context): String = p(c).getString("link_host", "") ?: ""
    fun saveLink(c: Context, secret: String, host: String) {
        p(c).edit().putString("link_secret", secret).putString("link_host", host).commit()
    }
    fun linkLast(c: Context): String = p(c).getString("link_last", "") ?: ""
    fun setLinkLast(c: Context, v: String) { p(c).edit().putString("link_last", v).apply() }

    fun setPending(c: Context, r: LinkReq) {
        p(c).edit().putString("link_pending", "${r.id},${r.f},${r.b},${r.lg},${r.r},${r.ev},${r.at}").commit()
    }

    fun pendingAny(c: Context): LinkReq? {
        val a = (p(c).getString("link_pending", "") ?: "").split(",")
        if (a.size != 7) return null
        val n = a.drop(1).map { it.toLongOrNull() ?: return null }
        return LinkReq(a[0], n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toInt(), n[5])
    }

    fun pending(c: Context, now: Long = System.currentTimeMillis()): LinkReq? =
        pendingAny(c)?.takeIf { now - it.at <= PENDING_MS }

    fun clearPending(c: Context) { p(c).edit().putString("link_pending", "").commit() }

    // ------------------------------------------------------------ sounds setting
    fun soundsOn(c: Context) = p(c).getBoolean("sounds_on", true)
    fun setSoundsOn(c: Context, on: Boolean) { p(c).edit().putBoolean("sounds_on", on).apply() }
    fun announced(c: Context) = p(c).getInt("announced", -1)
    fun setAnnounced(c: Context, i: Int) { p(c).edit().putInt("announced", i).commit() }

    // ------------------------------------------------------------ session end / abort
    /** Natural end: credit the stats, play the end sound (only if it just ended), clear the lock. */
    fun finishSession(c: Context, now: Long) {
        if (!hasSession(c) || now < sessionEnd(c)) return
        settleStats(c, now)
        if (now - sessionEnd(c) < 120_000L) Sounds.play(c, "end")
        clearSession(c)
    }

    /** Emergency exit: credit finished blocks plus the part of the current focus block you did, then unlock. */
    fun abortSession(c: Context, now: Long) {
        settleStats(c, now)
        val w = currentWindow(c, now)
        if (w != null && w.focus) {
            val m = (now - w.start) / 60000.0
            if (m >= 0.5) appendStats(c, "${w.start},$m;")
        }
        clearSession(c)
    }

    // ------------------------------------------------------------ stats (same idea as the desktop app)
    // One record per focus block: "startMillis,minutes;". Finished blocks are credited in full, an early
    // exit credits the part you did. Records under 0.5 min are ignored.
    private var statsRaw: String? = null
    private var statsList: List<Pair<Long, Double>> = emptyList()

    private fun appendStats(c: Context, add: String) {
        if (add.isEmpty()) return
        var s = (p(c).getString("stats", "") ?: "") + add
        if (s.length > 60_000) {
            s = s.split(";").filter { it.isNotEmpty() }.takeLast(2500).joinToString(";") + ";"
        }
        p(c).edit().putString("stats", s).commit()
    }

    /** Credit every focus window that has finished and was not credited yet. Safe to call often. */
    fun settleStats(c: Context, now: Long) {
        val ws = windows(c)
        val before = p(c).getInt("logged", 0)
        var n = before
        val add = StringBuilder()
        for (i in ws.indices) {
            if (i >= n && ws[i].end <= now) {
                if (ws[i].focus) {
                    val m = (ws[i].end - ws[i].start) / 60000.0
                    if (m >= 0.5) add.append("${ws[i].start},$m;")
                }
                n = i + 1
            }
        }
        if (n != before) {
            p(c).edit().putInt("logged", n).commit()
            appendStats(c, add.toString())
        }
    }

    fun statRecords(c: Context): List<Pair<Long, Double>> {
        val raw = p(c).getString("stats", "") ?: ""
        if (raw == statsRaw) return statsList
        val list = raw.split(";").mapNotNull { r ->
            val a = r.split(",")
            val st = a.getOrNull(0)?.toLongOrNull()
            val m = a.getOrNull(1)?.toDoubleOrNull()
            if (st != null && m != null) st to m else null
        }
        statsRaw = raw
        statsList = list
        return list
    }

    /** Oldest day first, today last, one entry per day (0.0 when nothing was done). */
    fun minutesByDay(c: Context, days: Int): List<Pair<LocalDate, Double>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val map = LinkedHashMap<LocalDate, Double>()
        for (i in days - 1 downTo 0) map[today.minusDays(i.toLong())] = 0.0
        for ((st, m) in statRecords(c)) {
            val d = Instant.ofEpochMilli(st).atZone(zone).toLocalDate()
            val cur = map[d]
            if (cur != null) map[d] = cur + m
        }
        return map.entries.map { it.key to it.value }
    }

    fun totals(c: Context): Pair<Double, Int> {
        val r = statRecords(c)
        return r.sumOf { it.second } to r.size
    }

    fun streak(c: Context): Int {
        val by = minutesByDay(c, 60).toMap()
        var d = LocalDate.now()
        var n = 0
        if ((by[d] ?: 0.0) <= 0.0) d = d.minusDays(1)
        while ((by[d] ?: 0.0) > 0.0) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun fmtDur(min: Double): String {
        val m = Math.round(min).toInt()
        return if (m < 60) "${m}m" else if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
    }

    // ------------------------------------------------------------ emergency exit (medium strictness)
    fun exitRequestedAt(c: Context) = p(c).getLong("exit_at", 0L)
    fun requestExit(c: Context, now: Long) { p(c).edit().putLong("exit_at", now).commit() }
    fun cancelExit(c: Context) { p(c).edit().putLong("exit_at", 0L).commit() }

    // ------------------------------------------------------------ site matching
    fun hostOf(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.contains(' ')) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (!s.contains('.')) return null
        return s.removePrefix("www.")
    }

    fun normalizeSites(raw: String): List<String> =
        raw.lines().mapNotNull { hostOf(it) }.distinct()

    fun isBlockedUrl(c: Context, raw: String): Boolean {
        val h = hostOf(raw) ?: return false
        return sessionSites(c).any { h == it || h.endsWith(".$it") }
    }

    // ------------------------------------------------------------ formatting
    fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun mmss(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }
}
