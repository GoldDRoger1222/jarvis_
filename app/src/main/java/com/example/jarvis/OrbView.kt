package com.example.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class OrbView(ctx: Context) : View(ctx) {
    companion object {
        const val OFF = 0
        const val IDLE = 1
        const val LISTEN = 2
        const val THINK = 3
        const val SPEAK = 4
        const val ALERT = 5
    }

    var state = OFF
    private var t = 0f
    private var spin = 0f
    private var rot = 0f
    private var last = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun colorFor(s: Int): Int = when (s) {
        OFF -> Color.rgb(70, 100, 120)
        THINK -> Color.rgb(255, 190, 60)
        SPEAK -> Color.rgb(0, 255, 190)
        ALERT -> Color.rgb(255, 70, 70)
        else -> Color.rgb(0, 229, 255)
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        t += dt
        val targetSpin = when (state) {
            OFF -> 0f
            IDLE -> 0.5f
            LISTEN -> 1.2f
            THINK -> 3.2f
            SPEAK -> 1.6f
            else -> 2f
        }
        spin += (targetSpin - spin) * minOf(1f, dt * 3f)
        rot += dt * 22f * spin

        val col = colorFor(state)
        val r0 = Color.red(col)
        val g0 = Color.green(col)
        val b0 = Color.blue(col)
        val dim = if (state == OFF) 0.35f else 1f
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val rr = minOf(w, h) * 0.46f
        val pulse = when (state) {
            OFF -> 0.02f * sin(t * 1.5f)
            IDLE -> 0.05f * sin(t * 2.5f)
            LISTEN -> 0.12f * sin(t * 7f)
            THINK -> 0.08f * sin(t * 11f)
            SPEAK -> 0.10f * sin(t * 9f) + 0.08f * sin(t * 15.3f + 1f)
            else -> 0.10f * sin(t * 14f)
        }

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND

        p.strokeWidth = 2f
        p.color = Color.argb((110 * dim).toInt(), r0, g0, b0)
        c.drawCircle(cx, cy, rr, p)

        for (i in 0 until 72) {
            val ang = Math.toRadians((i * 5 + rot * 0.4f).toDouble())
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            val long = i % 6 == 0
            val len = if (long) rr * 0.09f else rr * 0.045f
            p.strokeWidth = if (long) 3f else 1.5f
            p.color = Color.argb(((if (long) 200 else 120) * dim).toInt(), r0, g0, b0)
            c.drawLine(cx + (rr - len) * ca, cy + (rr - len) * sa, cx + rr * ca, cy + rr * sa, p)
        }

        p.strokeWidth = 7f
        p.color = Color.argb((170 * dim).toInt(), r0, g0, b0)
        val r2 = rr * 0.82f
        rect.set(cx - r2, cy - r2, cx + r2, cy + r2)
        for (k in 0 until 6) c.drawArc(rect, -rot * 1.3f + k * 60f, 38f, false, p)

        p.strokeWidth = 3f
        p.color = Color.argb((140 * dim).toInt(), r0, g0, b0)
        val r3 = rr * 0.68f
        rect.set(cx - r3, cy - r3, cx + r3, cy + r3)
        for (k in 0 until 3) c.drawArc(rect, rot * 2f + k * 120f, 70f, false, p)

        p.strokeWidth = 5f
        p.color = Color.argb((200 * dim).toInt(), r0, g0, b0)
        c.drawCircle(cx, cy, rr * 0.5f * (1f + pulse * 0.3f), p)

        p.strokeWidth = 4f
        p.color = Color.argb((150 * dim).toInt(), r0, g0, b0)
        for (i in 0 until 10) {
            val ang = Math.toRadians((i * 36 + rot * 0.8f).toDouble())
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            c.drawLine(cx + rr * 0.24f * ca, cy + rr * 0.24f * sa, cx + rr * 0.46f * ca, cy + rr * 0.46f * sa, p)
        }

        p.style = Paint.Style.FILL
        val cr = rr * 0.20f * (1f + pulse)
        p.color = Color.argb((35 * dim).toInt(), r0, g0, b0)
        c.drawCircle(cx, cy, cr * 2.6f, p)
        p.color = Color.argb((70 * dim).toInt(), r0, g0, b0)
        c.drawCircle(cx, cy, cr * 1.8f, p)
        p.color = Color.argb((130 * dim).toInt(), r0, g0, b0)
        c.drawCircle(cx, cy, cr * 1.2f, p)
        p.color = Color.argb((255 * dim).toInt(), (r0 + 255) / 2, (g0 + 255) / 2, (b0 + 255) / 2)
        c.drawCircle(cx, cy, cr * 0.7f, p)

        postInvalidateOnAnimation()
    }
}
