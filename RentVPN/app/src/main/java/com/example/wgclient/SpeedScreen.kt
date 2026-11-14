package com.example.wgclient

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

data class SpeedUi(
    val running: Boolean = false,
    val phase: SpeedPhase = SpeedPhase.IDLE,
    val live: Double = 0.0,
    val result: SpeedResult? = null,
    val error: String? = null,
    val history: List<SpeedResult> = emptyList(),
)

@Composable
fun SpeedTestScreen(ui: SpeedUi, viaVpn: Boolean, onStart: () -> Unit) {
    val r = ui.result
    val gaugeValue = when (ui.phase) {
        SpeedPhase.DOWN, SpeedPhase.UP -> ui.live
        SpeedPhase.DONE -> r?.downMbps ?: 0.0
        else -> 0.0
    }
    val label = when (ui.phase) {
        SpeedPhase.IDLE -> "Готов к тесту"
        SpeedPhase.PING -> "Измеряем пинг…"
        SpeedPhase.DOWN -> "Загрузка"
        SpeedPhase.UP -> "Отдача"
        SpeedPhase.DONE -> "Загрузка (итог)"
    }
    // шкала логарифмическая, как у спидтестов: 0…1000 Мбит/с
    val fraction = (ln(1.0 + gaugeValue) / ln(1001.0)).coerceIn(0.0, 1.0).toFloat()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Тест скорости", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                if (viaVpn) "ЧЕРЕЗ VPN" else "БЕЗ VPN",
                color = if (viaVpn) Color(0xFF111111) else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape)
                    .background(if (viaVpn) Lime else Surface2).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        SpeedGauge(fraction, if (ui.phase == SpeedPhase.PING) "…" else fmt(gaugeValue), "Мбит/с", label)

        Button(
            onClick = onStart, enabled = !ui.running,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF111111), disabledContainerColor = Surface2, disabledContentColor = Muted),
            shape = RoundedCornerShape(27.dp)
        ) {
            Text(
                when {
                    ui.running -> "Идёт тест…"
                    r != null -> "Повторить тест"
                    else -> "Начать тест"
                }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
        }

        ui.error?.let { Text("Ошибка теста: $it", color = Bad, fontSize = 12.sp) }

        if (r != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Пинг", "${r.pingMs}", "мс", Modifier.weight(1f))
                Metric("Джиттер", "${r.jitterMs}", "мс", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Загрузка", fmt(r.downMbps), "Мбит/с", Modifier.weight(1f))
                Metric("Отдача", fmt(r.upMbps), "Мбит/с", Modifier.weight(1f))
            }
            Text("Сервер: ${r.server}", color = Muted, fontSize = 12.sp)
        }

        if (ui.history.isNotEmpty()) {
            Text("ИСТОРИЯ", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.fillMaxWidth())
            val df = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
            ui.history.forEach { h ->
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, LineC, shape).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("↓ ${fmt(h.downMbps)}   ↑ ${fmt(h.upMbps)} Мбит/с", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${df.format(Date(h.time))} · ${h.pingMs} мс", color = Muted, fontSize = 11.sp)
                    }
                    Text(if (h.viaVpn) "VPN" else "прямое", color = if (h.viaVpn) Lime else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Тест на сервере Cloudflare (speed.cloudflare.com). Результат может отличаться от speedtest.net.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Metric(title: String, value: String, unit: String, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(Surface1).border(1.dp, LineC, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun SpeedGauge(fraction: Float, value: String, unit: String, label: String) {
    val f by animateFloatAsState(fraction, tween(300), label = "gauge")
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val tl = Offset(stroke / 2, stroke / 2)
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(Surface2, 150f, 240f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            if (f > 0.005f) drawArc(Lime, 150f, 240f * f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(value, color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = Muted, fontSize = 13.sp)
        }
    }
}

private fun fmt(v: Double): String = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
