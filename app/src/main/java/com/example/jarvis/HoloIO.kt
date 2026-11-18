package com.example.jarvis

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object ProjectStore {
    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "holo")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun clean(name: String): String =
        name.trim().replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").take(40).ifBlank { "model" }

    fun save(ctx: Context, name: String, json: String): String {
        val n = clean(name)
        File(dir(ctx), "$n.json").writeText(json)
        return n
    }

    fun list(ctx: Context): List<String> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(".json") }
    }

    fun load(ctx: Context, name: String): String? {
        val f = File(dir(ctx), clean(name) + ".json")
        return if (f.exists()) f.readText() else null
    }

    fun delete(ctx: Context, name: String): Boolean = File(dir(ctx), clean(name) + ".json").delete()

    fun find(ctx: Context, name: String): String? {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        val all = list(ctx)
        return all.firstOrNull { it.lowercase() == q }
            ?: all.firstOrNull { it.lowercase().contains(q) || q.contains(it.lowercase()) }
    }
}

class SavedFile(val path: String, val uri: Uri?)

object Exporter {
    fun save(ctx: Context, name: String, bytes: ByteArray, mime: String): SavedFile {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JarvisHolo")
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw Exception("Downloads e file banate parlam na")
            val os = ctx.contentResolver.openOutputStream(uri) ?: throw Exception("File likhte parlam na")
            os.use { it.write(bytes) }
            var shown = name
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) shown = it.getString(0)
            }
            return SavedFile("Download/JarvisHolo/$shown", uri)
        }
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, name)
        f.writeBytes(bytes)
        return SavedFile(f.absolutePath, null)
    }

    fun share(ctx: Context, uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val ch = Intent.createChooser(i, "STL share koro")
        ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(ch)
    }
}

object Stl {
    fun build(m: HoloModel, sizeMm: Float): ByteArray {
        val v = m.verts
        val n = v.size / 3
        var mnx = 1e9f
        var mny = 1e9f
        var mnz = 1e9f
        var mxx = -1e9f
        var mxy = -1e9f
        var mxz = -1e9f
        for (i in 0 until n) {
            val x = v[3 * i]
            val y = v[3 * i + 1]
            val z = v[3 * i + 2]
            if (x < mnx) mnx = x
            if (y < mny) mny = y
            if (z < mnz) mnz = z
            if (x > mxx) mxx = x
            if (y > mxy) mxy = y
            if (z > mxz) mxz = z
        }
        val ext = maxOf(mxx - mnx, mxy - mny, mxz - mnz, 1e-6f)
        val k = sizeMm / ext
        val cx = (mnx + mxx) / 2
        val cz = (mnz + mxz) / 2
        val nt = m.tris.size / 3
        val bb = ByteBuffer.allocate(84 + nt * 50).order(ByteOrder.LITTLE_ENDIAN)
        val head = ("JARVIS HOLOGRAM " + m.name).toByteArray(Charsets.US_ASCII)
        bb.put(head, 0, minOf(80, head.size))
        bb.position(80)
        bb.putInt(nt)
        val p = FloatArray(9)
        for (q in 0 until nt) {
            for (j in 0..2) {
                val i = m.tris[3 * q + j]
                p[3 * j] = (v[3 * i] - cx) * k
                p[3 * j + 1] = -(v[3 * i + 2] - cz) * k
                p[3 * j + 2] = (v[3 * i + 1] - mny) * k
            }
            val ux = p[3] - p[0]
            val uy = p[4] - p[1]
            val uz = p[5] - p[2]
            val wx = p[6] - p[0]
            val wy = p[7] - p[1]
            val wz = p[8] - p[2]
            var nx = uy * wz - uz * wy
            var ny = uz * wx - ux * wz
            var nz = ux * wy - uy * wx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) {
                nx /= len
                ny /= len
                nz /= len
            }
            bb.putFloat(nx)
            bb.putFloat(ny)
            bb.putFloat(nz)
            for (j in 0..8) bb.putFloat(p[j])
            bb.putShort(0)
        }
        return bb.array()
    }
}

