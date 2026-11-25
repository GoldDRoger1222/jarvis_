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

        gemBox = field("Gemini API key", "k", true)
        nickBox = field("Jarvis tomake ki bole dakbe? (jemon Boss)", "n")
        personaBox = field("Personality (jemon moja kore, bondhur moto)", "ps")
        val c1 = card("AI")
        c1.addView(gemBox, full(10))
        c1.addView(nickBox, full(8))
        c1.addView(personaBox, full(8))
        c1.addView(hudBtn("AI Providers (OpenRouter, Live voice)") { Providers.show(this) }, full(8))
        l.addView(c1, full(6))

        langBtn = hudBtn("") { toggleLang() }
        wakeBtn = hudBtn("") { toggleWake() }
        rateBtn = hudBtn("") { cycle("sr") }
        pitchBtn = hudBtn("") { cycle("pt") }
        val c2 = card("VOICE")
        c2.addView(langBtn, full(10))
        c2.addView(wakeBtn, full(8))
        c2.addView(rateBtn, full(8))
        c2.addView(pitchBtn, full(8))
        c2.addView(hudBtn("Awaj (male/female) bodlao") { openTts() }, full(8))
        l.addView(c2, full(12))

        lockBtn = hudBtn("") { toggleLock() }
        strictBtn = hudBtn("") { cycle("vs") }
        oneBtn = hudBtn("") { toggleOne() }
        tbBtn = hudBtn("") { toggleTb() }
        val c3 = card("SECURITY")
        c3.addView(hudBtn("Amar voice shekhao (8 bar Jarvis + 5 lomba bakko)") { go("ENROLL") }, full(10))
        c3.addView(lockBtn, full(8))
        c3.addView(strictBtn, full(8))
        c3.addView(oneBtn, full(8))
        c3.addView(tbBtn, full(8))
        l.addView(c3, full(12))

        val c4 = card("PERMISSIONS (ekbar koro)")
        c4.addView(hudBtn("1. Battery optimization bondho koro") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, full(10))
        c4.addView(hudBtn("2. Accessibility chalu koro") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, full(8))
        c4.addView(hudBtn("3. Display over other apps dao") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, full(8))
        c4.addView(hudBtn("4. App permission (contacts, call, sms)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, full(8))
        l.addView(c4, full(12))
        return scroll(l)
    }

    // ---------- Actions ----------

    private fun openTts() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun toggleLang() {
        val n = when (prefs().getString("l", "bn-BD")) { "bn-BD" -> "en-IN"; "en-IN" -> "en-US"; else -> "bn-BD" }
        prefs().edit().putString("l", n).apply()
        refreshBtns()
    }

    private fun toggleWake() {
        val n = if (prefs().getString("w", "v") == "v") "g" else "v"
        prefs().edit().putString("w", n).apply()
        refreshBtns()
    }

    private fun toggleLock() {
        if (!prefs().contains("vp")) {
            statusTv.text = "Age 'Amar voice shekhao' tap koro (SETTINGS)"
            showTab(0)
            return
        }
        prefs().edit().putBoolean("vl", !prefs().getBoolean("vl", false)).apply()
        Toast.makeText(this, "Jarvis bondho kore abar chalu koro", Toast.LENGTH_LONG).show()
        refreshBtns()
    }

    private fun toggleOne() {
        prefs().edit().putBoolean("oc", !prefs().getBoolean("oc", false)).apply()
        refreshBtns()
    }

    private fun toggleTb() {
        prefs().edit().putBoolean("tb", !prefs().getBoolean("tb", false)).apply()
        refreshBtns()
    }

    private fun cycle(k: String) {
        prefs().edit().putInt(k, (prefs().getInt(k, 1) + 1) % 3).apply()
        refreshBtns()
    }

    private fun refreshBtns() {
        val langName = when (prefs().getString("l", "bn-BD")) {
            "bn-BD" -> "Bangla"
            "en-IN" -> "English (India accent)"
            else -> "English (US)"
        }
        val lockOn = prefs().contains("vp") && prefs().getBoolean("vl", false)
        val offline = prefs().getString("w", "v") == "v"
        langBtn.text = "Bhasha: $langName"
        wakeBtn.text = if (offline) "Wake: Offline (beep nai, English Jarvis)"
        else "Wake: Online (Google, Bangla-o cholbe, voice lock cholbe na)"
        rateBtn.text = "Gotir: " + rateNames[prefs().getInt("sr", 1).coerceIn(0, 2)]
        pitchBtn.text = "Awajer sur: " + pitchNames[prefs().getInt("pt", 1).coerceIn(0, 2)]
        lockBtn.text = when {
            !prefs().contains("vp") -> "Voice lock: OFF (age voice shekhao)"
            lockOn -> "Voice lock: ON (shudhu amar voice)"
            else -> "Voice lock: OFF"
        }
        strictBtn.text = "Lock strictness: " + strictNames[prefs().getInt("vs", 1).coerceIn(0, 2)]
        oneBtn.text = if (prefs().getBoolean("oc", false)) "Ek wake e ek kaj: ON" else "Ek wake e ek kaj: OFF"
        tbBtn.text = if (prefs().getBoolean("tb", false)) "TALK button e voice check: OFF (nijer phone, shohoj)"
        else "TALK button e voice check: ON (nirapod)"
        chipTv.text = "Voice lock " + (if (lockOn) "ON" else "OFF") + "  •  " +
            (if (offline) "Offline wake" else "Online wake") + "  •  " + langName
    }

    private fun refresh() {
        startBtn.text = if (JarvisService.running) "Jarvis Bondho Koro" else "Jarvis Chalu Koro"
    }

    private fun applyStatus(s: String) {
        statusTv.text = s
        val l = s.lowercase()
        val now = System.currentTimeMillis()
        if (l.contains("chena na")) alertUntil = now + 2500
        orb.state = when {
            !JarvisService.running -> OrbView.OFF
            now < alertUntil -> OrbView.ALERT
            l.contains("dak shunchi") -> OrbView.IDLE
            l.contains("shunchi") || l.contains("haan bolo") || l.startsWith("bolo") -> OrbView.LISTEN
            l.contains("bolchi") -> OrbView.SPEAK
            l.contains("bhabchi") || l.contains("bujhchi") || l.contains("voice check") ||
                l.contains("kaj korchi") || l.contains("opekkha") -> OrbView.THINK
            else -> OrbView.IDLE
        }
    }

    private fun saveFields() {
        prefs().edit()
            .putString("k", gemBox.text.toString().trim())
            .putString("n", nickBox.text.toString().trim())
            .putString("ps", personaBox.text.toString().trim())
            .apply()
    }

    private fun missing(p: String) = checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED

    private fun go(action: String?) {
        saveFields()
        if (action != "ENROLL" && prefs().getString("k", "").isNullOrEmpty()) {
            statusTv.text = "Gemini key dao (SETTINGS tab)"
            showTab(2)
            return
        }
        val need = mutableListOf<String>()
        if (missing(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        val optional = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) optional.add("android.permission.POST_NOTIFICATIONS")
        for (p in optional) if (missing(p)) need.add(p)
        if (need.isNotEmpty()) {
            pending = action
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        startJarvis(action)
    }

    private fun startJarvis(action: String?) {
        val i = Intent(this, JarvisService::class.java)
        if (action != null) i.action = action
        startForegroundService(i)
        showTab(0)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (missing(Manifest.permission.RECORD_AUDIO)) {
            statusTv.text = "Mic permission dao (SETTINGS > 4. App permission)"
            return
        }
        val denied = perms.filterIndexed { i, _ -> res[i] != PackageManager.PERMISSION_GRANTED }
            .map { it.substringAfterLast('.') }
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Deoa hoyni: " + denied.joinToString() +
                ". SETTINGS > 4. App permission diye dao. Baki kaj cholbe.", Toast.LENGTH_LONG).show()
        }
        startJarvis(pending)
    }

    // ---------- Hologram ----------

    private fun build(desc: String, modify: Boolean) {
        val d = desc.trim()
        if (d.isEmpty()) { holoStatus.text = "Ki banabo likho"; return }
        val g = ++gen
        holoStatus.text = "Toiri hocche... (10-40 second)"
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
                val finalJson = json
                runOnUiThread {
                    if (g == gen) {
                        lastJson = finalJson
                        prefs().edit().putString("hl", finalJson).apply()
                        holo.model = mm
                        holoStatus.text = mm.name + "  |  " + mm.edgeCount + " lines"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (g == gen) holoStatus.text = "Error: " + (e.message ?: "").take(160)
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

    // ---------- Lifecycle ----------

    override fun onResume() {
        super.onResume()
        JarvisService.onLog = { s -> log.append(s) }
        JarvisService.onStatus = { s -> applyStatus(s); refresh() }
        log.text = JarvisService.logBuf.toString()
        val st = JarvisService.statusText
        applyStatus(if (st.isEmpty()) "Jarvis ghumay. START tap koro" else st)
        refresh()
        refreshBtns()
        val saved = prefs().getString("hl", "") ?: ""
        if (saved.isNotEmpty() && saved != lastJson) {
            try {
                holo.model = HoloModel.parse(saved)
                lastJson = saved
                holoStatus.text = (holo.model?.name ?: "") + "  |  " + (holo.model?.edgeCount ?: 0) + " lines"
            } catch (e: Exception) {
            }
        }
    }

    override fun onPause() {
        saveFields()
        JarvisService.onLog = null
        JarvisService.onStatus = null
        super.onPause()
    }
}
