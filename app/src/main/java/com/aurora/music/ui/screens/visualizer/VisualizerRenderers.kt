package com.aurora.music.ui.screens.visualizer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.aurora.music.playback.VisualizerController
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class VizColors(val primary: Color, val secondary: Color)

private fun mix(c: VizColors, t: Float): Color = lerp(c.primary, c.secondary, t.coerceIn(0f, 1f))

fun DrawScope.drawBars(f: VisualizerController.Frame, c: VizColors, mirror: Boolean, peakHold: Boolean) {
    val bands = f.bands
    val n = bands.size
    if (n == 0) return
    val slot = size.width / n
    val bw = slot * 0.66f
    val gap = (slot - bw) / 2f
    val baseY = if (mirror) size.height / 2f else size.height * 0.98f
    val maxH = if (mirror) size.height * 0.46f else size.height * 0.92f
    val radius = CornerRadius(bw / 2.2f, bw / 2.2f)
    for (i in 0 until n) {
        val v = bands[i]
        val h = v * maxH
        if (h < 1f) continue
        val x = i * slot + gap
        val col = mix(c, v)
        val brush = Brush.verticalGradient(
            listOf(col, c.primary.copy(alpha = 0.85f)),
            startY = baseY - h, endY = baseY,
        )
        drawRoundRect(brush, topLeft = Offset(x, baseY - h), size = Size(bw, h), cornerRadius = radius)
        if (mirror) {
            drawRoundRect(
                Brush.verticalGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), startY = baseY, endY = baseY + h),
                topLeft = Offset(x, baseY), size = Size(bw, h), cornerRadius = radius,
            )
        }
        if (peakHold && i < f.peaks.size) {
            val py = baseY - f.peaks[i] * maxH
            drawRoundRect(c.secondary, topLeft = Offset(x, py - 3f), size = Size(bw, 3f), cornerRadius = CornerRadius(2f, 2f))
        }
    }
}

fun DrawScope.drawMirrorBars(f: VisualizerController.Frame, c: VizColors, peakHold: Boolean) {
    val bands = f.bands
    val n = bands.size
    if (n == 0) return
    val half = n
    val slot = size.width / (half * 2f)
    val bw = slot * 0.7f
    val cx = size.width / 2f
    val baseY = size.height * 0.98f
    val maxH = size.height * 0.92f
    val radius = CornerRadius(bw / 2.2f, bw / 2.2f)
    for (i in 0 until n) {
        val v = bands[i]
        val h = v * maxH
        if (h < 1f) continue
        val col = mix(c, v)
        val brush = Brush.verticalGradient(listOf(col, c.primary.copy(alpha = 0.85f)), startY = baseY - h, endY = baseY)
        val xr = cx + i * slot
        val xl = cx - (i + 1) * slot
        drawRoundRect(brush, topLeft = Offset(xr, baseY - h), size = Size(bw, h), cornerRadius = radius)
        drawRoundRect(brush, topLeft = Offset(xl, baseY - h), size = Size(bw, h), cornerRadius = radius)
        if (peakHold && i < f.peaks.size) {
            val py = baseY - f.peaks[i] * maxH
            drawRoundRect(c.secondary, topLeft = Offset(xr, py - 3f), size = Size(bw, 3f), cornerRadius = CornerRadius(2f, 2f))
            drawRoundRect(c.secondary, topLeft = Offset(xl, py - 3f), size = Size(bw, 3f), cornerRadius = CornerRadius(2f, 2f))
        }
    }
}

fun DrawScope.drawWaveform(f: VisualizerController.Frame, c: VizColors) {
    val w = f.wave
    val n = w.size
    if (n < 2) return
    val cy = size.height / 2f
    val amp = size.height * 0.42f
    val dx = size.width / (n - 1)
    val path = Path()
    for (i in 0 until n) {
        val x = i * dx
        val y = cy - w[i] * amp
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, c.primary.copy(alpha = 0.25f), style = Stroke(width = 10f, cap = StrokeCap.Round))
    drawPath(path, Brush.horizontalGradient(listOf(c.primary, c.secondary)), style = Stroke(width = 3.5f, cap = StrokeCap.Round))
}

fun DrawScope.drawFilledWave(f: VisualizerController.Frame, c: VizColors) {
    val w = f.wave
    val n = w.size
    if (n < 2) return
    val cy = size.height / 2f
    val amp = size.height * 0.42f
    val dx = size.width / (n - 1)
    val top = Path().apply {
        moveTo(0f, cy)
        for (i in 0 until n) lineTo(i * dx, cy - kotlin.math.abs(w[i]) * amp)
        lineTo(size.width, cy); close()
    }
    val bottom = Path().apply {
        moveTo(0f, cy)
        for (i in 0 until n) lineTo(i * dx, cy + kotlin.math.abs(w[i]) * amp)
        lineTo(size.width, cy); close()
    }
    val brush = Brush.verticalGradient(listOf(c.secondary.copy(alpha = 0.85f), c.primary.copy(alpha = 0.3f)), startY = cy - amp, endY = cy + amp)
    drawPath(top, brush, style = Fill)
    drawPath(bottom, brush, style = Fill)
}

