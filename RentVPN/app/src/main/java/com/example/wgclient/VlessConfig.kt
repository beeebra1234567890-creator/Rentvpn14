package com.example.wgclient

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** Разобранная ссылка vless://uuid@host:port?...#name */
data class VlessConfig(
    val uuid: String,
    val host: String,
    val port: Int,
    val name: String,
    val encryption: String = "none",
    val flow: String = "",
    val network: String = "tcp",      // tcp | ws | grpc | httpupgrade | xhttp | http
    val security: String = "none",    // none | tls | reality
    val headerType: String = "none",  // для tcp: none | http
    val path: String = "",
    val hostHeader: String = "",
    val sni: String = "",
    val fingerprint: String = "",
    val alpn: List<String> = emptyList(),
    val allowInsecure: Boolean = false,
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val serviceName: String = "",
    val grpcMulti: Boolean = false,
    val xhttpMode: String = "",
)

object VlessParser {
    private fun dec(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** @throws IllegalArgumentException если ссылка некорректна или транспорт не поддерживается */
    fun parse(link: String): VlessConfig {
        val src = link.trim()
        require(src.startsWith("vless://", ignoreCase = true)) { "Не VLESS-ссылка" }
        var rest = src.substring(8)

        val name = dec(rest.substringAfter('#', ""))
        rest = rest.substringBefore('#')
        val query = rest.substringAfter('?', "")
        rest = rest.substringBefore('?').trimEnd('/')

        require(rest.contains('@')) { "Нет UUID в ссылке" }
        val uuid = dec(rest.substringBeforeLast('@')).trim()
        require(uuid.isNotBlank()) { "Пустой UUID" }
        val hostPort = rest.substringAfterLast('@')

        val host: String
        val portStr: String
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            require(end > 0) { "Некорректный IPv6-адрес" }
            host = hostPort.substring(1, end)
            portStr = hostPort.substring(end + 1).removePrefix(":")
        } else if (hostPort.contains(':')) {
            host = hostPort.substringBeforeLast(':')
            portStr = hostPort.substringAfterLast(':')
        } else {
            host = hostPort
            portStr = ""
        }
        require(host.isNotBlank()) { "Пустой адрес сервера" }
        val port = (if (portStr.isBlank()) 443 else portStr.toIntOrNull()) ?: throw IllegalArgumentException("Некорректный порт")
        require(port in 1..65535) { "Порт вне диапазона" }

        val q = HashMap<String, String>()
        query.split('&').filter { it.isNotBlank() }.forEach {
            q[it.substringBefore('=').lowercase()] = dec(it.substringAfter('=', ""))
        }

        val network = when (val t = (q["type"] ?: "tcp").lowercase()) {
            "tcp", "raw" -> "tcp"
            "ws" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "h2", "http" -> "http"
            else -> throw IllegalArgumentException("Транспорт «$t» не поддерживается")
        }
        val security = when (val s = (q["security"] ?: "none").lowercase()) {
            "", "none" -> "none"
            "tls" -> "tls"
            "reality" -> "reality"
            else -> throw IllegalArgumentException("Security «$s» не поддерживается")
        }
        if (security == "reality") require(!q["pbk"].isNullOrBlank()) { "Reality: нет publicKey (pbk)" }

        return VlessConfig(
            uuid = uuid,
            host = host,
            port = port,
            name = name,
            encryption = q["encryption"]?.ifBlank { null } ?: "none",
            flow = q["flow"].orEmpty(),
            network = network,
            security = security,
            headerType = (q["headertype"] ?: "none").lowercase(),
            path = q["path"].orEmpty(),
            hostHeader = q["host"].orEmpty(),
            sni = (q["sni"] ?: q["peer"]).orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            alpn = q["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            allowInsecure = q["allowinsecure"] == "1" || q["allowinsecure"] == "true" || q["insecure"] == "1",
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            serviceName = q["servicename"].orEmpty(),
            grpcMulti = q["mode"] == "multi",
            xhttpMode = q["mode"].orEmpty(),
        )
    }
}

/** Собирает полный JSON-конфиг Xray: tun-inbound (fd от VpnService) → VLESS-outbound. */
fun VlessConfig.toXrayJson(socks: SocksInfo? = null): String {
    val user = JSONObject()
        .put("id", uuid)
        .put("encryption", encryption)
        .put("level", 0)
    if (flow.isNotBlank()) user.put("flow", flow)

    val proxy = JSONObject()
        .put("tag", "proxy")
        .put("protocol", "vless")
        .put("settings", JSONObject().put("vnext", JSONArray().put(
            JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user))
        )))
        .put("streamSettings", streamSettings())
    return XrayJson.tunConfig(proxy, socks)
}

