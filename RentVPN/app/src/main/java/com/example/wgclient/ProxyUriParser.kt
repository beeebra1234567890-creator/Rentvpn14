package com.example.wgclient

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * URI adapters for proxy protocols implemented by the bundled Xray core.
 * Deliberately does not advertise OpenVPN, IKEv2 or SSH as supported: those
 * need separate engines and are not provided by this project.
 */
object ProxyUriParser {
    data class Parsed(
        val protocol: String,
        val name: String,
        val host: String,
        val port: Int,
        val outbound: JSONObject,
    )

    fun parse(raw: String): Parsed {
        val value = raw.trim()
        return when (value.substringBefore("://").lowercase()) {
            "vmess" -> parseVmess(value)
            "trojan" -> parseTrojan(value)
            "ss", "shadowsocks" -> parseShadowsocks(value)
            "socks", "socks5" -> parseSocks(value)
            "http", "https" -> parseHttp(value)
            else -> throw IllegalArgumentException("Этот формат ссылки не поддерживается")
        }
    }

    fun toXrayJson(raw: String, socks: SocksInfo? = null): String =
        XrayJson.tunConfig(parse(raw).outbound, socks)

    private fun decodeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun query(uri: URI): Map<String, String> =
        (uri.rawQuery ?: "").split('&').filter { it.isNotBlank() }.associate {
            val key = URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase()
            val value = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            key to value
        }

