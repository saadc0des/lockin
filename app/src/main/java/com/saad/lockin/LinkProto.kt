package com.saad.lockin

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol to the Lockin PC app (see lockin/linkproto.py on the PC for the full description).
 * Plain JVM code on purpose: no Android classes, so it can be tested on a computer.
 */
object LinkProto {
    const val PORT = 47653
    const val DISCOVER = "LOCKIN_DISCOVER"
    const val HERE = "LOCKIN_HERE"
    const val MAX_LINE = 8192

    class ProtoError(msg: String) : Exception(msg)

    private fun isAlnum(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    fun normSecret(s: String?): String = (s ?: "").filter { isAlnum(it) }.uppercase()

    fun cleanName(s: String?, def: String = "device"): String {
        val t = (s ?: "").map { if (isAlnum(it) || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("").take(24)
        return if (t.isEmpty()) def else t
    }

    private fun hmac(key: ByteArray, text: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun mac(key: ByteArray, text: String): String = hex(hmac(key, text))

    fun eq(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun authMac(secret: String, ns: String, nc: String, dev: String): String =
        mac(secret.toByteArray(Charsets.UTF_8), "A|$ns|$nc|$dev")

    fun serverMac(secret: String, ns: String, nc: String): String =
        mac(secret.toByteArray(Charsets.UTF_8), "B|$ns|$nc")

    fun deriveKey(secret: String, ns: String, nc: String): ByteArray =
        hmac(secret.toByteArray(Charsets.UTF_8), "K|$ns|$nc")

    fun newNonce(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return hex(b)
    }

    fun authLine(nc: String, dev: String, mac: String) = "AUTH $nc $dev $mac\n"

    /** Returns (nonceS, pcName). */
    fun parseHello(line: String): Pair<String, String> {
        val p = line.split(" ")
        if (p.size != 3 || p[0] != "HELLO" || !Regex("[0-9a-f]{32}").matches(p[1])) throw ProtoError("bad hello")
        return p[1] to p[2]
    }

    /** One authenticated connection. role "C" = the phone. */
    class Channel(private val key: ByteArray, role: String = "C") {
        private val sendDir = role
        private val recvDir = if (role == "S") "C" else "S"
        private var sendSeq = 0L
        private var recvSeq = 0L

        fun pack(msg: Map<String, Any?>): String {
            val body = MiniJson.stringify(msg)
            sendSeq++
            return mac(key, "$sendDir|$sendSeq|$body") + " " + sendSeq + " " + body + "\n"
        }

        fun unpack(line: String): Map<String, Any?> {
            val p = line.split(" ", limit = 3)
            if (p.size != 3) throw ProtoError("bad frame")
            val seq = p[1].toLongOrNull() ?: throw ProtoError("bad sequence")
            if (seq <= recvSeq) throw ProtoError("replayed or bad sequence")
            if (!eq(p[0], mac(key, "$recvDir|${p[1]}|${p[2]}"))) throw ProtoError("bad signature")
            recvSeq = seq
            return MiniJson.parse(p[2])
        }
    }
}

/** Just enough JSON for our flat messages: strings, integers, booleans, null. */
object MiniJson {
    fun stringify(m: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(',')
            first = false
            quote(sb, k)
            sb.append(':')
            when (v) {
                null -> sb.append("null")
                is Boolean -> sb.append(if (v) "true" else "false")
                is Int, is Long -> sb.append(v.toString())
                is String -> quote(sb, v)
                else -> throw IllegalArgumentException("unsupported value")
            }
        }
        return sb.append('}').toString()
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch > '~' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    fun parse(s: String): Map<String, Any?> {
        val p = Parser(s)
        val out = p.obj()
        p.ws()
        if (p.i != s.length) throw LinkProto.ProtoError("junk after json")
        return out
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun need(c: Char) {
            ws()
            if (i >= s.length || s[i] != c) throw LinkProto.ProtoError("expected $c")
            i++
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            need('{')
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                need(':')
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                need('}')
                return m
            }
        }

        private fun value(): Any? {
            ws()
            if (i >= s.length) throw LinkProto.ProtoError("unexpected end")
            return when {
                s[i] == '"' -> str()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                s[i] == '-' || s[i].isDigit() -> num()
                else -> throw LinkProto.ProtoError("unsupported json")
            }
        }

        private fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw LinkProto.ProtoError("bad number")
        }

        fun str(): String {
            ws()
            if (i >= s.length || s[i] != '"') throw LinkProto.ProtoError("expected string")
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw LinkProto.ProtoError("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) throw LinkProto.ProtoError("bad escape")
                when (val e = s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) throw LinkProto.ProtoError("bad unicode escape")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> throw LinkProto.ProtoError("bad escape \\$e")
                }
            }
        }
    }
}
