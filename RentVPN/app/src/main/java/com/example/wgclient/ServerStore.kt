package com.example.wgclient

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

/** Сервер из подписки. [unsupportedReason] != null — подключиться нельзя. */
data class Server(
    val name: String,
    val protocol: String,
    val raw: String,
    val host: String = "",
    val port: Int = 0,
    val unsupportedReason: String? = null,
) {
    val supported: Boolean get() = unsupportedReason == null
}

/** Разбор подписок, хранение списка серверов, загрузка по HTTPS, TCP-пинг. */
object ServerStore {
    private fun listFile(c: Context) = File(c.filesDir, "servers.json")
    private fun prefs(c: Context) = c.getSharedPreferences("rentvpn", Context.MODE_PRIVATE)

    fun load(c: Context): List<Server> {
        val f = listFile(c)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { parseOne(arr.getString(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(c: Context, servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { arr.put(it.raw) }
        listFile(c).writeText(arr.toString())
    }

    fun loadSelected(c: Context, servers: List<Server>): Int {
        val raw = prefs(c).getString("selected", null)
        val i = servers.indexOfFirst { it.raw == raw && it.supported }
        return if (i >= 0) i else servers.indexOfFirst { it.supported }
    }

    fun saveSelected(c: Context, server: Server?) {
        prefs(c).edit().putString("selected", server?.raw).apply()
    }

    fun loadSubUrl(c: Context): String = prefs(c).getString("sub_url", "").orEmpty()
    fun saveSubUrl(c: Context, url: String) { prefs(c).edit().putString("sub_url", url).apply() }

    fun clear(c: Context) {
        listFile(c).delete()
        prefs(c).edit().clear().apply()
    }

    /** Нормализует ссылку подписки (happ://add/https://… → https://…). */
    fun normalizeUrl(address: String): String = address.trim().removePrefix("happ://add/")

    fun isSubscriptionUrl(s: String): Boolean {
        val t = normalizeUrl(s)
        return t.startsWith("https://") && !t.contains('\n') && !t.contains(' ')
    }

    suspend fun fetchText(context: Context, address: String): String = withContext(Dispatchers.IO) {
        val url = normalizeUrl(address)
        require(url.startsWith("https://")) { "Нужна HTTPS-ссылка подписки" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            // Панели (Remnawave и др.) выбирают содержимое подписки по User-Agent.
            // Представляемся как Happ и передаём идентификатор устройства (HWID), как это делает Happ.
            val hwid = runCatching {
                android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()
            setRequestProperty("User-Agent", "Happ/2.4.0")
            setRequestProperty("Accept", "*/*")
            if (hwid.isNotBlank()) setRequestProperty("x-hwid", hwid)
            setRequestProperty("x-device-os", "Android")
            setRequestProperty("x-ver-os", android.os.Build.VERSION.RELEASE.orEmpty())
            setRequestProperty("x-device-model", android.os.Build.MODEL.orEmpty())
            instanceFollowRedirects = true
        }
        try {
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            require(text.length <= 2 * 1024 * 1024) { "Ответ слишком большой" }
            require(text.isNotBlank()) { "Подписка пустая" }
            text
        } finally {
            conn.disconnect()
        }
    }

    /** Разбирает WireGuard-конфиг, vless://-ссылки, base64-подписку или список ссылок. */
    fun parse(input: String): List<Server> {
        var text = input.trim()
        if (text.isEmpty()) return emptyList()
        parseJson(text)?.let { return it }

        if (text.contains("[Interface]") && text.contains("[Peer]")) return listOf(wgServer(text))

        if (!text.contains("://")) {
            text = runCatching {
                String(Base64.decode(text.filter { !it.isWhitespace() }, Base64.DEFAULT or Base64.URL_SAFE))
            }.getOrDefault(text)
            parseJson(text.trim())?.let { return it }
            if (text.contains("[Interface]") && text.contains("[Peer]")) return listOf(wgServer(text))
        }

        return text.lines().mapNotNull { parseOne(it) }
    }

    /** Одна запись: WG-конфиг целиком или одна ссылка вида proto://… */
    private fun parseOne(raw: String): Server? {
        val t = raw.trim()
        return when {
            t.startsWith("{") -> parseJson(t)?.firstOrNull()
            t.contains("[Interface]") && t.contains("[Peer]") -> wgServer(t)
            !t.contains("://") || t.startsWith("#") -> null
            else -> linkServer(t)
        }
    }

    private val stubHosts = setOf("0.0.0.0", "127.0.0.1", "localhost", "::", "::1")
    private const val STUB_REASON = "служебная запись подписки, не сервер"

    /** JSON-подписка: массив (или один объект) готовых Xray-конфигов с полем remarks. */
    private fun parseJson(text: String): List<Server>? {
        if (!text.startsWith("[") && !text.startsWith("{")) return null
        return runCatching {
            val items = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
            (0 until items.length()).mapNotNull { jsonServer(items.optJSONObject(it)) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun jsonServer(obj: JSONObject?): Server? {
        if (obj == null) return null
        val name = obj.optString("remarks").ifBlank { "VLESS" }
        val raw = obj.toString()
        val proxy = XrayJson.findProxy(obj)
            ?: return Server(name, "json", raw, unsupportedReason = "в конфиге нет VLESS-outbound")
        val ep = XrayJson.endpoint(proxy)
            ?: return Server(name, "vless", raw, unsupportedReason = "не указан адрес сервера")
        if (ep.first in stubHosts || ep.second <= 1)
            return Server(name, "vless", raw, ep.first, ep.second, STUB_REASON)
        return Server(name, "vless", raw, ep.first, ep.second)
    }

    private fun linkServer(line: String): Server {
        val proto = line.substringBefore("://").lowercase()
        val tag = line.substringAfter('#', "").let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
        if (proto != "vless") {
            return Server(tag.ifBlank { proto.take(12).uppercase() }, proto.take(12), line,
                unsupportedReason = "протокол ${proto.take(12).uppercase()} не поддерживается")
        }
        return try {
            val c = VlessParser.parse(line)
            Server(c.name.ifBlank { c.host }, "vless", line, c.host, c.port,
                if (c.host in stubHosts || c.port <= 1) STUB_REASON else null)
        } catch (e: IllegalArgumentException) {
            Server(tag.ifBlank { "VLESS" }, "vless", line, unsupportedReason = e.message ?: "некорректная ссылка")
        }
    }

    private fun wgServer(conf: String): Server {
        val endpoint = Regex("""(?im)^\s*Endpoint\s*=\s*(\S+)""").find(conf)?.groupValues?.get(1).orEmpty()
        val host = endpoint.substringBeforeLast(':', endpoint).trim('[', ']')
        val port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: 0
        val title = Regex("""(?m)^#\s*(.+)$""").find(conf)?.groupValues?.get(1)?.trim()
        return Server(title ?: "WireGuard", "wireguard", conf, host, port)
    }

    /** TCP-пинг в мс; для WireGuard (UDP) и при ошибке — null. */
    suspend fun ping(s: Server): Int? = withContext(Dispatchers.IO) {
        if (s.protocol != "vless" || s.host.isBlank() || s.port <= 0) return@withContext null
        runCatching {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(s.host, s.port), 2500) }
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        }.getOrNull()
    }
}