/** Работа с готовыми Xray-конфигами (формат JSON-подписок панелей). */
object XrayJson {
    /** Первый VLESS-outbound (приоритет у tag="proxy"). */
    fun findProxy(cfg: JSONObject): JSONObject? {
        val outs = cfg.optJSONArray("outbounds") ?: return null
        var first: JSONObject? = null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol").lowercase() == "vless") {
                if (o.optString("tag") == "proxy") return o
                if (first == null) first = o
            }
        }
        return first
    }

    fun endpoint(proxy: JSONObject): Pair<String, Int>? {
        val v = proxy.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: return null
        val addr = v.optString("address")
        val port = v.optInt("port", 443)
        return if (addr.isBlank()) null else addr to port
    }

    /** Берёт VLESS-outbound из чужого конфига как есть, остальное заменяет на своё (tun + DNS). */
    fun build(raw: String, socks: SocksInfo? = null): String {
        val src = JSONObject(raw)
        val proxy = findProxy(src) ?: throw IllegalArgumentException("в конфиге нет VLESS-outbound")
        val copy = JSONObject(proxy.toString())
        copy.put("tag", "proxy")
        return tunConfig(copy, socks)
    }

    fun tunConfig(proxy: JSONObject, socks: SocksInfo? = null): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        val inbounds = JSONArray()
        // Локальный SOCKS (только 127.0.0.1, с паролем) — для теста скорости внутри туннеля
        if (socks != null) inbounds.put(
            JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", socks.port)
                .put("protocol", "socks")
                .put("settings", JSONObject()
                    .put("auth", "password")
                    .put("udp", false)
                    .put("accounts", JSONArray().put(JSONObject().put("user", socks.user).put("pass", socks.pass))))
        )
        inbounds.put(
            JSONObject()
                .put("tag", "tun-in")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 0))
                .put("sniffing", JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic")))
        )
        root.put("inbounds", inbounds)
        root.put("outbounds", JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
        root.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
        return root.toString()
    }
}

private fun VlessConfig.streamSettings(): JSONObject {
    val ss = JSONObject().put("network", network).put("security", security)
    val fp = fingerprint.ifBlank { "chrome" }

    when (security) {
        "tls" -> ss.put("tlsSettings", JSONObject().apply {
            put("serverName", sni.ifBlank { hostHeader.ifBlank { host } })
            put("fingerprint", fp)
            put("allowInsecure", allowInsecure)
            if (alpn.isNotEmpty()) put("alpn", JSONArray(alpn))
        })
        "reality" -> ss.put("realitySettings", JSONObject().apply {
            put("serverName", sni.ifBlank { host })
            put("fingerprint", fp)
            put("publicKey", publicKey)
            put("shortId", shortId)
            put("spiderX", spiderX)
        })
    }

    when (network) {
        "tcp" -> if (headerType == "http") {
            ss.put("tcpSettings", JSONObject().put("header", JSONObject()
                .put("type", "http")
                .put("request", JSONObject()
                    .put("path", JSONArray().put(path.ifBlank { "/" }))
                    .put("headers", JSONObject().apply {
                        if (hostHeader.isNotBlank())
                            put("Host", JSONArray(hostHeader.split(',').map { it.trim() }))
                    }))))
        }
        "ws" -> ss.put("wsSettings", JSONObject().apply {
            put("path", path.ifBlank { "/" })
            if (hostHeader.isNotBlank()) put("headers", JSONObject().put("Host", hostHeader))
        })
        "grpc" -> ss.put("grpcSettings", JSONObject()
            .put("serviceName", serviceName)
            .put("multiMode", grpcMulti))
        "httpupgrade" -> ss.put("httpupgradeSettings", JSONObject().apply {
            put("path", path.ifBlank { "/" })
            if (hostHeader.isNotBlank()) put("host", hostHeader)
        })
        "xhttp" -> ss.put("xhttpSettings", JSONObject().apply {
            put("path", path.ifBlank { "/" })
            if (hostHeader.isNotBlank()) put("host", hostHeader)
            if (xhttpMode.isNotBlank()) put("mode", xhttpMode)
        })
        "http" -> ss.put("httpSettings", JSONObject().apply {
            put("path", path.ifBlank { "/" })
            if (hostHeader.isNotBlank()) put("host", JSONArray(hostHeader.split(',').map { it.trim() }))
        })
    }
    return ss
}
