package com.example.wgclient

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnController.init(applicationContext)
        setContent { RentVpnApp() }
    }
}

private data class Traffic(
    val up: Long = 0, val down: Long = 0,
    val upTotal: Long = 0, val downTotal: Long = 0,
    val history: List<Long> = emptyList(),
)

@Composable
private fun RentVpnApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val state by VpnController.state.collectAsState()
    val error by VpnController.error.collectAsState()
    val connectedAt by VpnController.connectedAt.collectAsState()

    var servers by remember { mutableStateOf(ServerStore.load(context)) }
    var selected by remember { mutableIntStateOf(ServerStore.loadSelected(context, servers)) }
    var subUrl by remember { mutableStateOf(ServerStore.loadSubUrl(context)) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val pings = remember { mutableStateMapOf<String, Int>() } // raw -> мс, -1 = нет ответа
    var traffic by remember { mutableStateOf(Traffic()) }
    var elapsed by remember { mutableLongStateOf(0L) }

    val server = servers.getOrNull(selected)?.takeIf { it.supported }
    var speed by remember { mutableStateOf(SpeedUi(history = SpeedTest.loadHistory(context))) }

    // ---- служебные эффекты ----
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(error) {
        error?.let { message = it; VpnController.clearError() }
    }
    LaunchedEffect(servers) {
        servers.filter { it.protocol == "vless" && it.supported }.forEach { s ->
            launch { pings[s.raw] = ServerStore.ping(s) ?: -1 }
        }
    }
    LaunchedEffect(state, connectedAt) {
        if (state != VpnState.CONNECTED) { elapsed = 0; return@LaunchedEffect }
        while (true) {
            elapsed = (SystemClock.elapsedRealtime() - connectedAt) / 1000
            delay(500)
        }
    }
    LaunchedEffect(state) {
        if (state != VpnState.CONNECTED) { traffic = Traffic(); return@LaunchedEffect }
        val uid = Process.myUid()
        fun rx() = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        fun tx() = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        val baseRx = rx(); val baseTx = tx()
        var lastRx = baseRx; var lastTx = baseTx
        while (true) {
            delay(1000)
            val r = rx(); val t = tx()
            val down = (r - lastRx).coerceAtLeast(0); val up = (t - lastTx).coerceAtLeast(0)
            lastRx = r; lastTx = t
            traffic = Traffic(up, down, t - baseTx, r - baseRx, (traffic.history + down).takeLast(60))
        }
    }

    // ---- действия ----
    fun runSpeed() {
        if (speed.running) return
        val socks = if (state == VpnState.CONNECTED) VpnController.socksForTest(context) else null
        val via = state == VpnState.CONNECTED && (socks != null || VpnController.isWireGuardConnected())
        speed = speed.copy(running = true, phase = SpeedPhase.PING, live = 0.0, error = null)
        scope.launch {
            runCatching { SpeedTest.run(socks, via) { ph, v -> speed = speed.copy(phase = ph, live = v) } }
                .onSuccess { r ->
                    SpeedTest.addHistory(context, r)
                    speed = speed.copy(running = false, phase = SpeedPhase.DONE, live = 0.0, result = r, history = SpeedTest.loadHistory(context))
                }
                .onFailure { speed = speed.copy(running = false, phase = SpeedPhase.IDLE, live = 0.0, error = it.message ?: "ошибка сети") }
        }
    }

    fun connectNow() {
        val s = server ?: return
        ServerStore.saveSelected(context, s)
        VpnController.connect(context, s)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectNow() else message = "Разрешение Android VPN не выдано"
    }

    fun toggle() {
        if (state != VpnState.DISCONNECTED) { VpnController.disconnect(context); return }
        if (server == null) { message = "Сначала добавьте сервер"; tab = 2; return }
        val intent = VpnService.prepare(context)
        if (intent != null) permissionLauncher.launch(intent) else connectNow()
    }

    fun applyServers(list: List<Server>, replace: Boolean) {
        if (list.isEmpty()) { message = "Не удалось распознать серверы"; return }
        val prevRaw = server?.raw
        val merged = if (replace) list else (servers + list).distinctBy { it.raw }
        servers = merged
        ServerStore.save(context, merged)
        var idx = merged.indexOfFirst { it.raw == prevRaw && it.supported }
        if (idx < 0) idx = merged.indexOfFirst { it.supported }
        selected = idx
        ServerStore.saveSelected(context, merged.getOrNull(idx))
        message = "Серверов: ${list.size}, доступно: ${list.count { it.supported }}"
    }

    fun fetchSubscription(url: String) {
        scope.launch {
            busy = true
            runCatching { ServerStore.fetchText(context, url) }
                .onSuccess {
                    subUrl = ServerStore.normalizeUrl(url)
                    ServerStore.saveSubUrl(context, subUrl)
                    applyServers(ServerStore.parse(it), replace = true)
                }
                .onFailure { message = "Не удалось загрузить: ${it.message ?: "ошибка сети"}" }
            busy = false
        }
    }

    fun addInput(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (ServerStore.isSubscriptionUrl(t)) fetchSubscription(t)
        else { applyServers(ServerStore.parse(t), replace = false); input = "" }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching { readText(context, uri) }
            .onSuccess { applyServers(ServerStore.parse(it), replace = false) }
            .onFailure { message = "Не удалось прочитать файл" }
    }

    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { InputImage.fromFilePath(context, uri) }
                .onSuccess { image ->
                    BarcodeScanning.getClient().process(image)
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                            if (value.isNullOrBlank()) message = "QR-код не найден на изображении"
                            else addInput(value)
                        }
                        .addOnFailureListener { message = "Не удалось прочитать QR-код: ${it.message ?: "ошибка"}" }
                }
                .onFailure { message = "Не удалось открыть изображение QR-кода" }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lime, onPrimary = Color(0xFF111111), background = Bg, surface = Surface1,
            onSurface = Color.White, onBackground = Color.White, outline = LineC
        )
    ) {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(
                        state = state, server = server, ping = server?.let { pings[it.raw] },
                        traffic = traffic, elapsed = elapsed, message = message,
                        onToggle = { toggle() }, onServers = { tab = 2 }, onPro = { tab = 3 }
                    )
                    1 -> SpeedTestScreen(speed, viaVpn = state == VpnState.CONNECTED && (VpnController.isWireGuardConnected() || VpnController.socksForTest(context) != null), onStart = { runSpeed() })
                    2 -> ServerScreen(
                        servers = servers, selected = selected, pings = pings, input = input,
                        subUrl = subUrl, message = message, busy = busy,
                        onInput = { input = it },
                        onAdd = { addInput(input) },
                        onFile = { filePicker.launch(arrayOf("*/*")) },
                        onQr = { qrPicker.launch(arrayOf("image/*")) },
                        onPaste = { clipboard.getText()?.text?.let { addInput(it) } ?: run { message = "Буфер обмена пуст" } },
                        onRefresh = { fetchSubscription(subUrl) },
                        onPick = { i ->
                            val s = servers[i]
                            if (!s.supported) message = "${s.name}: ${s.unsupportedReason}"
                            else {
                                if (state != VpnState.DISCONNECTED) VpnController.disconnect(context)
                                selected = i; ServerStore.saveSelected(context, s); message = "Выбран: ${s.name}"
                            }
                        },
                        onDelete = { i ->
                            val next = servers.filterIndexed { j, _ -> j != i }
                            val keepRaw = server?.raw
                            servers = next; ServerStore.save(context, next)
                            selected = next.indexOfFirst { it.raw == keepRaw && it.supported }.let { k ->
                                if (k >= 0) k else next.indexOfFirst { it.supported }
                            }
                            ServerStore.saveSelected(context, next.getOrNull(selected))
                        }
                    )
                    else -> ProfileScreen(onClear = {
                        if (state != VpnState.DISCONNECTED) VpnController.disconnect(context)
                        servers = emptyList(); selected = -1; subUrl = ""; pings.clear()
                        ServerStore.clear(context)
                        message = "Локальные данные удалены"
                    })
                }
            }
            BottomNav(tab) { tab = it }
        }
    }
}

