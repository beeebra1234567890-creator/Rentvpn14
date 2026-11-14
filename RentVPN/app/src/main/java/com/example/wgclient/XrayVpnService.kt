package com.example.wgclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * VPN-сервис для VLESS: поднимает TUN-интерфейс и отдаёт его fd в Xray-core (tun-inbound).
 *
 * Работает в ОТДЕЛЬНОМ процессе (:vpn). При отключении процесс целиком завершается —
 * это гарантирует, что Xray и туннель не останутся жить в фоне. Состояние уходит в
 * основной процесс широковещательными сообщениями ([ACTION_STATE]).
 *
 * Сам процесс исключён из VPN (addDisallowedApplication), чтобы соединение Xray
 * с сервером не зацикливалось в туннель.
 */
class XrayVpnService : VpnService() {

    private var core: CoreController? = null
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    @Volatile private var stopped = false
    @Volatile private var current = VpnState.DISCONNECTED
    @Volatile private var connectedAt = 0L

    // gomobile: Go int → Java long
    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }

    /** Основной процесс спрашивает текущее состояние (после перезапуска UI). */
    private val queryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publish(current)
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this, queryReceiver, IntentFilter(ACTION_QUERY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground() // после startForegroundService обязателен в течение 5 с
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (intent?.action != ACTION_START || config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        publish(VpnState.CONNECTING)
        worker?.interrupt()
        worker = Thread({ start(config) }, "xray-start").also { it.start() }
        return START_NOT_STICKY
    }

    private fun start(config: String) {
        try {
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            if (stopped) return

            val builder = Builder()
                .setSession("RENT VPN")
                .setMtu(VpnSettings.mtu(this))
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
            if (VpnSettings.ipv6Enabled(this)) {
                builder.addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0)
            }
            VpnSettings.dnsServers(this).forEach { builder.addDnsServer(it) }
            runCatching { builder.addDisallowedApplication(packageName) }

            val pfd = builder.establish()
                ?: throw IllegalStateException("Android не выдал VPN-интерфейс (нет разрешения?)")
            if (stopped) { runCatching { pfd.close() }; return }
            tun = pfd

            val controller = Libv2ray.newCoreController(callback)
            core = controller
            controller.startLoop(config, pfd.fd)

            if (stopped) return // уборку сделает onDestroy
            connectedAt = SystemClock.elapsedRealtime()
            publish(VpnState.CONNECTED)
        } catch (e: Throwable) {
            if (!stopped) {
                publish(VpnState.DISCONNECTED, "Не удалось подключиться: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
            }
        }
    }

    private fun cleanup() {
        val c = core
        core = null
        if (c != null) {
            // stopLoop может зависнуть — не ждём дольше 2 с, процесс всё равно будет завершён
            val t = Thread { runCatching { c.stopLoop() } }
            t.start()
            runCatching { t.join(2000) }
        }
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() {
        // Пользователь отключил VPN из системных настроек
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        runCatching { unregisterReceiver(queryReceiver) }
        worker?.interrupt()
        cleanup()
        publish(VpnState.DISCONNECTED)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
        // Жёстко завершаем ТОЛЬКО этот процесс (:vpn): Xray и туннель гарантированно исчезают.
        Process.killProcess(Process.myPid())
    }

    private fun publish(state: VpnState, error: String? = null) {
        current = state
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_STATE, state.name)
                .putExtra(EXTRA_CONNECTED_AT, connectedAt)
                .putExtra(EXTRA_ERROR, error)
        )
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("RENT VPN")
            .setContentText("VPN включён")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val ACTION_START = "com.rentvpn.client.START"
        const val ACTION_STATE = "com.rentvpn.client.STATE"
        const val ACTION_QUERY = "com.rentvpn.client.QUERY"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val EXTRA_CONNECTED_AT = "connected_at"
        private const val CHANNEL = "rentvpn"
        private const val NOTIFICATION_ID = 1
    }
}
