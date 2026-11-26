package com.saad.lockin

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Runs on a computer (no Android needed). Used by tests/test_link_android.py on the PC side. */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "vectors" -> vectors()
        "good" -> good(args[1].toInt(), args[2])
        "wrong" -> wrong(args[1].toInt(), args[2])
        "discover" -> println("FOUND " + LinkDiscovery.find(args[1].toInt(), listOf(InetAddress.getByName("127.0.0.1"))).joinToString(","))
        else -> println("usage")
    }
}

private fun vectors() {
    val s = LinkProto.normSecret("k7qd-m2xf-9tba")
    val ns = "00".repeat(16)
    val nc = "11".repeat(16)
    println("secret " + s)
    println("auth " + LinkProto.authMac(s, ns, nc, "A51"))
    println("srv " + LinkProto.serverMac(s, ns, nc))
    // the PC's first frame (role S) for this message, rebuilt here
    val pcSide = LinkProto.Channel(LinkProto.deriveKey(s, ns, nc), "S")
    println("frame " + pcSide.pack(mapOf("t" to "start", "id" to "abc", "focus" to 25)).trim())
    // and the PC's frame must be readable by the phone side
    val phone = LinkProto.Channel(LinkProto.deriveKey(s, ns, nc), "C")
    val back = phone.unpack(pcSide.pack(mapOf("t" to "reply", "id" to "q\"uo'te", "ok" to true, "why" to "PC\u00e9 is busy")).trim())
    println("roundtrip " + back)
    println("name " + LinkProto.cleanName("SM-A515F phone!"))
}

private class Rec : LinkEvents {
    val msgs = CopyOnWriteArrayList<Map<String, Any?>>()
    @Volatile var pc = ""
    @Volatile var dropped = false
    override fun onConnected(pcName: String) { pc = pcName }
    override fun onDisconnected() { dropped = true }
    override fun onMessage(msg: Map<String, Any?>) { msgs.add(msg) }
}

private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
    return cond()
}

private fun good(port: Int, code: String) {
    val rec = Rec()
    val core = LinkCore("SM-A515F", rec)
    var end = ""
    val t = thread { end = core.runOnce(InetSocketAddress("127.0.0.1", port), LinkProto.normSecret(code)) }
    if (!waitFor(5000) { core.connected }) { println("FAIL not connected"); core.stopRequested = true; return }
    println("CONNECTED " + core.pcName)
    // phone -> PC
    core.send(mapOf("t" to "start", "id" to "ph1", "focus" to 25, "brk" to 5, "long" to 15, "rounds" to 4, "every" to 4))
    // PC -> phone: the PC test answers our request by asking us back
    if (!waitFor(6000) { rec.msgs.any { it["t"] == "start" } }) { println("FAIL no start from PC"); core.stopRequested = true; return }
    val m = rec.msgs.first { it["t"] == "start" }
    println("GOT_START id=${m["id"]} focus=${m["focus"]} brk=${m["brk"]} long=${m["long"]} rounds=${m["rounds"]} every=${m["every"]}")
    core.send(mapOf("t" to "reply", "id" to m["id"], "ok" to true, "why" to ""))
    Thread.sleep(700)
    core.stopRequested = true
    t.join(3000)
    println("DONE end=$end dropped=${rec.dropped}")
}

private fun wrong(port: Int, code: String) {
    val core = LinkCore("SM-A515F", Rec())
    println("END " + core.runOnce(InetSocketAddress("127.0.0.1", port), LinkProto.normSecret(code)))
}
