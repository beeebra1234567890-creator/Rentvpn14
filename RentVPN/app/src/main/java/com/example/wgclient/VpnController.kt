package com.example.wgclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Единая точка управления туннелем. Два движка:
 *  - VLESS → [XrayVpnService] (Xray-core через libv2ray.aar);
 *  - WireGuard → GoBackend из официальной библиотеки.
 */
object VpnController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** SystemClock.elapsedRealtime() в момент установки соединения. */
    private val _connectedAt = MutableStateFlow(0L)
    val connectedAt: StateFlow<Long> = _connectedAt.asStateFlow()

    private var wgBackend: GoBackend? = null
    private var activeProtocol: String = ""

    private val wgTunnel = object : Tunnel {
        override fun getName(): String = "rentvpn"
        override fun onStateChange(newState: Tunnel.State) {
            if (activeProtocol != "wireguard") return
            if (newState == Tunnel.State.UP) setState(VpnState.CONNECTED) else setState(VpnState.DISCONNECTED)
        }
    }

    private fun wg(context: Context): GoBackend =
        wgBackend ?: GoBackend(context.applicationContext).also { wgBackend = it }

    private var receiverRegistered = false

    /**
     * Слушает состояние VPN-сервиса (он живёт в отдельном процессе :vpn) и при старте
     * приложения запрашивает текущее состояние — на случай, если VPN уже включён.
     */
    fun init(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val app = context.applicationContext
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i == null || activeProtocol == "wireguard") return
                val st = runCatching { VpnState.valueOf(i.getStringExtra(XrayVpnService.EXTRA_STATE) ?: "") }
                    .getOrNull() ?: return
                i.getStringExtra(XrayVpnService.EXTRA_ERROR)?.let { _error.value = it }
                if (activeProtocol.isEmpty()) activeProtocol = "vless"
                if (st == VpnState.CONNECTED) {
                    _connectedAt.value = i.getLongExtra(XrayVpnService.EXTRA_CONNECTED_AT, 0L)
                        .takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
                }
                _state.value = st
            }
        }, IntentFilter(XrayVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        app.sendBroadcast(Intent(XrayVpnService.ACTION_QUERY).setPackage(app.packageName))
    }

    // ---- внутренние обновления состояния ----
    internal fun setState(s: VpnState) {
        if (s == VpnState.CONNECTED) _connectedAt.value = SystemClock.elapsedRealtime()
        _state.value = s
    }

    internal fun fail(message: String) {
        _error.value = message
        _state.value = VpnState.DISCONNECTED
    }

    fun clearError() { _error.value = null }

    private fun prefs(c: Context) = c.getSharedPreferences("rentvpn", Context.MODE_PRIVATE)

    /** Новый случайный порт и пароль локального SOCKS-входа на каждое подключение. */
    private fun newSocks(c: Context): SocksInfo {
        val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(10808 + (0..999).random())
        val info = SocksInfo(port, "u" + UUID.randomUUID().toString().take(8), UUID.randomUUID().toString().replace("-", ""))
        prefs(c).edit().putInt("socks_port", info.port).putString("socks_user", info.user)
            .putString("socks_pass", info.pass).apply()
        return info
    }

    /** SOCKS для теста внутри туннеля; null — если активен не VLESS (WireGuard тестируется напрямую). */
    fun socksForTest(c: Context): SocksInfo? {
        if (_state.value != VpnState.CONNECTED || activeProtocol != "vless") return null
        val p = prefs(c)
        val port = p.getInt("socks_port", 0)
        val user = p.getString("socks_user", null)
        val pass = p.getString("socks_pass", null)
        return if (port > 0 && user != null && pass != null) SocksInfo(port, user, pass) else null
    }

    fun isWireGuardConnected(): Boolean = _state.value == VpnState.CONNECTED && activeProtocol == "wireguard"

    /** Разрешение VpnService уже должно быть выдано (VpnService.prepare). */
    fun connect(context: Context, server: Server) {
        if (_state.value != VpnState.DISCONNECTED) return
        val app = context.applicationContext
        _error.value = null
        _state.value = VpnState.CONNECTING
        activeProtocol = server.protocol

        when (server.protocol) {
            "vless" -> {
                val socks = newSocks(app)
                val json = try {
                    if (server.raw.trimStart().startsWith("{")) XrayJson.build(server.raw, socks)
                    else VlessParser.parse(server.raw).toXrayJson(socks)
                } catch (e: Exception) {
                    fail("Некорректная VLESS-ссылка: ${e.message}")
                    return
                }
                val intent = Intent(app, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_START)
                    .putExtra(XrayVpnService.EXTRA_CONFIG, json)
                runCatching { ContextCompat.startForegroundService(app, intent) }
                    .onFailure { fail("Не удалось запустить VPN-сервис: ${it.message}") }
            }
            "wireguard" -> scope.launch {
                runCatching {
                    require(server.raw.contains("[Interface]") && server.raw.contains("[Peer]")) {
                        "Ожидается WireGuard-конфигурация с [Interface] и [Peer]"
                    }
                    val config = Config.parse(server.raw.byteInputStream())
                    wg(app).setState(wgTunnel, Tunnel.State.UP, config)
                }.onFailure { fail("Не удалось подключиться: ${it.message ?: "проверьте конфигурацию"}") }
            }
            else -> fail("Протокол ${server.protocol.uppercase()} не поддерживается")
        }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext
        when (activeProtocol) {
            "vless" -> {
                app.stopService(Intent(app, XrayVpnService::class.java))
                setState(VpnState.DISCONNECTED)
            }
            "wireguard" -> scope.launch {
                runCatching { wg(app).setState(wgTunnel, Tunnel.State.DOWN, null) }
                    .onFailure { _error.value = it.message ?: "Ошибка отключения" }
                setState(VpnState.DISCONNECTED)
            }
            else -> setState(VpnState.DISCONNECTED)
        }
    }
}
