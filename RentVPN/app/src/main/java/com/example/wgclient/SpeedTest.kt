package com.example.wgclient

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.random.Random

/** Локальный SOCKS5-вход Xray (с паролем) — через него тест идёт внутри VPN-туннеля. */
data class SocksInfo(val port: Int, val user: String, val pass: String)

enum class SpeedPhase { IDLE, PING, DOWN, UP, DONE }

data class SpeedResult(
    val pingMs: Int,
    val jitterMs: Int,
    val downMbps: Double,
    val upMbps: Double,
    val server: String,
    val viaVpn: Boolean,
    val time: Long,
)

/**
 * Тест скорости на открытой инфраструктуре Cloudflare (speed.cloudflare.com):
 * пинг/джиттер → загрузка (4 потока) → отдача (3 потока).
 * Если передан [SocksInfo] — трафик идёт через локальный SOCKS Xray, т.е. внутри VPN.
 */
object SpeedTest {
    private const val HOST = "speed.cloudflare.com"
    private const val DURATION_MS = 8000L
    private const val WARMUP_MS = 1500L
    private const val CHUNK = 25_000_000

    suspend fun run(socks: SocksInfo?, viaVpn: Boolean, onUpdate: (SpeedPhase, Double) -> Unit): SpeedResult =
        withContext(Dispatchers.IO) {
            onUpdate(SpeedPhase.PING, 0.0)
            val ping = measurePing(socks)
            val down = transfer(socks, true) { onUpdate(SpeedPhase.DOWN, it) }
            val up = transfer(socks, false) { onUpdate(SpeedPhase.UP, it) }
            SpeedResult(ping.first, ping.second, down, up, ping.third, viaVpn, System.currentTimeMillis())
        }

    // ---------- соединение ----------

