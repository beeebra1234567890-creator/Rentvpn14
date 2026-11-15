package com.example.wgclient

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ---------- Палитра (чёрный фон + лаймовый акцент) ----------
val Bg = Color(0xFF080611)
val Lime = Color(0xFFB56BFF)
val LimeDeep = Color(0xFF7136C8)
val Surface1 = Color(0xFF171222)
val Surface2 = Color(0xFF211832)
val LineC = Color(0xFF38284D)
val Muted = Color(0xFFAAA0BA)
val Good = Color(0xFF55D99A)
val Warn = Color(0xFFFFC857)
val Bad = Color(0xFFFF5C6C)

// ---------- Ненавязчивое мерцание звёзд на фоне ----------
@Composable
fun StarField(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "starfield")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "starTwinkle"
    )
    val stars = remember {
        val random = Random(2411)
        List(54) { Triple(random.nextFloat(), random.nextFloat(), 0.35f + random.nextFloat() * 1.25f) }
    }
    Canvas(modifier) {
        stars.forEachIndexed { index, star ->
            val wave = (sin(phase + index * 1.71f) + 1f) / 2f
            val alpha = 0.12f + wave * 0.34f
            drawCircle(
                color = Color(0xFFDCC5FF).copy(alpha = alpha),
                radius = (star.third + wave * 0.45f) * density,
                center = Offset(star.first * size.width, star.second * size.height)
            )
        }
    }
}

// ---------- Шар из частиц ----------
private const val GROUPS = 3
private const val PER_GROUP = 700

private class Particles {
    val theta = Array(GROUPS) { FloatArray(PER_GROUP) }
    val radius = Array(GROUPS) { FloatArray(PER_GROUP) }
    val seed = Array(GROUPS) { FloatArray(PER_GROUP) }
    val buf = Array(GROUPS) { FloatArray(PER_GROUP * 2) }

    init {
        val rnd = Random(7)
        for (g in 0 until GROUPS) for (i in 0 until PER_GROUP) {
            theta[g][i] = rnd.nextFloat() * (2 * PI).toFloat()
            // большинство частиц у внешнего края кольца
            radius[g][i] = 1f - rnd.nextFloat().let { it * it }
            seed[g][i] = rnd.nextFloat() * (2 * PI).toFloat()
        }
    }
}

@Composable
fun ParticleOrb(state: VpnState, modifier: Modifier = Modifier) {
    val particles = remember { Particles() }
    val paints = remember {
        Array(GROUPS) { g ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeWidth = floatArrayOf(2.2f, 3.2f, 4.4f)[g]
                color = android.graphics.Color.argb(floatArrayOf(150f, 200f, 235f)[g].toInt(), 255, 255, 255)
            }
        }
    }
    val period = if (state == VpnState.CONNECTING) 2600 else 9000
    val transition = rememberInfiniteTransition(label = "orb")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier) {
        val p = phase.value
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f * 0.9f
        val density = this.density
        for (g in 0 until GROUPS) {
            val th = particles.theta[g]; val rd = particles.radius[g]; val sd = particles.seed[g]; val out = particles.buf[g]
            for (i in 0 until PER_GROUP) {
                val a = th[i] + p * 0.08f
                val edge = 0.06f * sin(7f * a + p + sd[i] * 0.3f) + 0.04f * sin(13f * a - p * 1.3f) + 0.03f * sin(3f * a + p * 0.6f)
                val rr = r * (0.66f + 0.34f * rd[i] + edge * rd[i])
                out[i * 2] = cx + rr * cos(a)
                out[i * 2 + 1] = cy + rr * sin(a)
            }
            paints[g].strokeWidth = floatArrayOf(2.2f, 3.2f, 4.4f)[g] * density / 2.6f
            drawContext.canvas.nativeCanvas.drawPoints(out, paints[g])
        }
    }
}

