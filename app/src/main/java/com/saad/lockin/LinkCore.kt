package com.saad.lockin

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue

/** What the link engine tells its owner. May be called from the link thread. */
interface LinkEvents {
    fun onConnected(pcName: String)
    fun onDisconnected()
    fun onMessage(msg: Map<String, Any?>)
}

/** Why a connection attempt ended. */
object LinkEnd {
    const val CANT_CONNECT = "cant-connect"
    const val NO_HELLO = "no-hello"
    const val REFUSED = "refused"        // the PC did not accept our pairing code
    const val CLOSED = "closed"          // was connected, connection dropped
    const val TIMEOUT = "timeout"        // was connected, the PC went quiet
    const val BAD_DATA = "bad-data"
    const val STOPPED = "stopped"
}

/**
 * The connection engine: connect to the PC, prove the pairing code both ways, then exchange signed messages.
 * Plain JVM code (no Android classes) so it can be tested against the real PC app on a computer.
 * One thread calls runOnce(); any thread may call send().
 */
class LinkCore(private val deviceName: String, private val events: LinkEvents) {
    @Volatile var connected = false
        private set
    @Volatile var pcName = ""
        private set
    @Volatile var stopRequested = false
    private val outbox = LinkedBlockingQueue<Map<String, Any?>>()

    /** Queue a message; it is sent within a fraction of a second, and dropped if we are not connected. */
    fun send(msg: Map<String, Any?>) {
        if (connected) outbox.add(msg)
    }

    private class Lines(private val inp: InputStream) {
        private var acc = ByteArray(0)
        var eof = false

        private fun take(): String? {
            val idx = acc.indexOf('\n'.code.toByte())
            if (idx < 0) {
                if (acc.size > LinkProto.MAX_LINE) throw LinkProto.ProtoError("line too long")
                return null
            }
            val line = String(acc, 0, idx, Charsets.UTF_8)
            acc = acc.copyOfRange(idx + 1, acc.size)
            return line.trimEnd('\r')
        }

        /** One complete line if there is one, else waits up to the socket timeout for more bytes. */
        fun poll(): String? {
            take()?.let { return it }
            try {
                val tmp = ByteArray(2048)
                val n = inp.read(tmp)
                if (n < 0) eof = true else acc += tmp.copyOf(n)
            } catch (_: SocketTimeoutException) {
            }
            return take()
        }

        fun readLine(timeoutMs: Long): String? {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                poll()?.let { return it }
                if (eof) return null
            }
            return null
        }
    }

    /** Blocks until the connection ends. Returns one of LinkEnd.*. */
    fun runOnce(addr: InetSocketAddress, secret: String): String {
        val sock = Socket()
        var announced = false
        try {
            try {
                sock.connect(addr, 3000)
            } catch (_: IOException) {
                return LinkEnd.CANT_CONNECT
            }
            sock.tcpNoDelay = true
            sock.soTimeout = 250
            val rd = Lines(sock.getInputStream())
            val out = sock.getOutputStream()

            val hello = rd.readLine(5000) ?: return LinkEnd.NO_HELLO
            val (ns, pc) = LinkProto.parseHello(hello)
            val nc = LinkProto.newNonce()
            val dev = LinkProto.cleanName(deviceName)
            out.write(LinkProto.authLine(nc, dev, LinkProto.authMac(secret, ns, nc, dev)).toByteArray(Charsets.UTF_8))
            out.flush()

            // A wrong code makes the PC hang up without answering.
            val ok = rd.readLine(5000) ?: return LinkEnd.REFUSED
            val p = ok.split(" ")
            if (p.size != 2 || p[0] != "OK" || !LinkProto.eq(p[1], LinkProto.serverMac(secret, ns, nc))) return LinkEnd.REFUSED

            val chan = LinkProto.Channel(LinkProto.deriveKey(secret, ns, nc), "C")
            outbox.clear()
            pcName = pc
            connected = true
            announced = true
            events.onConnected(pc)

            var lastRx = System.currentTimeMillis()
            var lastPing = 0L
            while (!stopRequested) {
                val line = rd.poll()
                if (line != null) {
                    lastRx = System.currentTimeMillis()
                    val msg = chan.unpack(line)
                    if (msg["t"] != "pong") events.onMessage(msg)
                }
                if (rd.eof) return LinkEnd.CLOSED
                while (true) {
                    val m = outbox.poll() ?: break
                    out.write(chan.pack(m).toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                val now = System.currentTimeMillis()
                if (now - lastPing > 15_000) {
                    out.write(chan.pack(mapOf("t" to "ping")).toByteArray(Charsets.UTF_8))
                    out.flush()
                    lastPing = now
                }
                if (now - lastRx > 45_000) return LinkEnd.TIMEOUT
            }
            return LinkEnd.STOPPED
        } catch (_: IOException) {
            return if (announced) LinkEnd.CLOSED else LinkEnd.CANT_CONNECT
        } catch (_: LinkProto.ProtoError) {
            return LinkEnd.BAD_DATA
        } finally {
            connected = false
            outbox.clear()
            try { sock.close() } catch (_: IOException) {}
            if (announced) events.onDisconnected()
        }
    }
}

/** Finding the PC on the Wi-Fi: shout on the network, the PC answers with its port. */
object LinkDiscovery {
    fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs != null && ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    fun find(port: Int, targets: List<InetAddress>, waitMs: Long = 1500): List<InetSocketAddress> {
        val found = LinkedHashSet<InetSocketAddress>()
        val s = DatagramSocket()
        try {
            s.broadcast = true
            s.soTimeout = 300
            val data = LinkProto.DISCOVER.toByteArray(Charsets.UTF_8)
            for (t in targets) {
                try { s.send(DatagramPacket(data, data.size, t, port)) } catch (_: IOException) {}
            }
            val end = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(ByteArray(256), 256)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                val parts = String(p.data, 0, p.length, Charsets.UTF_8).trim().split(" ")
                if (parts.size >= 2 && parts[0] == LinkProto.HERE) {
                    val tcp = parts[1].toIntOrNull() ?: continue
                    found.add(InetSocketAddress(p.address, tcp))
                }
            }
        } catch (_: IOException) {
        } finally {
            s.close()
        }
        return found.toList()
    }
}