    private fun open(socks: SocksInfo?): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        if (socks == null) {
            raw.connect(InetSocketAddress(HOST, 443), 8000)
        } else {
            raw.connect(InetSocketAddress("127.0.0.1", socks.port), 4000)
            raw.soTimeout = 15000
            socks5Connect(raw, socks, HOST, 443)
        }
        raw.soTimeout = 15000
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true) as SSLSocket
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, ssl.session)) {
            closeQuietly(ssl)
            throw IOException("Сертификат сервера не прошёл проверку")
        }
        return ssl
    }

    /** SOCKS5 с логином/паролем (RFC 1929) + CONNECT. */
    private fun socks5Connect(s: Socket, c: SocksInfo, host: String, port: Int) {
        val o = s.getOutputStream()
        val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2)); o.flush()
        val m = ByteArray(2); i.readFully(m)
        if (m[0].toInt() != 5 || m[1].toInt() != 2) throw IOException("SOCKS: метод не принят")
        val u = c.user.toByteArray(); val p = c.pass.toByteArray()
        o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); o.flush()
        val a = ByteArray(2); i.readFully(a)
        if (a[1].toInt() != 0) throw IOException("SOCKS: неверный пароль")
        val h = host.toByteArray()
        o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte())); o.flush()
        val r = ByteArray(4); i.readFully(r)
        if (r[1].toInt() != 0) throw IOException("SOCKS: сервер отказал (код ${r[1]})")
        val skip = when (r[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> i.readUnsignedByte(); else -> 0 }
        i.readFully(ByteArray(skip + 2))
    }

    private fun closeQuietly(s: Socket) { runCatching { s.close() } }

    // ---------- HTTP ----------

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** Читает заголовки ответа; всё до строки «HTTP/…» (хвост прошлого ответа) пропускает. */
    private fun readHeaders(inp: InputStream): Map<String, String> {
        val h = HashMap<String, String>()
        var started = false
        while (true) {
            val line = readLine(inp) ?: throw IOException("соединение закрыто сервером")
            if (!started) {
                if (line.startsWith("HTTP/")) { started = true; h[":status"] = line.split(' ').getOrNull(1).orEmpty() }
                continue
            }
            if (line.isEmpty()) return h
            h[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
    }

    // ---------- пинг ----------

    /** Возвращает (пинг мс, джиттер мс, точка присутствия Cloudflare). */
    private fun measurePing(socks: SocksInfo?): Triple<Int, Int, String> {
        val s = open(socks)
        try {
            val out = s.getOutputStream()
            val inp = BufferedInputStream(s.getInputStream())
            val rtts = ArrayList<Double>()
            var colo = ""
            repeat(10) { n ->
                val t0 = System.nanoTime()
                out.write("GET /__down?bytes=0 HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: RentVPN\r\nAccept: */*\r\n\r\n".toByteArray())
                out.flush()
                val h = readHeaders(inp)
                val rtt = (System.nanoTime() - t0) / 1e6
                if (n > 0) rtts += rtt // первый запрос — прогрев
                h["cf-meta-colo"]?.let { colo = it }
                h["content-length"]?.toLongOrNull()?.takeIf { it > 0 }?.let { inp.skip(it) }
            }
            val sorted = rtts.sorted()
            val median = sorted[sorted.size / 2]
            val jitter = rtts.zipWithNext { a, b -> abs(a - b) }.average()
            return Triple(median.toInt(), jitter.toInt(), if (colo.isBlank()) "Cloudflare" else "Cloudflare · $colo")
        } finally {
            closeQuietly(s)
        }
    }

    // ---------- загрузка / отдача ----------

    private suspend fun transfer(socks: SocksInfo?, download: Boolean, onLive: (Double) -> Unit): Double {
        val total = AtomicLong()
        val stop = AtomicBoolean(false)
        val firstError = AtomicReference<Throwable?>(null)
        val sockets = Collections.synchronizedList(ArrayList<Socket>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        repeat(if (download) 4 else 3) {
            scope.launch {
                try {
                    val buf = ByteArray(64 * 1024).also { if (!download) Random.nextBytes(it) }
                    while (!stop.get()) {
                        val s = open(socks)
                        sockets += s
                        if (stop.get()) { closeQuietly(s); break }
                        val out = s.getOutputStream()
                        if (download) {
                            val inp = BufferedInputStream(s.getInputStream(), 64 * 1024)
                            out.write("GET /__down?bytes=$CHUNK HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: RentVPN\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            readHeaders(inp)
                            while (!stop.get()) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                total.addAndGet(n.toLong())
                            }
                        } else {
                            out.write("POST /__up HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: RentVPN\r\nContent-Type: application/octet-stream\r\nContent-Length: $CHUNK\r\nConnection: close\r\n\r\n".toByteArray())
                            var sent = 0
                            while (!stop.get() && sent < CHUNK) {
                                val n = minOf(buf.size, CHUNK - sent)
                                out.write(buf, 0, n)
                                total.addAndGet(n.toLong())
                                sent += n
                            }
                            out.flush()
                        }
                        closeQuietly(s)
                    }
                } catch (e: Throwable) {
                    if (!stop.get()) firstError.compareAndSet(null, e)
                }
            }
        }

        val start = System.nanoTime()
        var lastT = start
        var lastB = 0L
        var warmB = -1L
        var warmT = 0L
        var smooth = 0.0
        while (true) {
            delay(250)
            val now = System.nanoTime()
            val b = total.get()
            val elapsedMs = (now - start) / 1_000_000
            val inst = (b - lastB) * 8.0 / ((now - lastT) / 1e9) / 1e6
            smooth = if (smooth == 0.0) inst else smooth * 0.6 + inst * 0.4
            onLive(smooth)
            lastT = now; lastB = b
            if (warmB < 0 && elapsedMs >= WARMUP_MS) { warmB = b; warmT = now }
            if (elapsedMs >= DURATION_MS || (firstError.get() != null && b == 0L)) break
        }
        stop.set(true)
        val endB = total.get()
        val endT = System.nanoTime()
        synchronized(sockets) { sockets.toList() }.forEach { closeQuietly(it) }
        scope.cancel()

        if (endB == 0L) throw IOException(firstError.get()?.message ?: "нет данных от сервера")
        val bytes: Long
        val secs: Double
        if (warmB >= 0 && endT > warmT) { bytes = endB - warmB; secs = (endT - warmT) / 1e9 }
        else { bytes = endB; secs = (endT - start) / 1e9 }
        return bytes * 8.0 / secs / 1e6
    }

    // ---------- история ----------

    private fun prefs(c: Context) = c.getSharedPreferences("rentvpn", Context.MODE_PRIVATE)

    fun loadHistory(c: Context): List<SpeedResult> = runCatching {
        val arr = JSONArray(prefs(c).getString("speed_history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SpeedResult(
                o.getInt("ping"), o.getInt("jitter"), o.getDouble("down"), o.getDouble("up"),
                o.optString("server"), o.optBoolean("vpn"), o.getLong("time")
            )
        }
    }.getOrDefault(emptyList())

    fun addHistory(c: Context, r: SpeedResult) {
        val list = (listOf(r) + loadHistory(c)).take(8)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("ping", it.pingMs).put("jitter", it.jitterMs).put("down", it.downMbps)
                    .put("up", it.upMbps).put("server", it.server).put("vpn", it.viaVpn).put("time", it.time)
            )
        }
        prefs(c).edit().putString("speed_history", arr.toString()).apply()
    }
}
