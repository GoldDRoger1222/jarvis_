package com.example.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

class InventionActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var body: TextView
    private val tabTv = ArrayList<TextView>()
    private var cur: JSONObject? = null
    private var tab = 0
    private var gen = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Hud.bg
        window.navigationBarColor = Hud.bg
        val c = this
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Hud.bg)
        val p = Hud.dp(c, 14)
        root.setPadding(p, p, p, p)

        val head = LinearLayout(c)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(c)
        title.text = "INVENTION LAB"
        title.setTextColor(Hud.cyan)
        title.textSize = 20f
        title.letterSpacing = 0.2f
        title.typeface = Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Hud.btn(c, "AI \u2699") { Providers.show(c) })
        root.addView(head)

        input = Hud.edit(c, "Ki banate ba invent korte chao? (jemon: shoura chalito pani pump)", false, true)
        root.addView(input, Hud.lp(c, 10))
        root.addView(Hud.btn(c, "Banao", true) { start(input.text.toString()) }, Hud.lp(c, 8))
        status = TextView(c)
        status.setTextColor(Hud.cyan)
        status.textSize = 12f
        root.addView(status, Hud.lp(c, 6))

        val tr = LinearLayout(c)
        tr.orientation = LinearLayout.HORIZONTAL
        for ((i, n) in listOf("OVERVIEW", "PARTS", "PLAN", "CODE").withIndex()) {
            val tv = TextView(c)
            tv.text = n
            tv.textSize = 12f
            tv.gravity = Gravity.CENTER
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setPadding(0, Hud.dp(c, 10), 0, Hud.dp(c, 10))
            tv.setOnClickListener { tab = i; render() }
            tr.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            tabTv.add(tv)
        }
        root.addView(tr, Hud.lp(c, 8))

        body = TextView(c)
        body.setTextColor(Hud.txt)
        body.textSize = 14f
        body.setPadding(0, Hud.dp(c, 8), 0, Hud.dp(c, 8))
        val sv = ScrollView(c)
        sv.addView(body)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        val b1 = Hud.btn(c, "Hologram") { openHolo() }
        val b2 = Hud.btn(c, "Share") { share() }
        val b3 = Hud.btn(c, "Menu") { menu() }
        row.addView(b1, LinearLayout.LayoutParams(0, -2, 1f))
        val l2 = LinearLayout.LayoutParams(0, -2, 1f)
        l2.leftMargin = Hud.dp(c, 8)
        row.addView(b2, l2)
        val l3 = LinearLayout.LayoutParams(0, -2, 1f)
        l3.leftMargin = Hud.dp(c, 8)
        row.addView(b3, l3)
        root.addView(row, Hud.lp(c, 8))
        setContentView(root)

        val idea = intent.getStringExtra("idea")
        if (!idea.isNullOrBlank()) {
            input.setText(idea)
            start(idea)
        } else {
            val last = getSharedPreferences("j", MODE_PRIVATE).getString("inv_last", null)
            if (last != null) cur = Invention.load(this, last)
        }
        render()
    }

    private fun render() {
        for (i in tabTv.indices) {
            tabTv[i].setTextColor(if (i == tab) Hud.cyan else Hud.dim)
            tabTv[i].background = if (i == tab) Hud.box(this, Color.argb(30, 0, 229, 255), Hud.cyan, 8) else null
        }
        val p = cur
        body.typeface = if (tab == 3) Typeface.MONOSPACE else Typeface.DEFAULT
        body.textSize = if (tab == 3) 12f else 14f
        body.text = if (p == null) "Upore idea likhe Banao tap koro.\n\nVoice: 'Jarvis, ami ekta ... banate chai'"
        else when (tab) {
            0 -> Invention.overview(p)
            1 -> Invention.parts(p)
            2 -> Invention.plan(p)
            else -> Invention.code(p)
        }
    }

    private fun start(idea: String) {
        val d = idea.trim()
        if (d.isEmpty()) { status.text = "Idea likho"; return }
        val g = ++gen
        status.text = "Shuru hocche... (1-3 minute lagte pare)"
        thread {
            try {
                val p = Invention.generate(this, d) { s -> runOnUiThread { if (g == gen) status.text = s } }
                runOnUiThread {
                    if (g == gen) {
                        cur = p
                        tab = 0
                        status.text = "Toiri hoyeche (" + p.optString("provider") + ")"
                        render()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (g == gen) status.text = "Error: " + (e.message ?: "").take(200) }
            }
        }
    }

    private fun openHolo() {
        val p = cur ?: run { status.text = "Age project banao"; return }
        val h = p.optString("holo")
        if (h.isBlank()) {
            status.text = "Hologram nai: " + p.optString("holo_error") + " (Menu > Hologram abar banao)"
            return
        }
        getSharedPreferences("j", MODE_PRIVATE).edit().putString("hl", h).apply()
        startActivity(Intent(this, HologramActivity::class.java))
    }

    private fun share() {
        val p = cur ?: run { status.text = "Age project banao"; return }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, Invention.markdown(p))
        startActivity(Intent.createChooser(i, "Project share koro"))
    }

    private fun menu() {
        val items = arrayOf("Save .md to Downloads", "Copy code", "Purano project", "Hologram abar banao")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            val p = cur
            when (w) {
                0 -> if (p == null) status.text = "Age project banao" else try {
                    val r = Exporter.save(this, (p.optString("title", "project").replace(Regex("[^A-Za-z0-9_-]"), "_")
                        .take(30).ifBlank { "project" }) + ".md", Invention.markdown(p).toByteArray(), "text/markdown")
                    status.text = "Saved: " + r.path
                } catch (e: Exception) { status.text = "Save error: " + (e.message ?: "") }
                1 -> if (p == null || p.optString("code").isBlank()) status.text = "Code nai" else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("code", p.optString("code")))
                    status.text = "Code copy hoyeche"
                }
                2 -> history()
                else -> if (p == null) status.text = "Age project banao" else {
                    status.text = "Hologram toiri hocche..."
                    thread {
                        try {
                            val h = HoloGen.generate(this, p.optString("hologram", p.optString("idea")), null)
                            HoloModel.parse(h)
                            p.put("holo", h)
                            Invention.save(this, p)
                            runOnUiThread { status.text = "Hologram toiri, 'Hologram' tap koro" }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "Hologram error: " + (e.message ?: "").take(150) }
                        }
                    }
                }
            }
        }.show()
    }

    private fun history() {
        val names = Invention.list(this)
        if (names.isEmpty()) { status.text = "Kono purano project nai"; return }
        AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, w ->
            val p = Invention.load(this, names[w])
            if (p != null) {
                cur = p
                tab = 0
                getSharedPreferences("j", MODE_PRIVATE).edit().putString("inv_last", names[w]).apply()
                render()
            }
        }.show()
    }
}
