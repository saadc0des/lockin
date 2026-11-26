package com.saad.lockin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetSocketAddress

/** Keeps the phone connected to the PC app: finds it on the Wi-Fi, reconnects when the link drops. */
class LinkClient(private val c: Context, private val onChange: () -> Unit) : LinkEvents {
    private val core = LinkCore(Build.MODEL ?: "phone", this)
    @Volatile private var running = false
    @Volatile private var current: InetSocketAddress? = null
    @Volatile var status = "Starting..."
        private set

    val connected: Boolean get() = core.connected
    val pcName: String get() = core.pcName

    fun start() {
        if (running) return
        running = true
        core.stopRequested = false
        Thread { loop() }.apply { isDaemon = true; name = "lockin-link"; start() }
    }

    fun stop() {
        running = false
        core.stopRequested = true
    }

    fun send(m: Map<String, Any?>) = core.send(m)

    private fun set(s: String) {
        status = s
        onChange()
    }

    private fun nap(ms: Long) {
        var left = ms
        while (running && left > 0) {
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            left -= 200
        }
    }

    private fun onWifi(): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun parse(s: String): InetSocketAddress? {
        val i = s.lastIndexOf(':')
        val host = if (i > 0) s.substring(0, i) else s
        val port = if (i > 0) (s.substring(i + 1).toIntOrNull() ?: return null) else LinkProto.PORT
        return try { InetSocketAddress(host.trim(), port) } catch (_: Exception) { null }
    }

    private fun candidates(): List<InetSocketAddress> {
        val out = LinkedHashSet<InetSocketAddress>()
        val manual = LockStore.linkHost(c).trim()
        if (manual.isNotEmpty()) parse(manual)?.let { out.add(it) }
        val last = LockStore.linkLast(c)
        if (last.isNotEmpty()) parse(last)?.let { out.add(it) }
        return out.toList()
    }

    private fun unreachable(end: String) = end == LinkEnd.CANT_CONNECT || end == LinkEnd.NO_HELLO

    private fun loop() {
        var wait = 2000L
        while (running) {
            val secret = LinkProto.normSecret(LockStore.linkSecret(c))
            if (secret.isEmpty()) { set("Enter the pairing code from the PC"); nap(2000); continue }
            if (!onWifi()) { set("Waiting for Wi-Fi"); nap(5000); continue }

            var end = ""
            var addrs = candidates()
            for (round in 0..1) {
                for (a in addrs) {
                    if (!running) return
                    current = a
                    set("Connecting to " + a.hostString + "...")
                    end = core.runOnce(a, secret)
                    if (!unreachable(end)) break
                }
                if (end.isNotEmpty() && !unreachable(end)) break
                if (round == 0) {
                    set("Looking for the PC on this Wi-Fi...")
                    addrs = LinkDiscovery.find(LinkProto.PORT, LinkDiscovery.broadcastTargets())
                    if (addrs.isEmpty()) break
                }
            }
            when (end) {
                LinkEnd.REFUSED -> { set("The PC refused the code. Check the pairing code."); nap(6000) }
                LinkEnd.CLOSED, LinkEnd.TIMEOUT, LinkEnd.BAD_DATA -> { wait = 2000; set("Reconnecting..."); nap(wait) }
                LinkEnd.STOPPED -> return
                else -> {
                    set("Can't find the PC. Is Lockin open on it with the phone link on?")
                    nap(wait)
                    wait = minOf(wait * 2, 15000)
                }
            }
        }
    }

    // ---- LinkEvents (called on the link thread)
    override fun onConnected(pcName: String) {
        current?.let { LockStore.setLinkLast(c, (it.address?.hostAddress ?: it.hostString) + ":" + it.port) }
        set("Connected to $pcName")
    }

    override fun onDisconnected() {
        if (running) set("Reconnecting...")
    }

    override fun onMessage(msg: Map<String, Any?>) = Link.onMessage(c, msg)
}
