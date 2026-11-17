package com.example.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
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
            exploded = false
            explT = 0f
            prepare(v)
        }
    var pyramid = false
    var flip = false
    var spin = true
    var yaw = 0f
    var pitch = 0.3f
    var zoom = 1f
    var exploded = false
    var onTap: (() -> Unit)? = null

    private val dens = ctx.resources.displayMetrics.density
    private var sel = -1
    private var iso = false
    private var isoT = 0f
    private var explT = 0f
    private var gcx = 0f
    private var gcy = 0f
    private var gcz = 0f
    private var gk = 1f
    private var last = 0L
    private var tAnim = 0f
    private var fps = 60f
    private var fpsAcc = 0f
    private var fpsN = 0

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

    private var grpOx = FloatArray(0)
    private var grpOy = FloatArray(0)
    private var grpOz = FloatArray(0)
    private var grpN = IntArray(0)
    private var grpSx = FloatArray(0)
    private var grpSy = FloatArray(0)
    private var orderG = IntArray(0)

    private var hxs = FloatArray(0)
    private var hys = FloatArray(0)
    private var hsx = FloatArray(0)
    private var hsy = FloatArray(0)
    private var hkey = LongArray(0)
    private var hh = IntArray(0)
    private val path = Path()

    private val lbl = IntArray(9)
    private val lAx = FloatArray(9)
    private val lAy = FloatArray(9)
    private val lSide = IntArray(9)
    private val lIdx = IntArray(9)

    private var menuOn = false
    private var menuL = 0f
    private var menuT = 0f
    private var menuR = 0f
    private var menuB = 0f
    private var menuListTop = 0f
    private var menuRowH = 1f
    private var menuCount = 0
    private val menuG = IntArray(12)

    private var scanBuf = FloatArray(0)
    private var starX = FloatArray(0)
    private var starY = FloatArray(0)
    private var starPh = FloatArray(0)
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
    private val scanP = stroke(14, 1f)
    private val leadP = stroke(190, 1.6f)
    private val panelS = stroke(120, 1.5f)
    private val accP = stroke(255, 3f, 150, 245, 255)
    private val frameC = stroke(120, 2f)
    private val frameV = stroke(90, 2f, 170, 100, 255)
    private val barP = Paint().apply { color = Color.argb(24, 120, 255, 255) }
    private val panelP = Paint().apply { style = Paint.Style.FILL; color = Color.argb(95, 5, 15, 35) }
    private val selP = Paint().apply { style = Paint.Style.FILL; color = Color.argb(60, 255, 240, 150) }
    private val barBg = Paint().apply { style = Paint.Style.FILL; color = Color.argb(60, 0, 229, 255) }
    private val barFg = Paint().apply { style = Paint.Style.FILL; color = Color.argb(200, 0, 229, 255) }
    private val nebA = Paint()
    private val nebB = Paint()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(255, 255, 240, 150)
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
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
    private val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val monoP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = Color.argb(220, 150, 235, 255)
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
        override fun onLongPress(e: MotionEvent) {
            if (!pyramid && !iso && model != null) {
                exploded = !exploded
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
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
        val wf = w.toFloat()
        val hf = h.toFloat()
        nebA.shader = RadialGradient(wf * 0.28f, hf * 0.38f, maxOf(wf, hf) * 0.65f,
            Color.argb(80, 110, 50, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        nebB.shader = RadialGradient(wf * 0.78f, hf * 0.68f, maxOf(wf, hf) * 0.6f,
            Color.argb(70, 30, 90, 220), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val rnd = java.util.Random(7)
        starX = FloatArray(90) { rnd.nextFloat() * wf }
        starY = FloatArray(90) { rnd.nextFloat() * hf }
        starPh = FloatArray(90) { rnd.nextFloat() * 6.28f }
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

    private fun menuHit(x: Float, y: Float): Int {
        if (!menuOn || x < menuL || x > menuR || y < menuT || y > menuB) return -1
        if (y < menuListTop) return -2
        val r = ((y - menuListTop) / menuRowH).toInt()
        return if (r in 0 until menuCount) menuG[r] else -2
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
        val hg = menuHit(x, y)
        if (hg == -2) return
        if (hg >= 0) {
            if (!iso) sel = if (sel == hg) -1 else hg
            return
        }
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
        val hg = menuHit(x, y)
        if (hg == -2) return
        val g = if (hg >= 0) hg else pick(x, y)
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

        val ng = m.groupNames.size
        val sx = FloatArray(ng)
        val sy = FloatArray(ng)
        val sz = FloatArray(ng)
        val cn = IntArray(ng)
        for ((pi, pt) in m.parts.withIndex()) {
            val g = m.partGroup[pi]
            for (i in pt.start until pt.end) {
                sx[g] += m.verts[i * 3]
                sy[g] += m.verts[i * 3 + 1]
                sz[g] += m.verts[i * 3 + 2]
                cn[g]++
            }
        }
        grpOx = FloatArray(ng)
        grpOy = FloatArray(ng)
        grpOz = FloatArray(ng)
        grpSx = FloatArray(ng)
        grpSy = FloatArray(ng)
        grpN = cn
        for (g in 0 until ng) {
            if (cn[g] == 0) continue
            val x = sx[g] / cn[g]
            val y = sy[g] / cn[g]
            val z = sz[g] / cn[g]
            val len = sqrt(x * x + y * y + z * z)
            if (len < 0.2f) {
                val ang = g * 2.4f
                grpOx[g] = cos(ang) * 0.5f
                grpOy[g] = 0.3f * sin(ang)
                grpOz[g] = sin(ang) * 0.5f
            } else {
                val mag = len * 1.1f + 0.35f
                grpOx[g] = x / len * mag
                grpOy[g] = y / len * mag * 0.8f
                grpOz[g] = z / len * mag
            }
        }
        orderG = (0 until ng).sortedByDescending { cn[it] }.take(12).toIntArray()
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
        if (explT > 0.001f && grpOx.size == m.groupNames.size) {
            for ((pi, pt) in m.parts.withIndex()) {
                val g = m.partGroup[pi]
                val ox = grpOx[g] * explT
                val oy = grpOy[g] * explT
                val oz = grpOz[g] * explT
                for (i in pt.start until pt.end) {
                    av[i * 3] += ox
                    av[i * 3 + 1] += oy
                    av[i * 3 + 2] += oz
                }
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
        if (grpSx.size == m.groupNames.size) {
            java.util.Arrays.fill(grpSx, 0f)
            java.util.Arrays.fill(grpSy, 0f)
            for ((pi, pt) in m.parts.withIndex()) {
                val g = m.partGroup[pi]
                for (i in pt.start until pt.end) {
                    grpSx[g] += px[i]
                    grpSy[g] += py[i]
                }
            }
        }
    }

    // ---------- glass fill ----------

    private fun cr(a: Int, b: Int, c2: Int): Float =
        (hsx[b] - hsx[a]) * (hsy[c2] - hsy[a]) - (hsy[b] - hsy[a]) * (hsx[c2] - hsx[a])

    private fun fillPart(c: Canvas, pt: HoloPart, sc: Float, f: Float, hl: Boolean) {
        val n = pt.end - pt.start
        if (n < 3 || n > 400 || f < 0.03f) return
        if (hkey.size < n) {
            hxs = FloatArray(n)
            hys = FloatArray(n)
            hsx = FloatArray(n)
            hsy = FloatArray(n)
            hkey = LongArray(n)
            hh = IntArray(2 * n + 2)
        }
        var zs = 0f
        for (j in 0 until n) {
            val i = pt.start + j
            val x = px[i] * sc
            val y = py[i] * sc
            hxs[j] = x
            hys[j] = y
            zs += pz[i]
            hkey[j] = (((x + 30000f) * 8f).toInt().toLong() shl 20) or j.toLong()
        }
        java.util.Arrays.sort(hkey, 0, n)
        for (j in 0 until n) {
            val id = (hkey[j] and 0xFFFFFL).toInt()
            hsx[j] = hxs[id]
            hsy[j] = hys[id]
        }
        var k = 0
        for (i in 0 until n) {
            while (k >= 2 && cr(hh[k - 2], hh[k - 1], i) <= 0f) k--
            hh[k++] = i
        }
        val t = k + 1
        for (i in n - 2 downTo 0) {
            while (k >= t && cr(hh[k - 2], hh[k - 1], i) <= 0f) k--
            hh[k++] = i
        }
        if (k < 4) return
        path.reset()
        path.moveTo(hsx[hh[0]], hsy[hh[0]])
        for (i in 1 until k - 1) path.lineTo(hsx[hh[i]], hsy[hh[i]])
        path.close()
        val col = HoloModel.PALETTE[pt.role]
        val mix = (((zs / n) + 1f) * 0.5f).coerceIn(0f, 1f) * 0.65f
        val r = (Color.red(col) + (140 - Color.red(col)) * mix).toInt()
        val g = (Color.green(col) + (90 - Color.green(col)) * mix).toInt()
        val b = (Color.blue(col) + (255 - Color.blue(col)) * mix).toInt()
        fillP.color = if (hl) Color.argb(60, 255, 235, 140)
        else Color.argb((26 * f).toInt().coerceIn(0, 255), r, g, b)
        c.drawPath(path, fillP)
    }

    // ---------- edges ----------

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

    // ---------- scene decoration ----------

    private fun drawSpace(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(3, 5, 12))
        if (nebA.shader != null) {
            c.drawRect(0f, 0f, w, h, nebA)
            c.drawRect(0f, 0f, w, h, nebB)
        }
        for (i in starX.indices) {
            val a = (110 + 100 * sin(tAnim * 1.5f + starPh[i])).toInt().coerceIn(0, 255)
            starPaint.color = Color.argb(a, 200, 230, 255)
            c.drawCircle(starX[i], starY[i], 0.6f * dens + 0.5f, starPaint)
        }
    }

    private fun drawFrame(c: Canvas, w: Float, h: Float) {
        path.reset()
        path.moveTo(w * 0.02f, h * 0.06f)
        path.lineTo(w * 0.98f, h * 0.025f)
        path.lineTo(w * 0.98f, h * 0.975f)
        path.lineTo(w * 0.02f, h * 0.94f)
        path.close()
        c.drawPath(path, frameC)
        path.reset()
        path.moveTo(w * 0.045f, h * 0.09f)
        path.lineTo(w * 0.955f, h * 0.06f)
        path.lineTo(w * 0.955f, h * 0.95f)
        path.lineTo(w * 0.045f, h * 0.915f)
        path.close()
        c.drawPath(path, frameV)
    }

    private fun drawLabels(c: Canvas, m: HoloModel, sc: Float, cx: Float, cy: Float, w: Float, h: Float) {
        if (iso || grpN.size != m.groupNames.size) return
        var n = 0
        if (explT > 0.3f) {
            for (q in 0 until minOf(9, orderG.size)) lbl[n++] = orderG[q]
        } else if (sel >= 0) {
            lbl[n++] = sel
        }
        if (n == 0) return
        for (q in 0 until n) {
            val g = lbl[q]
            val cnt = grpN[g].coerceAtLeast(1)
            lAx[q] = cx + grpSx[g] / cnt * sc
            lAy[q] = cy + grpSy[g] / cnt * sc
            lSide[q] = if (lAx[q] < cx) 0 else 1
        }
        lblP.textSize = 9.5f * dens
        for (sd in 0..1) {
            var k = 0
            for (q in 0 until n) if (lSide[q] == sd) lIdx[k++] = q
            for (i in 1 until k) {
                val v = lIdx[i]
                var j = i - 1
                while (j >= 0 && lAy[lIdx[j]] > lAy[v]) {
                    lIdx[j + 1] = lIdx[j]
                    j--
                }
                lIdx[j + 1] = v
            }
            var prevY = h * 0.12f
            for (r in 0 until k) {
                val q = lIdx[r]
                val g = lbl[q]
                val text = m.groupNames[g].uppercase().take(24)
                val tw = lblP.measureText(text)
                var ly2 = maxOf(lAy[q], prevY + 15f * dens)
                ly2 = minOf(ly2, h * 0.93f)
                prevY = ly2
                val tx = if (sd == 0) 10f * dens else w - 10f * dens - tw
                val ex = if (sd == 0) tx + tw + 8f * dens else tx - 8f * dens
                val yy = ly2 + 3f * dens
                c.drawLine(lAx[q], lAy[q], ex, yy, leadP)
                c.drawLine(ex, yy, if (sd == 0) tx else tx + tw, yy, leadP)
                c.drawCircle(lAx[q], lAy[q], 2.6f * dens, dotP)
                lblP.color = if (g == sel) Color.argb(255, 255, 240, 150) else Color.argb(255, 210, 245, 255)
                c.drawText(text, tx, ly2, lblP)
            }
        }
    }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        c.drawRect(l, t, r, b, panelP)
        c.drawRect(l, t, r, b, panelS)
        val a = 10f * dens
        c.drawLine(l, t, l + a, t, accP)
        c.drawLine(l, t, l, t + a, accP)
        c.drawLine(r, b, r - a, b, accP)
        c.drawLine(r, b, r, b - a, accP)
    }

    private fun drawHud(c: Canvas, m: HoloModel, w: Float, h: Float, land: Boolean) {
        monoP.textSize = 9f * dens
        var moving = 0
        for (pt in m.parts) if (pt.kind != 0) moving++
        val ex = (explT * 100f).toInt()
        if (!land) {
            monoP.textAlign = Paint.Align.CENTER
            c.drawText(m.name.uppercase().take(28), w / 2, h * 0.085f, monoP)
            c.drawText("PARTS " + m.parts.size + "  MOVING " + moving + "  EXPLODE " + ex + "%  FPS " + fps.toInt(),
                w / 2, h * 0.925f, monoP)
            monoP.textAlign = Paint.Align.LEFT
            return
        }
        monoP.textAlign = Paint.Align.LEFT
        c.drawText("JARVIS // HOLOGRAPHIC SCHEMATIC", w * 0.04f, h * 0.085f, monoP)
        monoP.textAlign = Paint.Align.RIGHT
        c.drawText(m.name.uppercase().take(26), w * 0.96f, h * 0.085f, monoP)
        monoP.textAlign = Paint.Align.LEFT

        val lL = w * 0.03f
        val lT = h * 0.14f
        val lR = w * 0.24f
        val lB = h * 0.52f
        panel(c, lL, lT, lR, lB)
        c.drawText("PARAMETERS", lL + 8f * dens, lT + 14f * dens, monoP)
        val items = arrayOf("ASSEMBLY", "ZOOM", "ROTATION", "FRAME RATE")
        val twoPi = 6.2832f
        val vals = floatArrayOf(
            1f - explT,
            (zoom / 3f).coerceIn(0f, 1f),
            ((yaw % twoPi) + twoPi) % twoPi / twoPi,
            (fps / 60f).coerceIn(0f, 1f))
        for (i in items.indices) {
            val y0 = lT + 34f * dens + i * 30f * dens
            c.drawText(items[i], lL + 8f * dens, y0, monoP)
            val bl = lL + 8f * dens
            val br = lR - 8f * dens
            c.drawRect(bl, y0 + 5f * dens, br, y0 + 9f * dens, barBg)
            c.drawRect(bl, y0 + 5f * dens, bl + (br - bl) * vals[i], y0 + 9f * dens, barFg)
        }

        val rL = w * 0.74f
        val rT = h * 0.14f
        val rR = w * 0.97f
        val rB = h * 0.90f
        panel(c, rL, rT, rR, rB)
        c.drawText("COMPONENTS", rL + 8f * dens, rT + 14f * dens, monoP)
        val listTop = rT + 22f * dens
        val rowH = 15f * dens
        val statsH = 48f * dens
        val maxRows = ((rB - listTop - statsH) / rowH).toInt().coerceIn(1, 12)
        val cnt = minOf(maxRows, orderG.size)
        for (r in 0 until cnt) {
            val g = orderG[r]
            val y = listTop + r * rowH
            if (g == sel) c.drawRect(rL + 4f * dens, y, rR - 4f * dens, y + rowH, selP)
            monoP.color = if (g == sel) Color.argb(255, 255, 240, 150) else Color.argb(220, 150, 235, 255)
            c.drawText((r + 1).toString().padStart(2, '0') + " " + m.groupNames[g].uppercase().take(18),
                rL + 8f * dens, y + rowH - 4f * dens, monoP)
            menuG[r] = g
        }
        monoP.color = Color.argb(220, 150, 235, 255)
        val sy0 = rB - statsH + 12f * dens
        c.drawText("PARTS " + m.parts.size + "   MOVING " + moving, rL + 8f * dens, sy0, monoP)
        c.drawText("EXPLODE " + ex + "%   LINES " + m.edgeCount, rL + 8f * dens, sy0 + 14f * dens, monoP)
        c.drawText("long-press = explode", rL + 8f * dens, sy0 + 28f * dens, monoP)
        menuOn = true
        menuL = rL
        menuT = rT
        menuR = rR
        menuB = rB
        menuListTop = listTop
        menuRowH = rowH
        menuCount = cnt
    }

    // ---------- main draw ----------

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        fpsAcc += dt
        fpsN++
        if (fpsAcc >= 0.5f) {
            fps = fpsN / fpsAcc
            fpsAcc = 0f
            fpsN = 0
        }
        if (spin) yaw += dt * 0.6f
        tAnim += dt
        val target = if (iso) 1f else 0f
        isoT += (target - isoT) * minOf(1f, dt * 6f)
        if (abs(target - isoT) < 0.002f) isoT = target
        val et = if (exploded) 1f else 0f
        explT += (et - explT) * minOf(1f, dt * 3f)
        if (abs(et - explT) < 0.002f) explT = et

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val m = model
        menuOn = false
        if (pyramid) c.drawColor(Color.BLACK) else drawSpace(c, w, h)

        if (m == null) {
            c.drawText("Upore likhe Banao tap koro", cx, cy, txtP)
        } else {
            if (av.size != m.verts.size) prepare(m)
            frame(m)
            project(m)
            val fOthers = if (isoT >= 0.01f) 1f - isoT else if (sel >= 0) 0.4f else 1f
            if (pyramid) {
                val s = minOf(w, h)
                val sc = s * 0.15f * zoom / (1f + 0.55f * explT)
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
                val land = w > h * 1.15f
                drawFrame(c, w, h)
                c.drawLines(scanBuf, scanP)
                val by = (now / 1e9f * 140f) % h
                c.drawRect(0f, by, w, by + 36f, barP)
                val base = minOf(w, h * 0.8f) * 0.30f * zoom * (if (land) 0.85f else 1f)
                val sc = base / (1f + 0.55f * explT)
                lastSc = sc
                lastCx = cx
                lastCy = cy
                val floorY = cy + sc * 1.25f
                for (k in 0..2) {
                    val rr = sc * 1.25f * (0.55f + 0.25f * k)
                    c.drawOval(cx - rr, floorY - rr * 0.22f, cx + rr, floorY + rr * 0.22f, floorP)
                }
                c.save()
                c.translate(cx, cy)
                for ((pi, pt) in m.parts.withIndex()) {
                    if (pt.shape > 4) continue
                    val g = m.partGroup[pi]
                    val isSel = sel >= 0 && g == sel
                    fillPart(c, pt, sc, if (isSel) 1f else fOthers, isSel && isoT < 0.01f)
                }
                build(m, sc)
                flush(c, bufA, cntA, fOthers, true)
                flush(c, bufB, cntB, 1f, true)
                drawHl(c, true)
                c.restore()
                drawLabels(c, m, sc, cx, cy, w, h)
                drawHud(c, m, w, h, land)
                if (sel >= 0 && sel < m.groupNames.size) {
                    c.drawText(m.groupNames[sel], cx, h * 0.15f, labP)
                    if (iso) c.drawText("khali jayga tap = model e fire", cx, h * 0.15f + 36f, hintP)
                } else if (!land) {
                    c.drawText("tap: select | double-tap: alada | long-press: explode", cx, h - 10f * dens, hintP)
                }
            }
        }
        postInvalidateOnAnimation()
    }
}