// ---------- Кнопка питания ----------
@Composable
fun PowerButton(state: VpnState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "power")
    val spin = transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin"
    )
    Box(
        modifier
            .size(200.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(200.dp)) {
            val c = center
            val r = size.minDimension / 2f
            // тёмное внешнее кольцо
            drawCircle(Color(0xFF0E0E0E), radius = r * 0.98f, center = c)
            when (state) {
                VpnState.CONNECTED -> {
                    for (k in 1..4) drawCircle(Lime.copy(alpha = 0.07f * (5 - k)), radius = r * (0.82f + 0.05f * k), center = c)
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFFE6FF7A), LimeDeep), center = c, radius = r * 0.82f),
                        radius = r * 0.82f, center = c
                    )
                }
                VpnState.CONNECTING -> {
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFF353535), Color(0xFF232323)), center = c, radius = r * 0.82f),
                        radius = r * 0.82f, center = c
                    )
                    rotate(spin.value, c) {
                        drawArc(
                            Lime, startAngle = 0f, sweepAngle = 110f, useCenter = false,
                            topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    drawCircle(Lime.copy(alpha = 0.25f), radius = r * 0.78f, center = c, style = Stroke(1.dp.toPx()))
                }
                VpnState.DISCONNECTED -> drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF3B3B3B), Color(0xFF262626)), center = c, radius = r * 0.82f),
                    radius = r * 0.82f, center = c
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val iconColor = if (state == VpnState.CONNECTED) Color(0xFF111111) else Color(0xFFB9B9B9)
            PowerIcon(iconColor, Modifier.size(54.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                when (state) {
                    VpnState.DISCONNECTED -> "Подключить"
                    VpnState.CONNECTING -> "Подключение"
                    VpnState.CONNECTED -> "Отключить"
                },
                color = when (state) {
                    VpnState.DISCONNECTED -> Color(0xFF8A8A8A)
                    VpnState.CONNECTING -> Lime
                    VpnState.CONNECTED -> Color(0xFF3A4A10)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun PowerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
        drawArc(
            color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(w * 0.17f, w * 0.2f), size = Size(w * 0.66f, w * 0.66f), style = stroke
        )
        drawLine(color, Offset(w * 0.5f, w * 0.08f), Offset(w * 0.5f, w * 0.46f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

// ---------- Иконки ----------
@Composable
fun ShieldIcon(connected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * .5f, w * .06f)
            lineTo(w * .9f, w * .2f)
            lineTo(w * .9f, w * .5f)
            cubicTo(w * .9f, w * .75f, w * .7f, w * .9f, w * .5f, w * .96f)
            cubicTo(w * .3f, w * .9f, w * .1f, w * .75f, w * .1f, w * .5f)
            lineTo(w * .1f, w * .2f)
            close()
        }
        if (connected) {
            drawPath(path, Good)
            val tick = Path().apply { moveTo(w * .3f, w * .5f); lineTo(w * .45f, w * .64f); lineTo(w * .72f, w * .36f) }
            drawPath(tick, Color.White, style = Stroke(w * .1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawPath(path, Color(0xFFBDBDBD), style = Stroke(w * .07f, join = StrokeJoin.Round))
            val s = Stroke(w * .07f, cap = StrokeCap.Round)
            drawLine(Color(0xFFBDBDBD), Offset(w * .38f, w * .38f), Offset(w * .62f, w * .62f), s.width, StrokeCap.Round)
            drawLine(Color(0xFFBDBDBD), Offset(w * .62f, w * .38f), Offset(w * .38f, w * .62f), s.width, StrokeCap.Round)
        }
    }
}

@Composable
fun CrownIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * .15f, w * .72f)
            lineTo(w * .1f, w * .32f)
            lineTo(w * .32f, w * .52f)
            lineTo(w * .5f, w * .2f)
            lineTo(w * .68f, w * .52f)
            lineTo(w * .9f, w * .32f)
            lineTo(w * .85f, w * .72f)
            close()
        }
        drawPath(path, Color(0xFF111111))
        drawRect(Color(0xFF111111), Offset(w * .15f, w * .76f), Size(w * .7f, w * .08f))
    }
}

@Composable
fun NavIcon(kind: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val st = Stroke(w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            0 -> { // дом
                val p = Path().apply {
                    moveTo(w * .16f, w * .46f); lineTo(w * .5f, w * .14f); lineTo(w * .84f, w * .46f)
                    lineTo(w * .84f, w * .8f); lineTo(w * .16f, w * .8f); close()
                }
                drawPath(p, color, style = st)
            }
            1 -> { // спидометр
                drawArc(color, 150f, 240f, false, Offset(w * .1f, w * .1f), Size(w * .8f, w * .8f), style = st)
                drawLine(color, Offset(w * .5f, w * .52f), Offset(w * .68f, w * .34f), st.width, StrokeCap.Round)
                drawCircle(color, w * .06f, Offset(w * .5f, w * .52f))
            }
            2 -> { // глобус
                drawCircle(color, w * .38f, center, style = st)
                drawOval(color, Offset(w * .32f, w * .12f), Size(w * .36f, w * .76f), style = st)
                drawLine(color, Offset(w * .12f, w * .5f), Offset(w * .88f, w * .5f), st.width, StrokeCap.Round)
            }
            4 -> { // настройки
                drawCircle(color, w * .25f, center, style = st)
                drawCircle(color, w * .09f, center)
                for (i in 0 until 8) {
                    val angle = i * (Math.PI / 4.0)
                    val x1 = w * .34f * kotlin.math.cos(angle).toFloat()
                    val y1 = w * .34f * kotlin.math.sin(angle).toFloat()
                    val x2 = w * .45f * kotlin.math.cos(angle).toFloat()
                    val y2 = w * .45f * kotlin.math.sin(angle).toFloat()
                    drawLine(color, Offset(center.x + x1, center.y + y1), Offset(center.x + x2, center.y + y2), st.width * 1.2f, StrokeCap.Round)
                }
            }
            else -> { // профиль
                drawCircle(color, w * .17f, Offset(w * .5f, w * .32f), style = st)
                drawArc(color, 180f, 180f, false, Offset(w * .2f, w * .56f), Size(w * .6f, w * .5f), style = st)
            }
        }
    }
}

/** Вертикальные столбики качества связи + значение. */
@Composable
fun PingBars(ping: Int?, modifier: Modifier = Modifier) {
    val level = when {
        ping == null -> 0
        ping < 120 -> 4
        ping < 250 -> 3
        ping < 450 -> 2
        else -> 1
    }
    val col = when (level) { 4, 3 -> Good; 2 -> Warn; 1 -> Bad; else -> Muted }
    Canvas(modifier) {
        val bw = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (0.4f + 0.2f * i)
            drawRoundRect(
                if (i < level) col else Color(0xFF3A3A3A),
                Offset(i * bw * 1.7f, size.height - h), Size(bw, h),
                androidx.compose.ui.geometry.CornerRadius(bw / 2)
            )
        }
    }
}

/** Заливка-«спарклайн» для экрана скорости. */
fun DrawScope.sparkline(values: List<Long>, color: Color) {
    if (values.size < 2) return
    val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
    val step = size.width / (values.size - 1)
    val line = Path()
    val fill = Path()
    values.forEachIndexed { i, v ->
        val x = i * step
        val y = size.height - (v / max) * size.height * 0.9f - 2f
        if (i == 0) { line.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { line.lineTo(x, y); fill.lineTo(x, y) }
    }
    fill.lineTo(size.width, size.height); fill.close()
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .35f), Color.Transparent)))
    drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