object HoloTools {
    private fun dp(ctx: Context, x: Int) = (x * ctx.resources.displayMetrics.density).toInt()

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    private fun hud(ctx: Context, title: String): Pair<Dialog, LinearLayout> {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 16)
        box.setPadding(pad, pad, pad, pad)
        val bg = GradientDrawable()
        bg.setColor(Color.argb(245, 6, 12, 22))
        bg.cornerRadius = dp(ctx, 20).toFloat()
        bg.setStroke(maxOf(1, dp(ctx, 1)), Color.argb(150, 0, 229, 255))
        box.background = bg
        val t = TextView(ctx)
        t.text = title
        t.setTextColor(Color.rgb(0, 229, 255))
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.15f
        box.addView(t)
        d.setContentView(box)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout((ctx.resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        return Pair(d, box)
    }

    private fun btn(ctx: Context, label: String, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.rgb(0, 229, 255))
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        val bb = GradientDrawable()
        bb.setColor(Color.argb(40, 0, 229, 255))
        bb.cornerRadius = dp(ctx, 12).toFloat()
        bb.setStroke(maxOf(1, dp(ctx, 1)), Color.rgb(0, 229, 255))
        b.background = bb
        b.setOnClickListener { onClick() }
        return b
    }

    private fun lp(ctx: Context): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(-1, -2)
        l.topMargin = dp(ctx, 8)
        return l
    }

    private fun input(ctx: Context, title: String, hint: String, def: String, numeric: Boolean,
                      onOk: (String) -> Unit) {
        val (d, box) = hud(ctx, title)
        val e = EditText(ctx)
        e.hint = hint
        e.setText(def)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.GRAY)
        e.setSingleLine(true)
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        box.addView(e, lp(ctx))
        box.addView(btn(ctx, "OK") { d.dismiss(); onOk(e.text.toString()) }, lp(ctx))
        d.show()
    }

    fun show(v: HoloView) {
        val ctx = v.context
        val (d, box) = hud(ctx, "HOLOGRAM TOOLS")
        box.addView(btn(ctx, "Save project...") {
            d.dismiss()
            val cur = v.currentJson()
            if (cur == null) toast(ctx, "Age ekta model banao")
            else input(ctx, "SAVE PROJECT", "Project er nam", v.model?.name ?: "model", false) { n ->
                toast(ctx, "Saved: " + ProjectStore.save(ctx, n, cur))
            }
        }, lp(ctx))
        box.addView(btn(ctx, "Load project...") { d.dismiss(); loadDialog(v) }, lp(ctx))
        box.addView(btn(ctx, "Export STL (100 mm) to Downloads") {
            d.dismiss()
            toast(ctx, v.exportStl(100f, false))
        }, lp(ctx))
        box.addView(btn(ctx, "Export STL + Share") {
            d.dismiss()
            toast(ctx, v.exportStl(100f, true))
        }, lp(ctx))
        box.addView(btn(ctx, "STL custom size (mm)...") {
            d.dismiss()
            input(ctx, "STL SIZE (mm)", "lomba dik koto mm", "100", true) { s ->
                toast(ctx, v.exportStl(s.toFloatOrNull() ?: 100f, false))
            }
        }, lp(ctx))
        box.addView(btn(ctx, "Undo") { d.dismiss(); toast(ctx, v.undo()) }, lp(ctx))
        box.addView(btn(ctx, "Redo") { d.dismiss(); toast(ctx, v.redo()) }, lp(ctx))
        box.addView(btn(ctx, "View reset") { d.dismiss(); v.resetView() }, lp(ctx))
        d.show()
    }

    private fun loadDialog(v: HoloView) {
        val ctx = v.context
        val names = ProjectStore.list(ctx)
        if (names.isEmpty()) {
            toast(ctx, "Kono saved project nai")
            return
        }
        val (d, box) = hud(ctx, "LOAD PROJECT")
        val sv = ScrollView(ctx)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        sv.addView(col)
        for (n in names) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            val lb = btn(ctx, n) {
                d.dismiss()
                val js = ProjectStore.load(ctx, n)
                toast(ctx, if (js == null) "File porte parlam na" else v.loadJson(js))
            }
            val del = btn(ctx, "X") {
                ProjectStore.delete(ctx, n)
                d.dismiss()
                toast(ctx, "Deleted " + n)
            }
            row.addView(lb, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(del, LinearLayout.LayoutParams(-2, -2))
            col.addView(row, lp(ctx))
        }
        box.addView(sv, LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()))
        d.show()
    }
}