fun DrawScope.drawRadialBars(f: VisualizerController.Frame, c: VizColors, rot: Float) {
    val bands = f.bands
    val n = bands.size
    if (n == 0) return
    val cx = size.width / 2f
    val cy = size.height / 2f
    val inner = min(size.width, size.height) * 0.18f
    val maxLen = min(size.width, size.height) * 0.30f
    val sw = (2 * Math.PI * inner / n / 1.6f).toFloat().coerceAtLeast(2f)
    for (i in 0 until n) {
        val v = bands[i]
        val a = (i.toFloat() / n) * 2f * Math.PI.toFloat() + rot
        val len = inner + v * maxLen
        val sx = cx + cos(a) * inner
        val sy = cy + sin(a) * inner
        val ex = cx + cos(a) * len
        val ey = cy + sin(a) * len
        drawLine(mix(c, v), Offset(sx, sy), Offset(ex, ey), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

fun DrawScope.drawRadialWave(f: VisualizerController.Frame, c: VizColors, rot: Float) {
    val w = f.wave
    val n = w.size
    if (n < 3) return
    val cx = size.width / 2f
    val cy = size.height / 2f
    val base = min(size.width, size.height) * 0.26f
    val amp = min(size.width, size.height) * 0.12f
    val path = Path()
    for (i in 0..n) {
        val idx = i % n
        val a = (idx.toFloat() / n) * 2f * Math.PI.toFloat() + rot
        val r = base + w[idx] * amp
        val x = cx + cos(a) * r
        val y = cy + sin(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, c.primary.copy(alpha = 0.25f), style = Stroke(width = 9f, cap = StrokeCap.Round))
    drawPath(path, Brush.sweepGradient(listOf(c.primary, c.secondary, c.primary)), style = Stroke(width = 3f, cap = StrokeCap.Round))
}

fun DrawScope.drawFluid(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val base = min(size.width, size.height) * (0.16f + 0.10f * f.bass)
    val lobes = 7
    val path = Path()
    val steps = 90
    val bands = f.bands
    for (i in 0..steps) {
        val a = (i.toFloat() / steps) * 2f * Math.PI.toFloat()
        val bandV = if (bands.isNotEmpty()) bands[(i * bands.size / steps).coerceIn(0, bands.size - 1)] else 0f
        val wobble = sin(a * lobes + time * 1.7f) * 0.12f + bandV * 0.5f
        val r = base * (1f + wobble)
        val x = cx + cos(a) * r
        val y = cy + sin(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(
        path,
        Brush.radialGradient(
            listOf(c.secondary.copy(alpha = 0.9f), c.primary.copy(alpha = 0.5f), c.primary.copy(alpha = 0.0f)),
            center = Offset(cx, cy), radius = base * 2.2f,
        ),
        style = Fill,
    )
    drawPath(path, c.secondary.copy(alpha = 0.85f), style = Stroke(width = 2.5f))
}

class ParticleField(private val max: Int) {
    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var size: Float, var tint: Float)
    private val ps = ArrayList<P>(max)
    private var seed = 0x9E3779B1.toInt()
    private fun rnd(): Float { seed = seed * 1664525 + 1013904223; return ((seed ushr 8) and 0xFFFFFF) / 16777216f }

    fun update(bass: Float, level: Float, w: Float, h: Float) {
        val spawn = (level * 6f + bass * 8f).toInt()
        repeat(spawn) {
            if (ps.size < max) {
                val ang = rnd() * 2f * Math.PI.toFloat()
                val spd = 1.5f + level * 9f + rnd() * 3f
                ps.add(P(w / 2f, h / 2f, cos(ang) * spd, sin(ang) * spd, 1f, 2f + rnd() * 4f + bass * 6f, rnd()))
            }
        }
        val it = ps.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx; p.y += p.vy
            p.vy += 0.04f
            p.life -= 0.012f
            if (p.life <= 0f || p.x < -20 || p.x > w + 20 || p.y < -20 || p.y > h + 20) it.remove()
        }
    }

    fun draw(d: DrawScope, c: VizColors) {
        for (p in ps) {
            val col = lerp(c.primary, c.secondary, p.tint).copy(alpha = (p.life * 0.9f).coerceIn(0f, 1f))
            d.drawCircle(col, radius = p.size * p.life, center = Offset(p.x, p.y))
        }
    }
}

fun DrawScope.drawSmoothCurve(f: VisualizerController.Frame, c: VizColors) {
    val bands = f.bands
    val n = bands.size
    if (n < 2) return
    val baseY = size.height * 0.96f
    val maxH = size.height * 0.88f
    val dx = size.width / (n - 1)
    val line = Path()
    line.moveTo(0f, baseY - bands[0] * maxH)
    for (i in 1 until n) {
        val x0 = (i - 1) * dx; val x1 = i * dx
        val y0 = baseY - bands[i - 1] * maxH; val y1 = baseY - bands[i] * maxH
        val mx = (x0 + x1) / 2f
        line.cubicTo(mx, y0, mx, y1, x1, y1)
    }
    val fill = Path().apply { addPath(line); lineTo(size.width, baseY); lineTo(0f, baseY); close() }
    drawPath(fill, Brush.verticalGradient(listOf(c.secondary.copy(alpha = 0.5f), c.primary.copy(alpha = 0.04f)), startY = baseY - maxH, endY = baseY))
    drawPath(line, Brush.horizontalGradient(listOf(c.primary, c.secondary)), style = Stroke(width = 3f, cap = StrokeCap.Round))
}

fun DrawScope.drawDotGrid(f: VisualizerController.Frame, c: VizColors) {
    val n = f.bands.size
    if (n == 0) return
    val cols = min(28, n)
    val rows = 12
    val cw = size.width / cols
    val ch = size.height / rows
    val r = (min(cw, ch) / 2f) * 0.42f
    for (col in 0 until cols) {
        val v = f.bands[col * n / cols]
        val lit = v * rows
        for (row in 0 until rows) {
            val cx = col * cw + cw / 2f
            val cy = size.height - (row * ch + ch / 2f)
            val on = row < lit
            val tint = lerp(c.primary, c.secondary, row.toFloat() / rows)
            drawCircle(tint.copy(alpha = if (on) 1f else 0.10f), radius = if (on) r else r * 0.7f, center = Offset(cx, cy))
        }
    }
}

fun DrawScope.drawRings(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val maxR = min(size.width, size.height) * 0.55f
    val rings = 6
    for (i in 0 until rings) {
        val phase = ((time * 0.25f + i.toFloat() / rings) % 1f + 1f) % 1f
        val r = phase * maxR
        val alpha = ((1f - phase) * (0.22f + 0.6f * f.bass)).coerceIn(0f, 1f)
        drawCircle(lerp(c.primary, c.secondary, phase).copy(alpha = alpha), radius = r, center = Offset(cx, cy), style = Stroke(width = 2f + f.bass * 6f))
    }
    drawCircle(c.secondary.copy(alpha = 0.85f), radius = 6f + f.rms * 44f, center = Offset(cx, cy))
}

fun DrawScope.drawOrb(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val base = min(size.width, size.height) * 0.16f
    val r = base * (1f + 0.25f * f.rms)
    drawCircle(Brush.radialGradient(listOf(c.secondary.copy(alpha = 0.5f), c.primary.copy(alpha = 0f)), center = Offset(cx, cy), radius = r * 2.4f), radius = r * 2.4f, center = Offset(cx, cy))
    drawCircle(Brush.radialGradient(listOf(c.secondary, c.primary), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
    val n = f.bands.size
    for (i in 0 until n) {
        val a = i.toFloat() / n * 2f * Math.PI.toFloat() + time * 0.3f
        val v = f.bands[i]
        val r0 = r * 1.3f; val r1 = r0 + v * base * 1.5f
        drawLine(lerp(c.primary, c.secondary, v).copy(alpha = 0.85f), Offset(cx + cos(a) * r0, cy + sin(a) * r0), Offset(cx + cos(a) * r1, cy + sin(a) * r1), strokeWidth = 3f, cap = StrokeCap.Round)
    }
}

fun DrawScope.drawLadder(f: VisualizerController.Frame, c: VizColors) {
    val bands = f.bands
    val n = bands.size
    if (n == 0) return
    val slot = size.width / n
    val bw = slot * 0.62f
    val gap = (slot - bw) / 2f
    val segs = 16
    val segGap = 3f
    val segH = (size.height * 0.92f) / segs - segGap
    if (segH <= 0f) return
    for (i in 0 until n) {
        val lit = bands[i] * segs
        val x = i * slot + gap
        for (s in 0 until segs) {
            val on = s < lit
            val y = size.height * 0.96f - (s + 1) * (segH + segGap)
            val tint = lerp(c.primary, c.secondary, s.toFloat() / segs)
            drawRoundRect(if (on) tint else tint.copy(alpha = 0.10f), topLeft = Offset(x, y), size = Size(bw, segH), cornerRadius = CornerRadius(3f, 3f))
        }
    }
}

fun DrawScope.drawHorizon(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val horizonY = size.height * 0.46f
    val cx = size.width / 2f
    val lines = 11
    for (i in 1..lines) {
        val t = ((i.toFloat() / lines) + time * 0.08f) % 1f
        val y = horizonY + t * t * (size.height - horizonY)
        drawLine(c.primary.copy(alpha = 0.18f + 0.45f * t), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
    }
    val vlines = 12
    for (i in -vlines..vlines) {
        val xBottom = cx + i * (size.width / vlines.toFloat())
        drawLine(c.primary.copy(alpha = 0.18f), Offset(cx, horizonY), Offset(xBottom, size.height), strokeWidth = 1.1f)
    }
    val n = f.bands.size
    if (n > 1) {
        val dx = size.width / (n - 1)
        val ridge = Path().apply {
            moveTo(0f, horizonY)
            for (i in 0 until n) lineTo(i * dx, horizonY - f.bands[i] * horizonY * 0.85f)
            lineTo(size.width, horizonY); close()
        }
        drawPath(ridge, Brush.verticalGradient(listOf(c.secondary.copy(alpha = 0.6f), c.primary.copy(alpha = 0.04f)), startY = 0f, endY = horizonY))
    }
    drawLine(c.secondary, Offset(0f, horizonY), Offset(size.width, horizonY), strokeWidth = 2f)
}

fun DrawScope.drawConstellation(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val n = min(48, f.bands.size)
    if (n < 2) return
    val baseR = min(size.width, size.height) * 0.34f
    val pts = ArrayList<Offset>(n)
    for (i in 0 until n) {
        val a = i.toFloat() / n * 2f * Math.PI.toFloat() + time * 0.15f
        val v = f.bands[i * f.bands.size / n]
        val r = baseR * (0.5f + 0.8f * v)
        pts.add(Offset(cx + cos(a) * r, cy + sin(a) * r))
    }
    for (i in 0 until n) drawLine(c.primary.copy(alpha = 0.4f), pts[i], pts[(i + 1) % n], strokeWidth = 1.2f)
    for (i in 0 until n step 2) drawLine(c.primary.copy(alpha = 0.13f), pts[i], Offset(cx, cy), strokeWidth = 0.8f)
    for (p in pts) drawCircle(c.secondary, radius = 2.6f, center = p)
}

fun DrawScope.drawPeakDots(f: VisualizerController.Frame, c: VizColors) {
    val src = if (f.peaks.any { it > 0f }) f.peaks else f.bands
    val n = src.size
    if (n == 0) return
    val slot = size.width / n
    val baseY = size.height * 0.96f
    val maxH = size.height * 0.9f
    for (i in 0 until n) {
        val v = src[i]
        val x = i * slot + slot / 2f
        val y = baseY - v * maxH
        drawCircle(lerp(c.primary, c.secondary, v), radius = (slot * 0.28f).coerceIn(2f, 9f), center = Offset(x, y))
    }
}

fun DrawScope.drawSpectrumLine(f: VisualizerController.Frame, c: VizColors) {
    val bands = f.bands
    val n = bands.size
    if (n < 2) return
    val cy = size.height / 2f
    val amp = size.height * 0.42f
    val dx = size.width / (n - 1)
    val up = Path().apply { moveTo(0f, cy); for (i in 0 until n) lineTo(i * dx, cy - bands[i] * amp) }
    val dn = Path().apply { moveTo(0f, cy); for (i in 0 until n) lineTo(i * dx, cy + bands[i] * amp) }
    val brush = Brush.horizontalGradient(listOf(c.primary, c.secondary))
    drawPath(up, c.primary.copy(alpha = 0.2f), style = Stroke(8f, cap = StrokeCap.Round))
    drawPath(up, brush, style = Stroke(2.5f, cap = StrokeCap.Round))
    drawPath(dn, c.primary.copy(alpha = 0.2f), style = Stroke(8f, cap = StrokeCap.Round))
    drawPath(dn, brush, style = Stroke(2.5f, cap = StrokeCap.Round))
}

fun DrawScope.drawAurora(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val bands = f.bands
    val n = bands.size
    fun energy(lo: Float, hi: Float): Float {
        if (n == 0) return 0f
        var s = 0f; var cnt = 0
        val a = (lo * n).toInt(); val b = (hi * n).toInt()
        for (i in a until b) { s += bands[i]; cnt++ }
        return if (cnt > 0) s / cnt else 0f
    }
    val layers = 4
    for (l in 0 until layers) {
        val e = energy(l / layers.toFloat(), (l + 1) / layers.toFloat())
        val baseY = size.height * (0.32f + 0.13f * l)
        val amp = size.height * (0.05f + 0.11f * e)
        val freq = (1.5f + l) * (2f * Math.PI.toFloat()) / size.width
        val speed = time * (0.4f + 0.2f * l)
        val path = Path().apply {
            moveTo(0f, size.height)
            val steps = 64
            for (s in 0..steps) {
                val x = s.toFloat() / steps * size.width
                val y = baseY + sin(x * freq + speed) * amp + sin(x * freq * 0.5f - speed * 0.7f) * amp * 0.5f
                lineTo(x, y)
            }
            lineTo(size.width, size.height); close()
        }
        val col = lerp(c.primary, c.secondary, if (layers > 1) l / (layers - 1f) else 0f)
        drawPath(path, Brush.verticalGradient(listOf(col.copy(alpha = 0.28f + 0.3f * e), Color.Transparent), startY = baseY - amp, endY = size.height))
    }
}

class SpectralRiver {
    private val COLS = 96
    private val ROWS = 40
    private val grid = FloatArray(COLS * ROWS)   // ring buffer flat [col*ROWS + row]
    private val rowPeak = FloatArray(ROWS)
    private var head = 0
    private var acc = 0f
    private var lastTime = -1f
    private val WRITE_DT = 1f / 45f               // fixed write cadence fps-independent
    private val path = Path()
    private var w = 0f
    private var h = 0f
    private var bass = 0f
    private var level = 0f

    fun update(f: VisualizerController.Frame, time: Float, width: Float, height: Float) {
        w = width; h = height; bass = f.bass; level = f.level
        if (lastTime < 0f) lastTime = time
        var dt = time - lastTime; lastTime = time
        if (dt < 0f) dt = 0f
        if (dt > 0.1f) dt = 0.1f
        acc += dt
        val bands = f.bands
        val bn = bands.size
        var guard = 0
        while (acc >= WRITE_DT && guard < 4) {
            acc -= WRITE_DT; guard++
            head = (head + 1) % COLS
            val base = head * ROWS
            for (r in 0 until ROWS) {
                val v = if (bn > 0) bands[(r * bn / ROWS).coerceIn(0, bn - 1)] else 0f
                grid[base + r] = v
                if (v > rowPeak[r]) rowPeak[r] = v else rowPeak[r] *= 0.97f
            }
        }
    }

    fun draw(d: DrawScope, c: VizColors) {
        if (w <= 0f || h <= 0f) return
        val rowGap = h / ROWS
        d.withTransform({ translate(0f, -bass * h * 0.04f) }) {
            for (r in 0 until ROWS) {
                val peak = rowPeak[r]
                if (peak < 0.01f) continue
                val baseY = h * (1f - r.toFloat() / ROWS)
                path.reset()
                var prevY = 0f
                for (k in 0 until COLS) {
                    val idx = ((head - (COLS - 1 - k)) % COLS + COLS) % COLS
                    val v = grid[idx * ROWS + r]
                    val x = w * (k.toFloat() / (COLS - 1))
                    val y = baseY - v * rowGap * 1.6f
                    if (k == 0) {
                        path.moveTo(x, y); prevY = y
                    } else {
                        val px = w * ((k - 1).toFloat() / (COLS - 1))
                        val mx = (px + x) / 2f
                        path.cubicTo(mx, prevY, mx, y, x, y)
                        prevY = y
                    }
                }
                val col = lerp(c.primary, c.secondary, r.toFloat() / ROWS)
                val a = (0.10f + 0.75f * peak).coerceIn(0f, 0.9f)
                val sw = 1f + 2.2f * peak
                d.drawPath(path, col.copy(alpha = a), style = Stroke(width = sw, cap = StrokeCap.Round))
            }
        }
        d.drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, c.secondary.copy(alpha = 0.10f + 0.30f * level))),
            topLeft = Offset(w * 0.86f, 0f), size = Size(w * 0.14f, h),
        )
    }
}

class SpectralTerrain {
    private val ROWS = 22
    private val PTS = 56
    private val rows = FloatArray(ROWS * PTS)
    private var newest = 0
    private var acc = 0f; private var lastTime = -1f
    private val PUSH_DT = 1f / 30f
    private var frac = 0f
    private val stroke = Path(); private val fill = Path()
    private var w = 0f; private var h = 0f; private var bass = 0f

    fun update(f: VisualizerController.Frame, time: Float, width: Float, height: Float) {
        w = width; h = height; bass = f.bass
        if (lastTime < 0f) lastTime = time
        var dt = time - lastTime; lastTime = time
        if (dt < 0f) dt = 0f; if (dt > 0.1f) dt = 0.1f
        acc += dt
        var guard = 0
        while (acc >= PUSH_DT && guard < 4) {
            acc -= PUSH_DT; guard++
            newest = (newest + 1) % ROWS
            val base = newest * PTS
            val bands = f.bands; val bn = bands.size
            for (p in 0 until PTS) rows[base + p] = if (bn > 0) bands[(p * bn / PTS).coerceIn(0, bn - 1)] else 0f
        }
        frac = (acc / PUSH_DT).coerceIn(0f, 1f)
    }

    fun draw(d: DrawScope, c: VizColors) {
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val horizonY = h * 0.30f
        val nearScale = 1.0f; val farScale = 0.18f
        val amp = h * 0.34f
        for (sdRaw in (ROWS - 1) downTo 0) {
            val sd = sdRaw - frac
            if (sd < 0f) continue
            val dN = sd / (ROWS - 1)
            val s = nearScale + (farScale - nearScale) * dN
            val baseY = horizonY + (1f - dN) * (h * 0.62f)
            val slot = ((newest - sdRaw) % ROWS + ROWS) % ROWS
            val rb = slot * PTS
            val halfW = 0.5f * w * s
            val span = w * s

            stroke.reset(); fill.reset()
            fill.moveTo(cx - halfW, baseY)
            var prevX = 0f; var prevY = 0f
            for (p in 0 until PTS) {
                val x = cx + (p.toFloat() / (PTS - 1) - 0.5f) * span
                val y = baseY - rows[rb + p] * amp * s
                if (p == 0) stroke.moveTo(x, y)
                else {
                    val mx = (prevX + x) / 2f
                    stroke.cubicTo(mx, prevY, mx, y, x, y)
                }
                fill.lineTo(x, y)
                prevX = x; prevY = y
            }
            fill.lineTo(cx + halfW, baseY); fill.close()

            val rowColor = lerp(c.secondary, c.primary, dN)
            val a = 0.9f * (1f - dN * 0.6f)
            val occ = lerp(Color.Black, rowColor, 0.16f + 0.10f * (1f - dN)).copy(alpha = 1f)
            d.drawPath(fill, occ, style = Fill)
            d.drawPath(
                fill,
                Brush.verticalGradient(
                    listOf(rowColor.copy(alpha = 0.22f), Color.Transparent),
                    startY = baseY - amp * s, endY = baseY,
                ),
                style = Fill,
            )
            val sw = 1.4f + (1f - dN) * (1.6f + bass * 4f)
            d.drawPath(stroke, rowColor.copy(alpha = a), style = Stroke(width = sw, cap = StrokeCap.Round))
        }
    }
}

class CurlFlowField(requested: Int) {
    private val N: Int = requested.coerceIn(1, MAX_MOTES)
    private val xs = FloatArray(N); private val ys = FloatArray(N)
    private val pxs = FloatArray(N); private val pys = FloatArray(N)
    private val streakPts = ArrayList<Offset>(N * 2)
    private val headPts = ArrayList<Offset>(N)
    private var seed = 0x2545F491.toInt()
    private fun rnd(): Float { seed = seed * 1664525 + 1013904223; return ((seed ushr 8) and 0xFFFFFF) / 16777216f }
    private var lastTime = -1f
    private var inited = false
    private var w = 0f; private var h = 0f

    private fun spawn(i: Int, w: Float, h: Float) { xs[i] = rnd() * w; ys[i] = rnd() * h; pxs[i] = xs[i]; pys[i] = ys[i] }

    fun update(f: VisualizerController.Frame, time: Float, width: Float, height: Float) {
        w = width; h = height
        if (w <= 0f || h <= 0f) return
        if (!inited) { for (i in 0 until N) spawn(i, w, h); inited = true; lastTime = time }
        var dt = time - lastTime; lastTime = time
        if (dt < 0f) dt = 0f
        if (dt > 0.05f) dt = 0.05f
        val step = dt * 60f
        val bands = f.bands; val bn = bands.size
        var num = 0f; var den = 1e-4f
        for (i in 0 until bn) { num += i * bands[i]; den += bands[i] }
        val centroid = if (bn > 1) (num / den) / (bn - 1) else 0f
        val k = 0.006f * (1f + 1.4f * centroid)
        val flowSpeed = (0.6f + 3.0f * f.rms) * 2.4f
        val t = time
        val sx = 0.30f; val sy = 0.20f; val sz = 0.17f
        val gust = f.bass * 6f
        for (i in 0 until N) {
            val x = xs[i]; val y = ys[i]
            val s1 = sin(x * k + t * sx); val c1 = cos(x * k + t * sx)
            val s2 = cos(y * k - t * sy); val s2d = -sin(y * k - t * sy)
            val c3 = cos((x + y) * k * 1.7f + t * sz)
            var vx = (s1 * s2d * k + 0.5f * c3 * k * 1.7f)
            var vy = -(c1 * k * s2 + 0.5f * c3 * k * 1.7f)
            val mag = sqrt(vx * vx + vy * vy) + 1e-5f
            vx = vx / mag * flowSpeed; vy = vy / mag * flowSpeed
            vx += sin(t * 2f + i) * gust * 0.15f; vy += cos(t * 2f + i) * gust * 0.15f
            pxs[i] = x; pys[i] = y
            xs[i] = x + vx * step; ys[i] = y + vy * step
            if (xs[i] < 0f) { xs[i] += w; pxs[i] = xs[i] }
            if (xs[i] > w) { xs[i] -= w; pxs[i] = xs[i] }
            if (ys[i] < 0f) { ys[i] += h; pys[i] = ys[i] }
            if (ys[i] > h) { ys[i] -= h; pys[i] = ys[i] }
        }
    }

    fun draw(d: DrawScope, c: VizColors) {
        if (!inited) return
        streakPts.clear(); headPts.clear()
        for (i in 0 until N) {
            streakPts.add(Offset(pxs[i], pys[i])); streakPts.add(Offset(xs[i], ys[i]))
            headPts.add(Offset(xs[i], ys[i]))
        }
        d.drawPoints(streakPts, PointMode.Lines, c.primary.copy(alpha = 0.18f), strokeWidth = 4f, cap = StrokeCap.Round)
        d.drawPoints(streakPts, PointMode.Lines, c.secondary.copy(alpha = 0.55f), strokeWidth = 1.4f, cap = StrokeCap.Round)
        d.drawPoints(headPts, PointMode.Points, c.secondary.copy(alpha = 0.9f), strokeWidth = 2.0f, cap = StrokeCap.Round)
    }

    companion object { private const val MAX_MOTES = 1800 }
}

class StrangeAttractor(n: Int) {
    private val N: Int = n.coerceIn(500, 2500)
    private val xs = FloatArray(N); private val ys = FloatArray(N)
    private val pxs = FloatArray(N); private val pys = FloatArray(N)
    private val ptsA = ArrayList<Offset>(N)
    private val ptsB = ArrayList<Offset>(N)
    private val trail = ArrayList<Offset>(N)
    private var a = -1.6f; private var b = 1.3f; private var cc = 0.8f; private var dd = 1.4f
    private var time = 0f
    private var w = 0f; private var h = 0f; private var hasPrev = false

    private fun mean(bands: FloatArray, lo: Int, hi: Int): Float {
        if (bands.isEmpty()) return 0f
        val a0 = lo.coerceIn(0, bands.size - 1); val b0 = hi.coerceIn(a0 + 1, bands.size)
        var s = 0f; for (i in a0 until b0) s += bands[i]; return s / (b0 - a0)
    }

    fun update(f: VisualizerController.Frame, t: Float, width: Float, height: Float) {
        time = t; w = width; h = height
        val bands = f.bands; val n = bands.size
        val mid = mean(bands, n / 4, n / 2)
        val treble = mean(bands, 3 * n / 4, n)
        val tA = -1.6f - 0.9f * f.bass
        val tB = 1.3f + 0.7f * mid
        val tC = 0.8f + 0.7f * treble
        val tD = 1.4f + 0.5f * f.rms
        a += (tA - a) * 0.05f; b += (tB - b) * 0.05f; cc += (tC - cc) * 0.05f; dd += (tD - dd) * 0.05f
        if (hasPrev) { System.arraycopy(xs, 0, pxs, 0, N); System.arraycopy(ys, 0, pys, 0, N) }
        val scale = min(w, h) * 0.34f
        val cx = w / 2f; val cy = h / 2f
        var x = 0.1f + f.bass * 0.03f; var y = 0.1f + f.bass * 0.03f
        for (i in 0 until N) {
            val nx = sin(a * y) + cc * cos(a * x)
            val ny = sin(b * x) + dd * cos(b * y)
            x = nx; y = ny
            if (!x.isFinite() || !y.isFinite()) { x = 0.1f; y = 0.1f }
            xs[i] = cx + x * scale; ys[i] = cy + y * scale
        }
        if (!hasPrev) { System.arraycopy(xs, 0, pxs, 0, N); System.arraycopy(ys, 0, pys, 0, N); hasPrev = true }
    }

    fun draw(d: DrawScope, c: VizColors) {
        ptsA.clear(); ptsB.clear(); trail.clear()
        val half = N / 2
        for (i in 0 until N) {
            if (i < half) ptsA.add(Offset(xs[i], ys[i])) else ptsB.add(Offset(xs[i], ys[i]))
            trail.add(Offset(pxs[i], pys[i]))
        }
        d.withTransform({ rotate(degrees = time * 3.5f, pivot = Offset(w / 2f, h / 2f)) }) {
            d.drawPoints(trail, PointMode.Points, c.primary.copy(alpha = 0.18f), strokeWidth = 2.2f, cap = StrokeCap.Round)
            d.drawPoints(ptsA, PointMode.Points, c.primary.copy(alpha = 0.7f), strokeWidth = 2.2f, cap = StrokeCap.Round)
            d.drawPoints(ptsB, PointMode.Points, c.secondary.copy(alpha = 0.7f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
    }
}

class Cymatic(private val N: Int) {
    private val gx = FloatArray(N); private val gy = FloatArray(N)
    private val crest = ArrayList<Offset>(N)
    private var m = 4f; private var n = 3f
    private var seed = 0x7F4A7C15.toInt()
    private fun rnd(): Float { seed = seed * 1664525 + 1013904223; return ((seed ushr 8) and 0xFFFFFF) / 16777216f }
    private var inited = false
    private var time = 0f; private var w = 0f; private var h = 0f
    private var alphaLevel = 0f; private var alphaRms = 0f
    private val PIf = PI.toFloat()

    private fun wrap01(v: Float): Float {
        var x = v - floor(v)
        if (x < 0f) x += 1f
        if (x >= 1f) x -= 1f
        return x
    }

    fun update(f: VisualizerController.Frame, t: Float, width: Float, height: Float) {
        time = t; w = width; h = height; alphaLevel = f.level; alphaRms = f.rms
        if (!inited) { for (i in 0 until N) { gx[i] = rnd(); gy[i] = rnd() }; inited = true }
        val bands = f.bands; val bn = bands.size
        var num = 0f; var den = 1e-4f
        for (i in 0 until bn) { num += i * bands[i]; den += bands[i] }
        val centroid = if (bn > 1) (num / den) / (bn - 1) else 0f
        var high = 0f; if (bn > 0) { var s = 0f; val a0 = bn * 3 / 4; for (i in a0 until bn) s += bands[i]; high = s / (bn - a0).coerceAtLeast(1) }
        val targetM = 2f + Math.round(6f * centroid).toFloat()
        val targetN = 2f + Math.round(6f * (1f - centroid) * high).toFloat()
        m += (targetM - m) * 0.04f; n += (targetN - n) * 0.04f
        val stepScale = 0.018f * (0.5f + 2.0f * f.rms)
        val amp = 0.4f + 2.5f * f.bass
        val jit = 0.004f * (0.5f + f.level)
        val maxStep = 0.08f
        val mPI = m * PIf; val nPI = n * PIf
        for (i in 0 until N) {
            val x = gx[i]; val y = gy[i]
            val mpx = mPI * x; val npx = nPI * x
            val mpy = mPI * y; val npy = nPI * y
            val psi = cos(mpx) * cos(npy) - cos(npx) * cos(mpy)
            val dpx = -mPI * sin(mpx) * cos(npy) + nPI * sin(npx) * cos(mpy)
            val dpy = -nPI * cos(mpx) * sin(npy) + mPI * cos(npx) * sin(mpy)
            val sgn = if (psi >= 0f) 1f else -1f
            var sx = -sgn * dpx * stepScale * amp
            var sy = -sgn * dpy * stepScale * amp
            if (sx > maxStep) sx = maxStep else if (sx < -maxStep) sx = -maxStep
            if (sy > maxStep) sy = maxStep else if (sy < -maxStep) sy = -maxStep
            gx[i] = wrap01(x + sx + (rnd() - 0.5f) * jit)
            gy[i] = wrap01(y + sy + (rnd() - 0.5f) * jit)
        }
    }

    fun draw(d: DrawScope, c: VizColors) {
        val pad = min(w, h) * 0.06f
        val sw = min(w, h) - 2f * pad
        val ox = (w - sw) / 2f; val oy = (h - sw) / 2f
        if (crest.size != N) { crest.clear(); for (i in 0 until N) crest.add(Offset(ox + gx[i] * sw, oy + gy[i] * sw)) }
        else for (i in 0 until N) crest[i] = Offset(ox + gx[i] * sw, oy + gy[i] * sw)
        d.withTransform({ rotate(degrees = time * 4f, pivot = Offset(w / 2f, h / 2f)) }) {
            val col = lerp(c.primary, c.secondary, alphaLevel)
            drawPoints(crest, PointMode.Points, col.copy(alpha = 0.4f + 0.5f * alphaRms), strokeWidth = 2.4f, cap = StrokeCap.Round)
        }
    }
}

fun DrawScope.drawSuperformulaBloom(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val baseR = min(size.width, size.height) * 0.42f
    val L = 5
    val THETA = 72
    val bands = f.bands; val bn = bands.size
    val PIf = PI.toFloat()
    fun energy(l: Int): Float {
        if (bn == 0) return 0f
        val a0 = (l * bn / L); val b0 = ((l + 1) * bn / L).coerceAtLeast(a0 + 1)
        var s = 0f; for (i in a0 until b0.coerceAtMost(bn)) s += bands[i]; return s / (b0 - a0).coerceAtLeast(1)
    }
    var highE = 0f; if (bn > 0) { var s = 0f; val a0 = bn * 3 / 4; for (i in a0 until bn) s += bands[i]; highE = s / (bn - a0).coerceAtLeast(1) }
    val treble = highE
    val coreR = 8f + f.rms * 40f
    for (l in 0 until L) {
        val e = energy(l)
        val m = (Math.round(3f + 9f * e)).toFloat()
        val n1 = 0.3f + 2.0f * (1f - highE)
        val n23 = 0.4f + 6.0f * treble
        val petalScale = baseR * (0.35f + 0.16f * l) * (1f + 0.25f * f.bass)
        val rot = (if (l % 2 == 0) 1f else -1f) * time * (0.15f + 0.05f * l)
        val path = Path()
        for (s in 0..THETA) {
            val th = s.toFloat() / THETA * 2f * PIf
            val ang = th + rot
            val t1 = abs(cos(m * th / 4f)).pow(n23)
            val t2 = abs(sin(m * th / 4f)).pow(n23)
            val denom = (t1 + t2).coerceAtLeast(1e-4f)
            val r = denom.pow(-1f / n1.coerceAtLeast(0.05f)).coerceIn(0f, 3f)
            val rr = petalScale * r
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val col = lerp(c.primary, c.secondary, if (L > 1) l / (L - 1f) else 0f)
        val fillAlpha = 0.10f + 0.18f * e
        drawPath(path, Brush.radialGradient(listOf(c.secondary.copy(alpha = fillAlpha), Color.Transparent), center = Offset(cx, cy), radius = petalScale * 1.2f), style = Fill)
        drawPath(path, col.copy(alpha = (0.9f - 0.12f * l).coerceIn(0.2f, 0.9f)), style = Stroke(width = 1.5f + 1.5f * e, cap = StrokeCap.Round))
    }
    drawCircle(Brush.radialGradient(listOf(c.secondary, c.primary.copy(alpha = 0f)), center = Offset(cx, cy), radius = coreR * 2f), radius = coreR * 2f, center = Offset(cx, cy))
}

fun DrawScope.drawWormhole(f: VisualizerController.Frame, c: VizColors, time: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val minDim = min(size.width, size.height)
    val focal = minDim * 0.9f
    val RINGS = 18; val SIDES = 14
    val bands = f.bands; val bn = bands.size
    val PIf = PI.toFloat()
    val speed = 0.6f + f.bass * 1.4f
    val frac = (time * speed) % 1f
    val zNear = 0.20f; val zStep = 0.42f
    val baseR = 0.5f * (1f + 0.25f * f.bass)
    val cyBob = cy + sin(time) * 8f
    val zFar = zNear + (RINGS + 1) * zStep
    for (i in RINGS - 1 downTo 0) {
        val z = zNear + (i + frac) * zStep
        if (z <= 0.02f) continue
        val rProj = focal * baseR / z
        val ringTwist = z * 0.35f + time * 0.2f
        val depthT = i.toFloat() / RINGS
        val col = lerp(c.primary, c.secondary, depthT)
        val alpha = (1f - z / zFar).coerceIn(0.05f, 1f)
        val sw = (3f / z * minDim * 0.02f).coerceIn(0.6f, 7f)
        val path = Path()
        for (j in 0 until SIDES) {
            val a = j.toFloat() / SIDES * 2f * PIf + ringTwist
            val bi = (j * bn / SIDES).coerceIn(0, (bn - 1).coerceAtLeast(0))
            val bandV = if (bn > 0) bands[bi] else 0f
            val vr = rProj * (1f + 0.45f * bandV)
            val x = cx + cos(a) * vr
            val y = cyBob + sin(a) * vr
            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, col.copy(alpha = alpha), style = Stroke(width = sw, cap = StrokeCap.Round))
    }
    val coreR = minDim * (0.04f + 0.10f * f.rms)
    drawCircle(Brush.radialGradient(listOf(c.secondary, c.primary.copy(alpha = 0f)), center = Offset(cx, cyBob), radius = coreR * 3f), radius = coreR * 3f, center = Offset(cx, cyBob))
}