    private fun tag(uri: URI, fallback: String): String =
        uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() } ?: fallback

    private fun tls(host: String, sni: String = host): JSONObject =
        JSONObject().put("security", "tls").put("tlsSettings",
            JSONObject().put("serverName", sni.ifBlank { host }).put("fingerprint", "chrome"))

    private fun transport(network: String, path: String, hostHeader: String, serviceName: String = ""): JSONObject {
        val ss = JSONObject().put("network", network)
        when (network) {
            "ws" -> ss.put("wsSettings", JSONObject().put("path", path.ifBlank { "/" }).apply {
                if (hostHeader.isNotBlank()) put("headers", JSONObject().put("Host", hostHeader))
            })
            "grpc" -> ss.put("grpcSettings", JSONObject().put("serviceName", serviceName.ifBlank { path }))
            "httpupgrade" -> ss.put("httpupgradeSettings", JSONObject().put("path", path.ifBlank { "/" }).apply {
                if (hostHeader.isNotBlank()) put("host", hostHeader)
            })
            "tcp" -> Unit
            else -> throw IllegalArgumentException("Транспорт $network пока не поддержан")
        }
        return ss
    }

    private fun parseVmess(raw: String): Parsed {
        val obj = JSONObject(decodeBase64(raw.substringAfter("://")))
        val host = obj.optString("add").ifBlank { throw IllegalArgumentException("VMess: нет адреса") }
        val port = obj.optString("port").toIntOrNull() ?: throw IllegalArgumentException("VMess: неверный порт")
        val id = obj.optString("id").ifBlank { throw IllegalArgumentException("VMess: нет UUID") }
        require(port in 1..65535) { "VMess: порт вне диапазона" }
        val network = obj.optString("net", "tcp").lowercase().let { if (it == "http") "tcp" else it }
        val hostHeader = obj.optString("host")
        val path = obj.optString("path")
        val ss = transport(network, path, hostHeader)
        if (obj.optString("tls").equals("tls", true)) {
            val sni = obj.optString("sni").ifBlank { host }
            val t = tls(host, sni)
            val keys = t.keys()
            while (keys.hasNext()) { val k = keys.next(); ss.put(k, t.get(k)) }
        }
        val user = JSONObject().put("id", id)
            .put("alterId", obj.optInt("aid", 0))
            .put("security", obj.optString("scy").ifBlank { "auto" })
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
            .put("streamSettings", ss)
        return Parsed("vmess", obj.optString("ps").ifBlank { "VMess" }, host, port, outbound)
    }

    private fun parseTrojan(raw: String): Parsed {
        val uri = URI(raw)
        val host = uri.host ?: throw IllegalArgumentException("Trojan: нет адреса")
        val port = if (uri.port > 0) uri.port else 443
        require(port in 1..65535) { "Trojan: неверный порт" }
        val password = uri.rawUserInfo?.let { URLDecoder.decode(it, "UTF-8") }
            ?.substringAfter(':', uri.rawUserInfo ?: "")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Trojan: нет пароля")
        val q = query(uri)
        val network = q["type"]?.lowercase()?.takeIf { it in setOf("tcp", "ws", "grpc", "httpupgrade") } ?: "tcp"
        val ss = transport(network, q["path"].orEmpty(), q["host"].orEmpty(), q["servicename"].orEmpty())
        val t = tls(host, q["sni"].orEmpty())
        val keys = t.keys()
        while (keys.hasNext()) { val k = keys.next(); ss.put(k, t.get(k)) }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("password", password))))
            .put("streamSettings", ss)
        return Parsed("trojan", tag(uri, "Trojan"), host, port, outbound)
    }

    private fun parseShadowsocks(raw: String): Parsed {
        val uri = URI(raw)
        var host = uri.host
        var port = uri.port
        var method = ""
        var password = ""
        val userInfo = uri.rawUserInfo
        if (host != null && port > 0 && !userInfo.isNullOrBlank()) {
            val decoded = runCatching { decodeBase64(userInfo) }.getOrElse { URLDecoder.decode(userInfo, "UTF-8") }
            require(decoded.contains(':')) { "Shadowsocks: некорректные учётные данные" }
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':')
        } else {
            // Legacy SIP002 form: ss://BASE64(method:password@host:port)#tag
            val payload = decodeBase64(raw.substringAfter("://").substringBefore('#'))
            val credentials = payload.substringBefore('@')
            val endpoint = payload.substringAfter('@', "")
            require(endpoint.isNotBlank() && credentials.contains(':')) { "Shadowsocks: некорректная ссылка" }
            method = credentials.substringBefore(':')
            password = credentials.substringAfter(':')
            val hp = endpoint.substringAfterLast('@')
            if (hp.startsWith("[")) {
                val close = hp.indexOf(']')
                require(close > 0) { "Shadowsocks: некорректный IPv6" }
                host = hp.substring(1, close)
                port = hp.substring(close + 2).toIntOrNull() ?: 0
            } else {
                host = hp.substringBeforeLast(':')
                port = hp.substringAfterLast(':').toIntOrNull() ?: 0
            }
        }
        require(!host.isNullOrBlank() && port in 1..65535) { "Shadowsocks: неверный сервер или порт" }
        require(method.isNotBlank() && password.isNotBlank()) { "Shadowsocks: нет метода и пароля" }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("method", method).put("password", password))))
        return Parsed("shadowsocks", tag(uri, "Shadowsocks"), host, port, outbound)
    }

    private fun parseSocks(raw: String): Parsed {
        val uri = URI(raw)
        val host = uri.host ?: throw IllegalArgumentException("SOCKS: нет адреса")
        val port = if (uri.port > 0) uri.port else 1080
        val server = JSONObject().put("address", host).put("port", port)
        val userInfo = uri.rawUserInfo
        if (!userInfo.isNullOrBlank()) {
            val decoded = URLDecoder.decode(userInfo, "UTF-8")
            server.put("users", JSONArray().put(JSONObject()
                .put("user", decoded.substringBefore(':'))
                .put("pass", decoded.substringAfter(':', ""))))
        }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return Parsed("socks", tag(uri, "SOCKS5"), host, port, outbound)
    }

    private fun parseHttp(raw: String): Parsed {
        val uri = URI(raw)
        val host = uri.host ?: throw IllegalArgumentException("HTTP Proxy: нет адреса")
        val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 8080
        val server = JSONObject().put("address", host).put("port", port)
        val userInfo = uri.rawUserInfo
        if (!userInfo.isNullOrBlank()) {
            val decoded = URLDecoder.decode(userInfo, "UTF-8")
            server.put("users", JSONArray().put(JSONObject()
                .put("user", decoded.substringBefore(':'))
                .put("pass", decoded.substringAfter(':', ""))))
        }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "http")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        if (uri.scheme.equals("https", true)) outbound.put("streamSettings", tls(host))
        return Parsed("http", tag(uri, "HTTP Proxy"), host, port, outbound)
    }
}
