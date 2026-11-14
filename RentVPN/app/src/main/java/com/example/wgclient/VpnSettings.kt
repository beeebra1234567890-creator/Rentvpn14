package com.example.wgclient

import android.content.Context

/** Настройки сетевого туннеля RENT VPN. */
object VpnSettings {
    private const val PREFS = "rentvpn_preferences"
    const val DNS_CLOUDFLARE = "cloudflare"
    const val DNS_GOOGLE = "google"
    const val DNS_QUAD9 = "quad9"

    fun dnsMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dns_mode", DNS_CLOUDFLARE) ?: DNS_CLOUDFLARE

    fun dnsServers(context: Context): List<String> = when (dnsMode(context)) {
        DNS_GOOGLE -> listOf("8.8.8.8", "8.8.4.4")
        DNS_QUAD9 -> listOf("9.9.9.9", "149.112.112.112")
        else -> listOf("1.1.1.1", "1.0.0.1")
    }

    fun setDnsMode(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dns_mode", value).apply()
    }

    fun mtu(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("mtu", 1500).coerceIn(1280, 1500)

    fun setMtu(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("mtu", value.coerceIn(1280, 1500)).apply()
    }

    fun ipv6Enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ipv6", true)

    fun setIpv6Enabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ipv6", enabled).apply()
    }
}
