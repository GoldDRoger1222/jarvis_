package com.example.jarvis

import android.graphics.Color
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HoloPart(
    val name: String,
    val group: String,
    val role: Int,
    val start: Int,
    val end: Int,
    val kind: Int,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val ox: Float,
    val oy: Float,
    val oz: Float,
    val speed: Float,
    val amp: Float,
    val hz: Float,
    val phase: Float
)

private class Tmp(
    val name: String,
    val group: String,
    val role: Int,
    val start: Int,
    val end: Int,
    val kind: Int,
    val axis: DoubleArray,
    val piv: DoubleArray,
    val speed: Double,
    val amp: Double,
    val hz: Double,
    val phase: Double
)

class HoloModel(
    val name: String,
    val verts: FloatArray,
    val edges: IntArray,
    val parts: Array<HoloPart>,
    val vertPart: IntArray,
    val edgeRole: IntArray,
    val groupNames: Array<String>,
    val partGroup: IntArray
) {
    val edgeCount: Int get() = edges.size / 2

    companion object {
        const val ROLES = 9
        val PALETTE = intArrayOf(
            Color.rgb(0, 229, 255),
            Color.rgb(150, 190, 215),
            Color.rgb(255, 150, 40),
            Color.rgb(255, 70, 60),
            Color.rgb(200, 250, 255),
            Color.rgb(255, 225, 60),
            Color.rgb(60, 255, 140),
            Color.rgb(70, 130, 255),
            Color.rgb(80, 120, 140)
        )

        fun parse(text: String): HoloModel {
            val s = text.indexOf('{')
            val e = text.lastIndexOf('}')
            if (s < 0 || e <= s) throw Exception("Reply te model nai")
            val o = JSONObject(text.substring(s, e + 1))
            val parr = o.optJSONArray("parts") ?: throw Exception("parts nai")
            val vs = ArrayList<DoubleArray>()
            val es = ArrayList<IntArray>()
            val tmps = ArrayList<Tmp>()
            for (i in 0 until minOf(parr.length(), 150)) {
                val p = parr.optJSONObject(i) ?: continue
                val v0 = vs.size
                val e0 = es.size
                try {
                    val t = p.optString("t").lowercase()
                    val zero = doubleArrayOf(0.0, 0.0, 0.0)
                    val pos = if (t == "line") {
                        val la = arr(p, "a", zero)
                        val lb = arr(p, "b", doubleArrayOf(0.0, 1.0, 0.0))
                        doubleArrayOf((la[0] + lb[0]) / 2, (la[1] + lb[1]) / 2, (la[2] + lb[2]) / 2)
                    } else arr(p, "p", zero)
                    addPart(p, vs, es)
                    if (vs.size == v0) continue
                    var kind = 0
                    var axis = doubleArrayOf(0.0, 1.0, 0.0)
                    var piv = pos
                    var speed = 90.0
                    var amp = 0.1
                    var hz = 1.0
                    var phase = 0.0
                    val an = p.optJSONObject("a")
                    if (an != null) {
                        kind = when (an.optString("k").lowercase()) {
                            "rot", "spin" -> 1
                            "osc", "slide" -> 2
                            "swing" -> 3
                            else -> 0
                        }
                        if (kind != 0) {
                            axis = arr(an, "axis", axis)
                            val ln = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
                            axis = if (ln < 1e-6) doubleArrayOf(0.0, 1.0, 0.0)
                            else doubleArrayOf(axis[0] / ln, axis[1] / ln, axis[2] / ln)
                            piv = arr(an, "o", pos)
                            speed = an.optDouble("dps", 90.0)
                            amp = an.optDouble("amp", if (kind == 3) 20.0 else 0.1)
                            hz = an.optDouble("hz", 1.0)
                            phase = an.optDouble("ph", 0.0)
                        }
                    }
                    val nm = p.optString("n").ifBlank { t + " " + (i + 1) }
                    val gp = p.optString("g").ifBlank { nm }
                    tmps.add(Tmp(nm.take(40), gp.take(40), role(p.optString("c")), v0, vs.size,
                        kind, axis, piv, speed, amp, hz, phase))
                } catch (ex: Exception) {
                    while (vs.size > v0) vs.removeAt(vs.size - 1)
                    while (es.size > e0) es.removeAt(es.size - 1)
                }
            }
            if (vs.size < 3 || es.isEmpty() || tmps.isEmpty()) throw Exception("Model faka")

            val mn = doubleArrayOf(1e9, 1e9, 1e9)
            val mx = doubleArrayOf(-1e9, -1e9, -1e9)
            for (v in vs) for (k in 0..2) {
                if (v[k] < mn[k]) mn[k] = v[k]
                if (v[k] > mx[k]) mx[k] = v[k]
            }
            val c = DoubleArray(3) { (mn[it] + mx[it]) / 2 }
            var rad = 0.0
            for (v in vs) {
                val dx = v[0] - c[0]
                val dy = v[1] - c[1]
                val dz = v[2] - c[2]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d > rad) rad = d
            }
            if (rad < 1e-6) rad = 1.0
            val vf = FloatArray(vs.size * 3)
            for (i in vs.indices) {
                vf[i * 3] = ((vs[i][0] - c[0]) / rad).toFloat()
                vf[i * 3 + 1] = ((vs[i][1] - c[1]) / rad).toFloat()
                vf[i * 3 + 2] = ((vs[i][2] - c[2]) / rad).toFloat()
            }

            val vertPart = IntArray(vs.size)
            val groups = LinkedHashMap<String, Int>()
            val partGroup = IntArray(tmps.size)
            val parts = ArrayList<HoloPart>()
            for ((pi, t) in tmps.withIndex()) {
                for (i in t.start until t.end) vertPart[i] = pi
                val gi = groups.getOrPut(t.group) { groups.size }
                partGroup[pi] = gi
                val ampN = if (t.kind == 2) t.amp / rad else t.amp
                parts.add(HoloPart(
                    t.name, t.group, t.role, t.start, t.end, t.kind,
                    t.axis[0].toFloat(), t.axis[1].toFloat(), t.axis[2].toFloat(),
                    ((t.piv[0] - c[0]) / rad).toFloat(),
                    ((t.piv[1] - c[1]) / rad).toFloat(),
                    ((t.piv[2] - c[2]) / rad).toFloat(),
                    t.speed.toFloat(), ampN.toFloat(), t.hz.toFloat(), t.phase.toFloat()))
            }

            val ne = minOf(es.size, 7000)
            val ef = IntArray(ne * 2)
            val er = IntArray(ne)
            for (k in 0 until ne) {
                ef[2 * k] = es[k][0]
                ef[2 * k + 1] = es[k][1]
                er[k] = parts[vertPart[es[k][0]]].role
            }
            return HoloModel(o.optString("name", "model").take(40), vf, ef,
                parts.toTypedArray(), vertPart, er, groups.keys.toTypedArray(), partGroup)
        }

        private fun role(s: String): Int = when (s.lowercase()) {
            "body" -> 0
            "metal" -> 1
            "accent" -> 2
            "hot" -> 3
            "glass" -> 4
            "power" -> 5
            "green" -> 6
            "blue" -> 7
            "dark" -> 8
            else -> 0
        }

        private fun arr(p: JSONObject, k: String, d: DoubleArray): DoubleArray {
            val a = p.optJSONArray(k) ?: return d
            if (a.length() < 2) return d
            return doubleArrayOf(a.optDouble(0, d[0]), a.optDouble(1, d[1]), a.optDouble(2, d[2]))
        }

        private fun addPart(p: JSONObject, vs: ArrayList<DoubleArray>, es: ArrayList<IntArray>) {
            val t = p.optString("t").lowercase()
            val base = vs.size
            if (t == "line") {
                val a = arr(p, "a", doubleArrayOf(0.0, 0.0, 0.0))
                val b = arr(p, "b", doubleArrayOf(0.0, 1.0, 0.0))
                vs.add(a)
                vs.add(b)
                es.add(intArrayOf(base, base + 1))
                return
            }
            val pos = arr(p, "p", doubleArrayOf(0.0, 0.0, 0.0))
            val sz = arr(p, "s", doubleArrayOf(1.0, 1.0, 1.0))
            val rot = arr(p, "r", doubleArrayOf(0.0, 0.0, 0.0))
            val seg = p.optInt("seg", 16).coerceIn(3, 48)
            val lv = ArrayList<DoubleArray>()
            val le = ArrayList<IntArray>()
            when (t) {
                "box" -> box(lv, le)
                "cyl", "cylinder" -> cyl(lv, le, seg)
                "cone" -> cone(lv, le, seg)
                "sph", "sphere" -> sph(lv, le, seg)
                "pyr", "pyramid" -> pyr(lv, le)
                "tor", "torus" -> {
                    val outer = maxOf(sz[0], 0.02)
                    val tube = minOf(maxOf(sz[1], 0.01), outer * 0.9)
                    tor(lv, le, (outer - tube) / 2, tube / 2, if (p.has("seg")) maxOf(seg, 8) else 20)
                    sz[0] = 1.0
                    sz[1] = 1.0
                    sz[2] = 1.0
                }
                else -> return
            }
            for (v in lv) vs.add(xf(v, sz, rot, pos))
            for (e in le) es.add(intArrayOf(e[0] + base, e[1] + base))
        }

        private fun xf(v: DoubleArray, s: DoubleArray, r: DoubleArray, p: DoubleArray): DoubleArray {
            var x = v[0] * s[0]
            var y = v[1] * s[1]
            var z = v[2] * s[2]
            val rx = Math.toRadians(r[0])
            val ry = Math.toRadians(r[1])
            val rz = Math.toRadians(r[2])
            val y1 = y * cos(rx) - z * sin(rx)
            val z1 = y * sin(rx) + z * cos(rx)
            y = y1
            z = z1
            val x2 = x * cos(ry) + z * sin(ry)
            val z2 = -x * sin(ry) + z * cos(ry)
            x = x2
            z = z2
            val x3 = x * cos(rz) - y * sin(rz)
            val y3 = x * sin(rz) + y * cos(rz)
            x = x3
            y = y3
            return doubleArrayOf(x + p[0], y + p[1], z + p[2])
        }

        private fun box(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>) {
            for (i in 0 until 8) {
                v.add(doubleArrayOf(
                    if ((i and 4) != 0) 0.5 else -0.5,
                    if ((i and 2) != 0) 0.5 else -0.5,
                    if ((i and 1) != 0) 0.5 else -0.5))
            }
            for (a in 0 until 8) for (b in a + 1 until 8) {
                val d = a xor b
                if (d == 1 || d == 2 || d == 4) e.add(intArrayOf(a, b))
            }
        }

        private fun cyl(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>, n: Int) {
            for (i in 0 until n) {
                val a = 2.0 * Math.PI * i / n
                v.add(doubleArrayOf(0.5 * cos(a), -0.5, 0.5 * sin(a)))
            }
            for (i in 0 until n) {
                val a = 2.0 * Math.PI * i / n
                v.add(doubleArrayOf(0.5 * cos(a), 0.5, 0.5 * sin(a)))
            }
            for (i in 0 until n) {
                val j = (i + 1) % n
                e.add(intArrayOf(i, j))
                e.add(intArrayOf(n + i, n + j))
                if (n <= 8 || i % 2 == 0) e.add(intArrayOf(i, n + i))
            }
        }

        private fun cone(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>, n: Int) {
            for (i in 0 until n) {
                val a = 2.0 * Math.PI * i / n
                v.add(doubleArrayOf(0.5 * cos(a), -0.5, 0.5 * sin(a)))
            }
            v.add(doubleArrayOf(0.0, 0.5, 0.0))
            for (i in 0 until n) {
                e.add(intArrayOf(i, (i + 1) % n))
                if (n <= 8 || i % 2 == 0) e.add(intArrayOf(i, n))
            }
        }

        private fun sph(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>, n: Int) {
            val kk = 5
            v.add(doubleArrayOf(0.0, 0.5, 0.0))
            v.add(doubleArrayOf(0.0, -0.5, 0.0))
            for (k in 0 until kk) {
                val phi = Math.PI * (k + 1) / (kk + 1)
                val rr = 0.5 * sin(phi)
                val y = 0.5 * cos(phi)
                for (i in 0 until n) {
                    val a = 2.0 * Math.PI * i / n
                    v.add(doubleArrayOf(rr * cos(a), y, rr * sin(a)))
                }
            }
            for (k in 0 until kk) {
                for (i in 0 until n) {
                    val idx = 2 + k * n + i
                    e.add(intArrayOf(idx, 2 + k * n + (i + 1) % n))
                    if (n <= 8 || i % 2 == 0) {
                        if (k < kk - 1) e.add(intArrayOf(idx, 2 + (k + 1) * n + i))
                        if (k == 0) e.add(intArrayOf(0, idx))
                        if (k == kk - 1) e.add(intArrayOf(1, idx))
                    }
                }
            }
        }

        private fun pyr(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>) {
            v.add(doubleArrayOf(-0.5, -0.5, -0.5))
            v.add(doubleArrayOf(0.5, -0.5, -0.5))
            v.add(doubleArrayOf(0.5, -0.5, 0.5))
            v.add(doubleArrayOf(-0.5, -0.5, 0.5))
            v.add(doubleArrayOf(0.0, 0.5, 0.0))
            for (i in 0 until 4) {
                e.add(intArrayOf(i, (i + 1) % 4))
                e.add(intArrayOf(i, 4))
            }
        }

        private fun tor(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>, rBig: Double, rSmall: Double, mj: Int) {
            val mn = 8
            for (i in 0 until mj) {
                val u = 2.0 * Math.PI * i / mj
                for (j in 0 until mn) {
                    val w = 2.0 * Math.PI * j / mn
                    val rr = rBig + rSmall * cos(w)
                    v.add(doubleArrayOf(rr * cos(u), rSmall * sin(w), rr * sin(u)))
                }
            }
            for (i in 0 until mj) {
                for (j in 0 until mn) {
                    e.add(intArrayOf(i * mn + j, i * mn + (j + 1) % mn))
                    e.add(intArrayOf(i * mn + j, ((i + 1) % mj) * mn + j))
                }
            }
        }
    }
}