// ======================= HOME =======================

@Composable
private fun HomeScreen(
    state: VpnState, server: Server?, ping: Int?, traffic: Traffic, elapsed: Long, message: String,
    onToggle: () -> Unit, onServers: () -> Unit, onPro: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // верхняя строка: статус + PRO
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShieldIcon(state == VpnState.CONNECTED, Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Статус подключения", color = Muted, fontSize = 10.sp)
                Text(
                    when (state) {
                        VpnState.DISCONNECTED -> "Не подключено"
                        VpnState.CONNECTING -> "Подключение…"
                        VpnState.CONNECTED -> "Подключено"
                    },
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
            }
            Text("PRO", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Lime)
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onPro),
                contentAlignment = Alignment.Center
            ) { CrownIcon(Modifier.size(22.dp)) }
        }

        // таймер (место резервируется, чтобы макет не прыгал)
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            if (state == VpnState.CONNECTED) {
                Text(formatTime(elapsed), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
        }

        // скорость
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SpeedCell("↑", "Отдача", traffic.up, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(38.dp).background(LineC))
            SpeedCell("↓", "Загрузка", traffic.down, Modifier.weight(1f))
        }

        // шар + кнопка
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ParticleOrb(state, Modifier.fillMaxWidth().aspectRatio(1f))
            PowerButton(state, onToggle)
        }

        if (message.isNotBlank()) {
            val bad = message.contains("Не удалось") || message.contains("Ошибка") || message.contains("не поддерж") ||
                message.contains("Некорректн") || message.contains("не выдано") || message.contains("Сначала")
            Text(
                message, color = if (bad) Bad else Lime, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), maxLines = 3, overflow = TextOverflow.Ellipsis
            )
        }

        ServerPill(server, ping, onServers)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SpeedCell(arrow: String, label: String, bps: Long, modifier: Modifier) {
    val (value, unit) = formatSpeed(bps)
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, color = Color(0xFFBDBDBD), fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = Muted, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(3.dp))
                Text(unit, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun ServerPill(server: Server?, ping: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF1C1C1C), Color(0xFF2A2A2A))))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (server == null) {
            Text("Сервер не выбран — нажмите, чтобы добавить", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        } else {
            FlagBubble(server.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName(server.name), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.host.ifBlank { server.protocol.uppercase() }, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val p = ping?.takeIf { it >= 0 }
            PingBars(p, Modifier.size(width = 24.dp, height = 14.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (p != null) "$p мс" else "—", color = Color.White, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = Muted, fontSize = 22.sp)
    }
}

@Composable
private fun FlagBubble(name: String) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
        Text(flagOf(name) ?: "🌐", fontSize = 20.sp)
    }
}

