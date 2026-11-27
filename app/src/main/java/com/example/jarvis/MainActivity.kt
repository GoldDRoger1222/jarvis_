package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity() {
    private val bgC = Color.rgb(5, 8, 15)
    private val cardC = Color.rgb(11, 18, 32)
    private val cyan = Color.rgb(0, 229, 255)
    private val dimC = Color.rgb(110, 140, 155)
    private val txtC = Color.rgb(216, 243, 255)

    private lateinit var orb: OrbView
    private lateinit var statusTv: TextView
    private lateinit var chipTv: TextView
    private lateinit var log: TextView
    private lateinit var startBtn: Button
    private lateinit var gemBox: EditText
    private lateinit var nickBox: EditText
    private lateinit var personaBox: EditText
    private lateinit var langBtn: Button
    private lateinit var wakeBtn: Button
    private lateinit var rateBtn: Button
    private lateinit var pitchBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var strictBtn: Button
    private lateinit var oneBtn: Button
    private lateinit var tbBtn: Button
    private lateinit var holo: HoloView
    private lateinit var holoStatus: TextView
    private lateinit var holoInput: EditText
    private val pages = ArrayList<View>()
    private val tabTv = ArrayList<TextView>()
    private val tabBar = ArrayList<View>()
    private var lastJson = ""
    private var gen = 0
    private var pending: String? = null
    private var alertUntil = 0L
    private val rateNames = arrayOf("Dhire", "Normal", "Druto")
    private val pitchNames = arrayOf("Mota", "Normal", "Sharu")
    private val strictNames = arrayOf("Lenient (shohoje chine)", "Normal", "Strict (kothin)")

    private fun prefs() = Secure.prefs(this)
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    // ---------- UI helpers ----------

    private fun box(fill: Int, stroke: Int, radius: Int, sw: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        g.setStroke(dp(sw), stroke)
        return g
    }

    private fun full(top: Int = 8): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(-1, -2)
        l.topMargin = dp(top)
        return l
    }

    private fun half(left: Int = 0): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(0, -2, 1f)
        l.leftMargin = dp(left)
        l.topMargin = dp(8)
        return l
    }

    private fun hudBtn(label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(if (primary) Color.WHITE else cyan)
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        b.background = box(
            if (primary) Color.argb(110, 0, 229, 255) else Color.argb(28, 0, 229, 255), cyan, 12, 1)
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = box(cardC, Color.argb(60, 0, 229, 255), 16)
        c.setPadding(dp(14), dp(12), dp(14), dp(14))
        val t = TextView(this)
        t.text = title
        t.setTextColor(cyan)
        t.textSize = 12f
        t.letterSpacing = 0.2f
        t.typeface = Typeface.DEFAULT_BOLD
        c.addView(t)
        return c
    }

    private fun field(h: String, key: String, secret: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = h
        e.setHintTextColor(dimC)
        e.setTextColor(txtC)
        e.textSize = 14f
        e.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        e.background = box(Color.argb(40, 0, 0, 0), Color.argb(90, 0, 229, 255), 10)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.setText(prefs().getString(key, ""))
        return e
    }

    private fun info(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 12f
        t.setTextColor(dimC)
        return t
    }

    private fun pageLayout(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(14), dp(10), dp(14), dp(24))
        return l
    }

    private fun scroll(l: View): ScrollView {
        val s = ScrollView(this)
        s.addView(l)
        return s
    }

    // ---------- Screen ----------

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = bgC
        window.navigationBarColor = bgC
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgC)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(16), dp(14), dp(16), dp(4))
        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.textSize = 24f
        title.setTextColor(cyan)
        title.letterSpacing = 0.35f
        title.typeface = Typeface.DEFAULT_BOLD
        val sub = TextView(this)
        sub.text = "Just A Rather Very Intelligent System"
        sub.textSize = 11f
        sub.setTextColor(dimC)
        head.addView(title)
        head.addView(sub)
        root.addView(head)

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(dp(8), 0, dp(8), 0)
        val names = listOf("JARVIS", "HOLOGRAM", "SETTINGS")
        for ((i, n) in names.withIndex()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val tv = TextView(this)
            tv.text = n
            tv.textSize = 13f
            tv.letterSpacing = 0.15f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, dp(12), 0, dp(8))
            val bar = View(this)
            col.addView(tv, LinearLayout.LayoutParams(-1, -2))
            col.addView(bar, LinearLayout.LayoutParams(-1, dp(2)))
            col.setOnClickListener { showTab(i) }
            tabs.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            tabTv.add(tv)
            tabBar.add(bar)
        }
        root.addView(tabs)
        val line = View(this)
        line.setBackgroundColor(Color.argb(50, 0, 229, 255))
        root.addView(line, LinearLayout.LayoutParams(-1, dp(1)))

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        pages.add(buildJarvisPage())
        pages.add(buildHoloPage())
        pages.add(buildSettingsPage())
        for (p in pages) frame.addView(p, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        showTab(0)
        refreshBtns()
    }

    private fun showTab(i: Int) {
        for (k in pages.indices) {
            pages[k].visibility = if (k == i) View.VISIBLE else View.GONE
            tabTv[k].setTextColor(if (k == i) cyan else dimC)
            tabBar[k].setBackgroundColor(if (k == i) cyan else Color.TRANSPARENT)
        }
    }

    private fun buildJarvisPage(): View {
        val l = pageLayout()
        orb = OrbView(this)
        l.addView(orb, LinearLayout.LayoutParams(-1, dp(260)))

        statusTv = TextView(this)
        statusTv.gravity = Gravity.CENTER
        statusTv.setTextColor(cyan)
        statusTv.textSize = 15f
        statusTv.typeface = Typeface.DEFAULT_BOLD
        l.addView(statusTv, full(4))

        chipTv = TextView(this)
        chipTv.gravity = Gravity.CENTER
        chipTv.setTextColor(dimC)
        chipTv.textSize = 11f
        l.addView(chipTv, full(2))

        startBtn = hudBtn("", true) {
            if (JarvisService.running) stopService(Intent(this, JarvisService::class.java))
            else go(null)
        }
        startBtn.textSize = 16f
        val talk = hudBtn("Ekhoni kotha bolo") { go("TALK") }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(startBtn, half())
        row.addView(talk, half(10))
        l.addView(row, full(10))

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(hudBtn("Jarvis Live") { startActivity(Intent(this, LiveActivity::class.java)) }, half())
        row2.addView(hudBtn("Invention Lab") { startActivity(Intent(this, InventionActivity::class.java)) }, half(10))
        l.addView(row2, full(0))
        l.addView(hudBtn("Research Lab") { startActivity(Intent(this, LabActivity::class.java)) }, full(8))

        val c = card("TRANSCRIPT")
        log = TextView(this)
        log.setTextColor(txtC)
        log.textSize = 14f
        log.setPadding(0, dp(8), 0, 0)
        c.addView(log)
        l.addView(c, full(14))
        return scroll(l)
    }

    private fun buildHoloPage(): View {
        val l = pageLayout()
        val c = card("3D HOLOGRAM")
        holoStatus = TextView(this)
        holoStatus.setTextColor(cyan)
        holoStatus.textSize = 13f
        holoStatus.setPadding(0, dp(6), 0, dp(6))
        c.addView(holoStatus)

        holo = HoloView(this)
        holo.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        c.addView(holo, LinearLayout.LayoutParams(-1, dp(320)))

        holoInput = field("Ki banabo? (jemon: rocket engine, ironman helmet)", "_holo")
        c.addView(holoInput, full(10))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(hudBtn("Banao", true) { build(holoInput.text.toString(), false) }, half())
        row.addView(hudBtn("Bodlao") { build(holoInput.text.toString(), true) }, half(10))
        c.addView(row, full(4))
        val fs = hudBtn("Fullscreen / Pyramid mode") { startActivity(Intent(this, HologramActivity::class.java)) }
        c.addView(fs, full(10))
        l.addView(c, full(6))
        l.addView(info("Voice: 'Jarvis, ekta rocket engine er hologram banao', tarpor 'Jarvis, ota boro koro'."), full(10))

        val saved = prefs().getString("hl", "") ?: ""
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
        holoStatus.text = (holo.model?.name ?: "") + "  |  " + (holo.model?.edgeCount ?: 0) + " lines"
        return scroll(l)
    }

    private fun buildSettingsPage(): View {
        val l = pageLayout()

        gemBox = field("Gemini API key", "k",
