package com.example.jarvis

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

class HologramActivity : Activity() {
    private lateinit var holo: HoloView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var pyrBtn: Button
    private lateinit var spinBtn: Button
    private var lastJson = ""
    private var gen = 0
    private var chrome = true

    private fun mk(label: String, f: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wa = window.attributes
        wa.screenBrightness = 1f
        window.attributes = wa
        immersive()
        val pad = (10 * resources.displayMetrics.density).toInt()

        holo = HoloView(this)
        holo.onTap = { toggleChrome() }

        status = TextView(this).apply {
            setTextColor(Color.rgb(0, 229, 255))
            textSize = 14f
            text = "Jarvis Hologram"
        }
        input = EditText(this).apply {
            hint = "Ki banabo? (jemon: rocket engine, ironman helmet)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val go = mk("Banao") { build(input.text.toString(), false) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(go)
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(row)
        }

        pyrBtn = mk("Pyramid: OFF") {
            holo.pyramid = !holo.pyramid
            pyrBtn.text = if (holo.pyramid) "Pyramid: ON" else "Pyramid: OFF"
            if (holo.pyramid && chrome) toggleChrome()
        }
        val flipBtn = mk("Flip") { holo.flip = !holo.flip }
        spinBtn = mk("Spin: ON") {
            holo.spin = !holo.spin
            spinBtn.text = if (holo.spin) "Spin: ON" else "Spin: OFF"
        }
        val editBtn = mk("Bodlao") { build(input.text.toString(), true) }
        val closeBtn = mk("X") { finish() }
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            for (v in listOf(pyrBtn, flipBtn, spinBtn, editBtn, closeBtn))
                addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(holo, FrameLayout.LayoutParams(-1, -1))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        val saved = getSharedPreferences("j", MODE_PRIVATE).getString("hl", "") ?: ""
        var shown = false
        if (saved.isNotEmpty()) {
            try {
                holo.model = HoloModel.parse(saved)
                lastJson = saved
                shown = true
            } catch (e: Exception) {
            }
        }
        if (!shown) {
            val d = demo()
            holo.model = HoloModel.parse(d)
            lastJson = d
        }
        status.text = (holo.model?.name ?: "") + "  |  tap: menu"
        handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    private fun handle(i: Intent?) {
        val d = i?.getStringExtra("desc")
        if (!d.isNullOrBlank()) {
            input.setText(d)
            build(d, i.getBooleanExtra("modify", false))
        }
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onResume() {
        super.onResume()
        immersive()
    }

    private fun toggleChrome() {
        chrome = !chrome
        val v = if (chrome) View.VISIBLE else View.GONE
        top.visibility = v
        bottom.visibility = v
    }

    private fun build(desc: String, modify: Boolean) {
        val d = desc.trim()
        if (d.isEmpty()) { status.text = "Ki banabo likho"; return }
        val g = ++gen
        status.text = "Toiri hocche... (10-40 second)"
        val prev = if (modify && lastJson.isNotEmpty()) lastJson else null
        thread {
            try {
                var model: HoloModel? = null
                var json = ""
                var err: Exception? = null
                for (t in 1..2) {
                    try {
                        json = HoloGen.generate(this, d, prev)
                        model = HoloModel.parse(json)
                        break
                    } catch (e: Exception) {
                        err = e
                    }
                }
                val mm = model ?: throw (err ?: Exception("Model hoyni"))
                runOnUiThread {
                    if (g == gen) {
                        lastJson = json
                        getSharedPreferences("j", MODE_PRIVATE).edit().putString("hl", json).apply()
                        holo.model = mm
                        status.text = mm.name + "  |  " + mm.edgeCount + " lines  |  tap: menu"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (g == gen) status.text = "Error: " + (e.message ?: "").take(160)
                }
            }
        }
    }

    private fun demo(): String {
        val parts = JSONArray()
        parts.put(JSONObject().put("t", "tor").put("s", JSONArray(listOf(1.8, 0.22)))
            .put("r", JSONArray(listOf(90.0, 0.0, 0.0))))
        parts.put(JSONObject().put("t", "tor").put("s", JSONArray(listOf(1.2, 0.14)))
            .put("r", JSONArray(listOf(90.0, 0.0, 0.0))))
        parts.put(JSONObject().put("t", "sph").put("s", JSONArray(listOf(0.45, 0.45, 0.45))))
        for (i in 0 until 10) {
            val deg = i * 36.0
            val a = Math.toRadians(deg)
            parts.put(JSONObject().put("t", "box")
                .put("p", JSONArray(listOf(0.75 * cos(a), 0.75 * sin(a), 0.0)))
                .put("s", JSONArray(listOf(0.3, 0.08, 0.08)))
                .put("r", JSONArray(listOf(0.0, 0.0, deg))))
        }
        return JSONObject().put("name", "Arc Reactor").put("parts", parts).toString()
    }
}