// ======================= SERVERS =======================

@Composable
private fun ServerScreen(
    servers: List<Server>, selected: Int, pings: Map<String, Int>, input: String, subUrl: String,
    message: String, busy: Boolean,
    onInput: (String) -> Unit, onAdd: () -> Unit, onFile: () -> Unit, onQr: () -> Unit, onPaste: () -> Unit,
    onRefresh: () -> Unit, onPick: (Int) -> Unit, onDelete: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Серверы", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = input, onValueChange = onInput, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
            label = { Text("Подписка, VLESS / VMess / Trojan / SS / SOCKS5 / HTTP или WireGuard") },
            colors = fieldColors(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            shape = RoundedCornerShape(18.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAdd, enabled = input.isNotBlank() && !busy, modifier = Modifier.weight(1.3f),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF111111), disabledContainerColor = Surface2),
                shape = RoundedCornerShape(16.dp)
            ) { Text(if (busy) "Загрузка…" else "Добавить", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Из буфера", color = Lime) }
            OutlinedButton(onClick = onFile, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Файл", color = Lime) }
        }
        OutlinedButton(onClick = onQr, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("Импорт QR-кода из изображения", color = Lime)
        }
        if (subUrl.isNotBlank()) {
            OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text("Обновить подписку", color = Lime)
            }
        }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 12.sp)

        if (servers.isNotEmpty()) {
            Text("СПИСОК (${servers.size})", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
            servers.forEachIndexed { i, s ->
                val isSel = i == selected
                val shape = RoundedCornerShape(22.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(if (isSel) Surface2 else Surface1)
                        .border(1.dp, if (isSel) Lime.copy(alpha = .7f) else LineC, shape)
                        .clickable { onPick(i) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlagBubble(s.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayName(s.name), color = if (s.supported) Color.White else Muted,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            s.protocol.uppercase() + if (s.supported) "" else " · ${s.unsupportedReason}",
                            color = if (s.supported) Muted else Bad.copy(alpha = .8f), fontSize = 11.sp, maxLines = 2
                        )
                    }
                    val p = pings[s.raw]?.takeIf { it >= 0 }
                    if (s.protocol == "vless" && s.supported) {
                        PingBars(p, Modifier.size(width = 22.dp, height = 13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (p != null) "$p" else "—", color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (isSel) Text("✓", color = Lime, fontSize = 18.sp)
                    Box(Modifier.size(34.dp).clip(CircleShape).clickable { onDelete(i) }, contentAlignment = Alignment.Center) {
                        Text("✕", color = Muted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ======================= PROFILE =======================

@Composable
private fun ProfileScreen(onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Профиль", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        InfoRow("Приложение", "RENT VPN · версия 1.2")
        InfoRow("Прокси", "VLESS (TLS / Reality), VMess, Trojan, Shadowsocks, SOCKS5, HTTP Proxy")
        InfoRow("WireGuard", "Импорт .conf, вставка из буфера")
        InfoRow("Импорт", "Ссылки, HTTPS-подписки, файлы и QR-код из изображения")
        InfoRow("Хранение", "Серверы и подписка — локально на устройстве")
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("Удалить сохранённые данные", color = Bad)
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Card2 {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(value, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Card2(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, LineC, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Lime, unfocusedBorderColor = LineC,
    focusedLabelColor = Lime, unfocusedLabelColor = Muted, cursorColor = Lime
)

// ======================= NAV =======================

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("Главная", "Скорость", "Серверы", "Профиль")
    Row(
        Modifier.fillMaxWidth().background(Bg).navigationBarsPadding().padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }.padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NavIcon(i, if (on) Color.White else Muted, Modifier.size(26.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, color = if (on) Color.White else Muted, fontSize = 10.sp)
            }
        }
    }
}

// ======================= helpers =======================

private fun formatTime(sec: Long): String = "%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)

private fun formatSpeed(bps: Long): Pair<String, String> = when {
    bps >= 1_000_000 -> "%.1f".format(bps / 1_000_000.0) to "МБ/с"
    else -> "%d".format(bps / 1000) to "КБ/с"
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.2f ГБ".format(b / 1e9)
    b >= 1_000_000 -> "%.1f МБ".format(b / 1e6)
    else -> "%d КБ".format(b / 1000)
}

/** Эмодзи-флаг в начале названия сервера (пара региональных индикаторов), если есть. */
private fun flagOf(name: String): String? {
    if (name.length < 4) return null
    val a = name.codePointAt(0)
    val b = name.codePointAt(Character.charCount(a))
    val isRi = { c: Int -> c in 0x1F1E6..0x1F1FF }
    return if (isRi(a) && isRi(b)) name.substring(0, Character.charCount(a) + Character.charCount(b)) else null
}

private fun displayName(name: String): String = flagOf(name)?.let { name.removePrefix(it).trim() } ?: name

private fun readText(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
