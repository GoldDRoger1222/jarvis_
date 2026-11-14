package com.example.jarvis

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HoloModel(val name: String, val verts: FloatArray, val edges: IntArray) {
    val edgeCount: Int get() = edges.size / 2

    companion object {
        private const val N = 16

        fun parse(text: String): HoloModel {
            val s = text.indexOf('{')
            val e = text.lastIndexOf('}')
            if (s < 0 || e <= s) throw Exception("Reply te model nai")
            val o = JSONObject(text.substring(s, e + 1))
            val parts = o.optJSONArray("parts") ?: throw Exception("parts nai")
            val vs = ArrayList<DoubleArray>()
            val es = ArrayList<IntArray>()
            for (i in 0 until minOf(parts.length(), 80)) {
                val p = parts.optJSONObject(i) ?: continue
                try { addPart(p, vs, es) } catch (ex: Exception) { }
            }
            if (vs.size < 3 || es.isEmpty()) throw Exception("Model faka")
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
            val ne = minOf(es.size, 6000)
            val ef = IntArray(ne * 2)
            for (i in 0 until ne) {
                ef[2 * i] = es[i][0]
                ef[2 * i + 1] = es[i][1]
            }
            return HoloModel(o.optString("name", "model").take(40), vf, ef)
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
            val lv = ArrayList<DoubleArray>()
            val le = ArrayList<IntArray>()
            when (t) {
                "box" -> box(lv, le)
                "cyl", "cylinder" -> cyl(lv, le)
                "cone" -> cone(lv, le)
                "sph", "sphere" -> sph(lv, le)
                "pyr", "pyramid" -> pyr(lv, le)
                "tor", "torus" -> {
                    val outer = maxOf(sz[0], 0.02)
                    val tube = minOf(maxOf(sz[1], 0.01), outer * 0.9)
                    tor(lv, le, (outer - tube) / 2, tube / 2)
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

        private fun cyl(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>) {
            for (i in 0 until N) {
                val a = 2.0 * Math.PI * i / N
                v.add(doubleArrayOf(0.5 * cos(a), -0.5, 0.5 * sin(a)))
            }
            for (i in 0 until N) {
                val a = 2.0 * Math.PI * i / N
                v.add(doubleArrayOf(0.5 * cos(a), 0.5, 0.5 * sin(a)))
            }
            for (i in 0 until N) {
                val j = (i + 1) % N
                e.add(intArrayOf(i, j))
                e.add(intArrayOf(N + i, N + j))
                if (i % 2 == 0) e.add(intArrayOf(i, N + i))
            }
        }

        private fun cone(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>) {
            for (i in 0 until N) {
                val a = 2.0 * Math.PI * i / N
                v.add(doubleArrayOf(0.5 * cos(a), -0.5, 0.5 * sin(a)))
            }
            v.add(doubleArrayOf(0.0, 0.5, 0.0))
            for (i in 0 until N) {
                e.add(intArrayOf(i, (i + 1) % N))
                if (i % 2 == 0) e.add(intArrayOf(i, N))
            }
        }

        private fun sph(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>) {
            val kk = 5
            v.add(doubleArrayOf(0.0, 0.5, 0.0))
            v.add(doubleArrayOf(0.0, -0.5, 0.0))
            for (k in 0 until kk) {
                val phi = Math.PI * (k + 1) / (kk + 1)
                val rr = 0.5 * sin(phi)
                val y = 0.5 * cos(phi)
                for (i in 0 until N) {
                    val a = 2.0 * Math.PI * i / N
                    v.add(doubleArrayOf(rr * cos(a), y, rr * sin(a)))
                }
            }
            for (k in 0 until kk) {
                for (i in 0 until N) {
                    val idx = 2 + k * N + i
                    e.add(intArrayOf(idx, 2 + k * N + (i + 1) % N))
                    if (i % 2 == 0) {
                        if (k < kk - 1) e.add(intArrayOf(idx, 2 + (k + 1) * N + i))
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

        private fun tor(v: ArrayList<DoubleArray>, e: ArrayList<IntArray>, rBig: Double, rSmall: Double) {
            val mj = 20
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
