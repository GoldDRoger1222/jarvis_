package com.example.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HoloView(ctx: Context) : View(ctx) {
    var model: HoloModel? = null
        set(v) {
            field = v
            PartGuide.dismiss()
            sel = -1
            iso = false
            isoT = 0f
            prepare(v)
        }
    var pyramid = false
    var flip = false
    var spin = true
    var yaw = 0f
    var pitch = 0.3f
    var zoom = 1f
    var onTap: (() -> Unit)? = null

    private var sel = -1
    private var iso = false
    private var isoT = 0f
    private var gcx = 0f
    private var gcy = 0f
    private var gcz = 0f
    private var gk = 1f
    private var last = 0L
    private var tAnim = 0f
    private var av = FloatArray(0)
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var pz = FloatArray(0)
    private var roleIdx: Array<IntArray> = emptyArray()
    private var bufA: Array<FloatArray> = emptyArray()
    private var bufB: Array<FloatArray> = emptyArray()
    private var bufH = FloatArray(0)
    private val cntA = IntArray(HoloModel.ROLES * 3)
    private val cntB = IntArray(HoloModel.ROLES * 3)
    private var cntH = 0
    private var scanBuf = FloatArray(0)
    private var lastSc = 1f
    private var lastCx = 0f
    private var lastCy = 0f
    private var lx = 0f
    private var ly = 0f

    private val alphaBk = intArrayOf(90, 175, 255)
    private val widthBk = floatArrayOf(1.6f, 2.2f, 2.9f)

    private fun stroke(a: Int, w: Float, r: Int = 0, g: Int = 229, b: Int = 255): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(a, r, g, b)
        return p
    }

    private val glowP = stroke(40, 8f)
    private val coreP = stroke(255, 2f)
    private val hlGlow = stroke(80, 11f, 255, 230, 120)
    private val hlCore = stroke(255, 3.6f, 255, 245, 160)
    private val floorP = stroke(90, 1.6f)
    private val scanP = stroke(20, 1f)
    private val barP = Paint().apply { color = Color.argb(30, 120, 255, 255) }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 229, 255)
        textSize = 38f
        textAlign = Paint.Align.CENTER
    }
    private val labP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 240, 150)
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hintP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 150, 190, 205)
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    private val sgd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.4f, 3f)
            return true
        }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            tapAt(e.x, e.y)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleAt(e.x, e.y)
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

    override fun onDetachedFromWindow() {
        PartGuide.dismiss()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sgd.onTouchEvent(e)
        gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lx = e.x
                ly = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!sgd.isInProgress && e.pointerCount == 1) {
                    yaw += (e.x - lx) * 0.012f
                    pitch = (pitch + (e.y - ly) * 0.01f).coerceIn(-1.4f, 1.4f)
                    lx = e.x
                    ly = e.y
                }
            }
        }
        return true
    }

    // ---------- selection ----------

    private fun distSeg(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        var t = if (l2 < 1e-6f) 0f else ((x - ax) * dx + (y - ay) * dy) / l2
        t = t.coerceIn(0f, 1f)
        val qx = ax + t * dx - x
        val qy = ay + t * dy - y
        return sqrt(qx * qx + qy * qy)
    }

    private fun pick(x: Float, y: Float): Int {
        val m = model ?: return -1
        if (px.size < m.verts.size / 3) return -1
        var best = -1
        var bs = 1e9f
        val e = m.edges
        for (k in 0 until m.edgeCount) {
            val a = e[2 * k]
            val b = e[2 * k + 1]
            val g = m.partGroup[m.vertPart[a]]
            if (isoT > 0.5f && g != sel) continue
            val d = distSeg(x, y,
                lastCx + px[a] * lastSc, lastCy + py[a] * lastSc,
                lastCx + px[b] * lastSc, lastCy + py[b] * lastSc)
            if (d < 40f) {
                val score = d + (pz[a] + pz[b]) * 8f
                if (score < bs) { bs = score; best = g }
            }
        }
        return best
    }

    private fun tapAt(x: Float, y: Float) {
        if (pyramid) { onTap?.invoke(); return }
        val g = pick(x, y)
        if (g >= 0) {
            if (iso) return
            sel = if (sel == g) -1 else g
        } else {
            if (iso) {
                iso = false
                PartGuide.dismiss()
            } else if (sel >= 0) sel = -1
            else onTap?.invoke()
        }
    }

    private fun doubleAt(x: Float, y: Float) {
        if (pyramid) return
        val m = model ?: return
        if (iso) {
            iso = false
            PartGuide.dismiss()
            return
        }
        val g = pick(x, y)
        if (g < 0) return
        startIso(m, g)
    }

    private fun startIso(m: HoloModel, g: Int) {
        sel = g
        var sx = 0f
        var sy = 0f
        var sz = 0f
        var n = 0
        for ((pi, pt) in m.parts.withIndex()) {
            if (m.partGroup[pi] != g) continue
            for (i in pt.start until pt.end) {
                sx += av[i * 3]
                sy += av[i * 3 + 1]
                sz += av[i * 3 + 2]
                n++
            }
        }
        if (n == 0) return
        gcx = sx / n
        gcy = sy / n
        gcz = sz / n
        var rad = 0f
        val names = ArrayList<String>()
        for ((pi, pt) in m.parts.withIndex()) {
            if (m.partGroup[pi] != g) continue
            names.add(pt.name)
            for (i in pt.start until pt.end) {
                val dx = av[i * 3] - gcx
                val dy = av[i * 3 + 1] - gcy
                val dz = av[i * 3 + 2] - gcz
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d > rad) rad = d
            }
        }
        gk = (0.9f / maxOf(rad, 0.05f)).coerceIn(1f, 6f)
        iso = true
        PartGuide.show(context, m.name, m.groupNames[g], names, m.groupNames.toList()) { iso = false }
    }

    // ---------- model preparation ----------

    private fun prepare(m: HoloModel?) {
        if (m == null) return
        val n = m.verts.size / 3
        av = FloatArray(m.verts.size)
        px = FloatArray(n)
        py = FloatArray(n)
        pz = FloatArray(n)
        val ne = m.edgeCount
        val counts = IntArray(HoloModel.ROLES)
        for (k in 0 until ne) counts[m.edgeRole[k]]++
        roleIdx = Array(HoloModel.ROLES) { IntArray(counts[it]) }
        val fill = IntArray(HoloModel.ROLES)
        for (k in 0 until ne) {
            val r = m.edgeRole[k]
            roleIdx[r][fill[r]++] = k
        }
        bufA = Array(HoloModel.ROLES * 3) { FloatArray(counts[it / 3] * 4) }
        bufB = Array(HoloModel.ROLES * 3) { FloatArray(counts[it / 3] * 4) }
        bufH = FloatArray(ne * 4)
    }

    private fun rotPart(pt: HoloPart, ang: Double) {
        val c = cos(ang).toFloat()
        val s = sin(ang).toFloat()
        val omc = 1f - c
        for (i in pt.start until pt.end) {
            val x = av[i * 3] - pt.ox
            val y = av[i * 3 + 1] - pt.oy
            val z = av[i * 3 + 2] - pt.oz
            val dot = pt.ax * x + pt.ay * y + pt.az * z
            val cx = pt.ay * z - pt.az * y
            val cy = pt.az * x - pt.ax * z
            val cz = pt.ax * y - pt.ay * x
            av[i * 3] = x * c + cx * s + pt.ax * dot * omc + pt.ox
            av[i * 3 + 1] = y * c + cy * s + pt.ay * dot * omc + pt.oy
            av[i * 3 + 2] = z * c + cz * s + pt.az * dot * omc + pt.oz
        }
    }

    private fun frame(m: HoloModel) {
        System.arraycopy(m.verts, 0, av, 0, m.verts.size)
        val t = tAnim.toDouble()
        for (pt in m.parts) {
            when (pt.kind) {
                1 -> rotPart(pt, Math.toRadians(pt.speed.toDouble() * t))
                2 -> {
                    val d = (pt.amp * sin(2.0 * Math.PI * pt.hz * t + Math.toRadians(pt.phase.toDouble()))).toFloat()
                    for (i in pt.start until pt.end) {
                        av[i * 3] += pt.ax * d
                        av[i * 3 + 1] += pt.ay * d
                        av[i * 3 + 2] += pt.az * d
                    }
                }
                3 -> rotPart(pt, Math.toRadians(pt.amp.toDouble() *
                    sin(2.0 * Math.PI * pt.hz * t + Math.toRadians(pt.phase.toDouble()))))
            }
        }
        if (isoT > 0.001f && sel >= 0) {
            for ((pi, pt) in m.parts.withIndex()) {
                if (m.partGroup[pi] != sel) continue
                for (i in pt.start until pt.end) {
                    val x = av[i * 3]
                    val y = av[i * 3 + 1]
                    val z = av[i * 3 + 2]
                    av[i * 3] = x + ((x - gcx) * gk - x) * isoT
                    av[i * 3 + 1] = y + ((y - gcy) * gk - y) * isoT
                    av[i * 3 + 2] = z + ((z - gcz) * gk - z) * isoT
                }
            }
        }
    }

    private fun project(m: HoloModel) {
        val n = m.verts.size / 3
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        for (i in 0 until n) {
            val x = av[i * 3]
            val y = av[i * 3 + 1]
            val z = av[i * 3 + 2]
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

    // ---------- drawing ----------

    private fun build(m: HoloModel, sc: Float) {
        java.util.Arrays.fill(cntA, 0)
        java.util.Arrays.fill(cntB, 0)
        cntH = 0
        val e = m.edges
        val sep = isoT >= 0.01f
        for (r in 0 until HoloModel.ROLES) {
            val idx = roleIdx[r]
            for (q in idx.indices) {
                val k = idx[q]
                val a = e[2 * k]
                val b = e[2 * k + 1]
                val g = m.partGroup[m.vertPart[a]]
                val z = (pz[a] + pz[b]) * 0.5f
                val bk = if (z > 0.35f) 0 else if (z < -0.35f) 2 else 1
                val isSel = sel >= 0 && g == sel
                val buf: FloatArray
                val o: Int
                if (isSel && !sep) {
                    buf = bufH
                    o = cntH
                    cntH += 4
                } else if (isSel) {
                    val i2 = r * 3 + bk
                    buf = bufB[i2]
                    o = cntB[i2]
                    cntB[i2] += 4
                } else {
                    val i2 = r * 3 + bk
                    buf = bufA[i2]
                    o = cntA[i2]
                    cntA[i2] += 4
                }
                buf[o] = px[a] * sc
                buf[o + 1] = py[a] * sc
                buf[o + 2] = px[b] * sc
                buf[o + 3] = py[b] * sc
            }
        }
    }

    private fun flush(c: Canvas, bufs: Array<FloatArray>, cnt: IntArray, f: Float, glow: Boolean) {
        if (f < 0.02f) return
        for (r in 0 until HoloModel.ROLES) {
            val col = HoloModel.PALETTE[r]
            val cr = Color.red(col)
            val cg = Color.green(col)
            val cb = Color.blue(col)
            for (bk in 0..2) {
                val n = cnt[r * 3 + bk]
                if (n == 0) continue
                val buf = bufs[r * 3 + bk]
                if (glow && bk > 0) {
                    glowP.color = Color.argb((38 * f).toInt(), cr, cg, cb)
                    c.drawLines(buf, 0, n, glowP)
                }
                coreP.strokeWidth = widthBk[bk]
                coreP.color = Color.argb((alphaBk[bk] * f).toInt().coerceIn(0, 255), cr, cg, cb)
                c.drawLines(buf, 0, n, coreP)
            }
        }
    }

    private fun drawHl(c: Canvas, glow: Boolean) {
        if (cntH == 0) return
        if (glow) c.drawLines(bufH, 0, cntH, hlGlow)
        c.drawLines(bufH, 0, cntH, hlCore)
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        if (spin) yaw += dt * 0.6f
        tAnim += dt
        val target = if (iso) 1f else 0f
        isoT += (target - isoT) * minOf(1f, dt * 6f)
        if (abs(target - isoT) < 0.002f) isoT = target

        c.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val m = model
        if (m == null) {
            c.drawText("Upore likhe Banao tap koro", cx, cy, txtP)
        } else {
            if (av.size != m.verts.size) prepare(m)
            frame(m)
            project(m)
            val fOthers = if (isoT >= 0.01f) 1f - isoT else if (sel >= 0) 0.4f else 1f
            if (pyramid) {
                val s = minOf(w, h)
                val sc = s * 0.15f * zoom
                val off = s * 0.30f
                build(m, sc)
                for (a in floatArrayOf(0f, 90f, 180f, -90f)) {
                    c.save()
                    c.translate(cx, cy)
                    c.rotate(a)
                    c.translate(0f, -off)
                    if (flip) c.rotate(180f)
                    flush(c, bufA, cntA, fOthers, false)
                    flush(c, bufB, cntB, 1f, false)
                    drawHl(c, false)
                    c.restore()
                }
            } else {
                c.drawLines(scanBuf, scanP)
                val by = (now / 1e9f * 140f) % h
                c.drawRect(0f, by, w, by + 36f, barP)
                val sc = minOf(w, h * 0.8f) * 0.30f * zoom
                lastSc = sc
                lastCx = cx
                lastCy = cy
                val floorY = cy + sc * 1.25f
                for (k in 0..2) {
                    val rr = sc * 1.25f * (0.55f + 0.25f * k)
                    c.drawOval(cx - rr, floorY - rr * 0.22f, cx + rr, floorY + rr * 0.22f, floorP)
                }
                build(m, sc)
                c.save()
                c.translate(cx, cy)
                flush(c, bufA, cntA, fOthers, true)
                flush(c, bufB, cntB, 1f, true)
                drawHl(c, true)
                c.restore()
                if (sel >= 0 && sel < m.groupNames.size) {
                    c.drawText(m.groupNames[sel], cx, h * 0.10f, labP)
                    if (iso) c.drawText("khali jayga tap = model e fire", cx, h * 0.10f + 36f, hintP)
                } else {
                    c.drawText("tap: select  |  double-tap: alada kore dekho", cx, h - 22f, hintP)
                }
            }
        }
        postInvalidateOnAnimation()
    }
}
