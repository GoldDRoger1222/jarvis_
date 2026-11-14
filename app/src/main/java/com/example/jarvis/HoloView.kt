package com.example.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HoloView(ctx: Context) : View(ctx) {
    var model: HoloModel? = null
    var pyramid = false
    var flip = false
    var spin = true
    var yaw = 0f
    var pitch = 0.3f
    var zoom = 1f
    var onTap: (() -> Unit)? = null

    private var last = 0L
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var pz = FloatArray(0)
    private var b0 = FloatArray(0)
    private var b1 = FloatArray(0)
    private var b2 = FloatArray(0)
    private var scanBuf = FloatArray(0)
    private var lx = 0f
    private var ly = 0f
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false

    private fun stroke(a: Int, w: Float, r: Int = 0, g: Int = 229, b: Int = 255): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(a, r, g, b)
        return p
    }

    private val glowP = stroke(40, 8f)
    private val farP = stroke(80, 1.6f)
    private val midP = stroke(170, 2.2f)
    private val nearP = stroke(255, 3f, 150, 245, 255)
    private val floorP = stroke(90, 1.6f)
    private val scanP = stroke(20, 1f)
    private val barP = Paint().apply { color = Color.argb(30, 120, 255, 255) }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 229, 255)
        textSize = 38f
        textAlign = Paint.Align.CENTER
    }

    private val sgd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.4f, 3f)
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val n = h / 6 + 1
        scanBuf = FloatArray(n * 4)
        for (i in 0 until n) {
            scanBuf[i * 4] = 0f
            scanBuf[i * 4 + 1] = i * 6f
            scanBuf[i * 4 + 2] = w.toFloat()
            scanBuf[i * 4 + 3] = i * 6f
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sgd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y; downX = e.x; downY = e.y; downT = e.eventTime; moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lx = e.x; ly = e.y; moved = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!sgd.isInProgress && e.pointerCount == 1) {
                    yaw += (e.x - lx) * 0.012f
                    pitch = (pitch + (e.y - ly) * 0.01f).coerceIn(-1.4f, 1.4f)
                    lx = e.x
                    ly = e.y
                    if (abs(e.x - downX) > 14 || abs(e.y - downY) > 14) moved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && e.eventTime - downT < 350) onTap?.invoke()
            }
        }
        return true
    }

    private fun project(m: HoloModel) {
        val n = m.verts.size / 3
        if (px.size < n) {
            px = FloatArray(n)
            py = FloatArray(n)
            pz = FloatArray(n)
        }
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val v = m.verts
        for (i in 0 until n) {
            val x = v[i * 3]
            val y = v[i * 3 + 1]
            val z = v[i * 3 + 2]
            val x1 = x * cy + z * sy
            val z1 = -x * sy + z * cy
            val y2 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            val k = 3.4f / (3.4f + z2)
            px[i] = x1 * k
            py[i] = -y2 * k
            pz[i] = z2
        }
    }

    private fun fillDraw(c: Canvas, m: HoloModel, sc: Float, glow: Boolean) {
        val ne = m.edges.size / 2
        if (b0.size < ne * 4) {
            b0 = FloatArray(ne * 4)
            b1 = FloatArray(ne * 4)
            b2 = FloatArray(ne * 4)
        }
        var n0 = 0
        var n1 = 0
        var n2 = 0
        val e = m.edges
        for (k in 0 until ne) {
            val a = e[2 * k]
            val b = e[2 * k + 1]
            val z = (pz[a] + pz[b]) * 0.5f
            val buf: FloatArray
            val o: Int
            if (z > 0.35f) { buf = b0; o = n0; n0 += 4 }
            else if (z < -0.35f) { buf = b2; o = n2; n2 += 4 }
            else { buf = b1; o = n1; n1 += 4 }
            buf[o] = px[a] * sc
            buf[o + 1] = py[a] * sc
            buf[o + 2] = px[b] * sc
            buf[o + 3] = py[b] * sc
        }
        if (glow) {
            c.drawLines(b0, 0, n0, glowP)
            c.drawLines(b1, 0, n1, glowP)
            c.drawLines(b2, 0, n2, glowP)
        }
        c.drawLines(b0, 0, n0, farP)
        c.drawLines(b1, 0, n1, midP)
        c.drawLines(b2, 0, n2, nearP)
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        if (spin) yaw += dt * 0.6f
        c.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val m = model
        if (m == null) {
            c.drawText("Upore likhe Banao tap koro", cx, cy, txtP)
        } else {
            project(m)
            if (pyramid) {
                val s = minOf(w, h)
                val sc = s * 0.15f * zoom
                val off = s * 0.30f
                for (a in floatArrayOf(0f, 90f, 180f, -90f)) {
                    c.save()
                    c.translate(cx, cy)
                    c.rotate(a)
                    c.translate(0f, -off)
                    if (flip) c.rotate(180f)
                    fillDraw(c, m, sc, false)
                    c.restore()
                }
            } else {
                c.drawLines(scanBuf, scanP)
                val by = (now / 1e9f * 140f) % h
                c.drawRect(0f, by, w, by + 36f, barP)
                val sc = minOf(w, h * 0.8f) * 0.30f * zoom
                val floorY = cy + sc * 1.25f
                for (k in 0..2) {
                    val rr = sc * 1.25f * (0.55f + 0.25f * k)
                    c.drawOval(cx - rr, floorY - rr * 0.22f, cx + rr, floorY + rr * 0.22f, floorP)
                }
                c.save()
                c.translate(cx, cy)
                fillDraw(c, m, sc, true)
                c.restore()
            }
        }
        postInvalidateOnAnimation()
    }
}
