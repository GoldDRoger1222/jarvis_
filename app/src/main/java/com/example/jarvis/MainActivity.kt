package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var gemBox: EditText
    private lateinit var nickBox: EditText
    private lateinit var personaBox: EditText
    private lateinit var langBtn: Button
    private lateinit var wakeBtn: Button
    private lateinit var rateBtn: Button
    private lateinit var pitchBtn: Button
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var log: TextView
    private var pending: String? = null
    private var askedExtra = false
    private val rateNames = arrayOf("Dhire", "Normal", "Druto")
    private val pitchNames = arrayOf("Mota", "Normal", "Sharu")

    private fun prefs() = getSharedPreferences("j", MODE_PRIVATE)

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun field(h: String, key: String, secret: Boolean = false) = EditText(this).apply {
        hint = h
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        setText(prefs().getString(key, ""))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        gemBox = field("Gemini API key", "k", true)
        nickBox = field("Jarvis tomake ki bole dakbe? (jemon Boss)", "n")
        personaBox = field("Jarvis er personality (jemon moja kore, bondhur moto)", "ps")
        langBtn = btn("") { toggleLang() }
        wakeBtn = btn("") { toggleWake() }
        rateBtn = btn("") { cycle("sr") }
        pitchBtn = btn("") { cycle("pt") }
        startBtn = btn("") {
            if (JarvisService.running) stopService(Intent(this, JarvisService::class.java))
            else go(null)
        }
        startBtn.textSize = 18f
        val talkBtn = btn("Ekhoni kotha bolo") { go("TALK") }
        val ttsBtn = btn("Awaj (male/female) bodlao") { openTts() }
        val batBtn = btn("1. Battery optimization bondho koro") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        val accBtn = btn("2. Accessibility chalu koro") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val ovBtn = btn("3. Display over other apps dao") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        status = TextView(this).apply { textSize = 16f }
        log = TextView(this).apply { textSize = 15f }
        listOf(gemBox, nickBox, personaBox, startBtn, talkBtn, langBtn, wakeBtn, rateBtn,
            pitchBtn, ttsBtn, batBtn, accBtn, ovBtn, status, log).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
        refreshBtns()
    }

    private fun openTts() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun toggleLang() {
        val n = if (prefs().getString("l", "bn-BD") == "bn-BD") "en-US" else "bn-BD"
        prefs().edit().putString("l", n).apply()
        refreshBtns()
    }

    private fun toggleWake() {
        val n = if (prefs().getString("w", "v") == "v") "g" else "v"
        prefs().edit().putString("w", n).apply()
        refreshBtns()
    }

    private fun cycle(k: String) {
        prefs().edit().putInt(k, (prefs().getInt(k, 1) + 1) % 3).apply()
        refreshBtns()
    }

    private fun refreshBtns() {
        langBtn.text = if (prefs().getString("l", "bn-BD") == "bn-BD") "Bhasha: Bangla" else "Bhasha: English"
        wakeBtn.text = if (prefs().getString("w", "v") == "v") "Wake: Offline (beep nai, English Jarvis)"
        else "Wake: Online (Google, Bangla-o cholbe)"
        rateBtn.text = "Gotir: " + rateNames[prefs().getInt("sr", 1).coerceIn(0, 2)]
        pitchBtn.text = "Awajer sur: " + pitchNames[prefs().getInt("pt", 1).coerceIn(0, 2)]
    }

    private fun refresh() {
        startBtn.text = if (JarvisService.running) "Jarvis Bondho Koro" else "Jarvis Chalu Koro"
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
        if (prefs().getString("k", "").isNullOrEmpty()) { status.text = "Gemini key dao"; return }
        val need = mutableListOf<String>()
        if (missing(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (!askedExtra) {
            askedExtra = true
            val extra = mutableListOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) extra.add("android.permission.POST_NOTIFICATIONS")
            for (p in extra) if (missing(p)) need.add(p)
        }
        if (need.isNotEmpty()) {
            pending = action
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        val i = Intent(this, JarvisService::class.java)
        if (action != null) i.action = action
        startForegroundService(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val i = perms.indexOf(Manifest.permission.RECORD_AUDIO)
        if (i >= 0 && res[i] != PackageManager.PERMISSION_GRANTED) {
            status.text = "Mic permission dao"; return
        }
        go(pending)
    }

    override fun onResume() {
        super.onResume()
        JarvisService.onLog = { s -> log.append(s) }
        JarvisService.onStatus = { s -> status.text = s; refresh() }
        log.text = JarvisService.logBuf.toString()
        status.text = JarvisService.statusText
        refresh()
        refreshBtns()
    }

    override fun onPause() {
        saveFields()
        JarvisService.onLog = null
        JarvisService.onStatus = null
        super.onPause()
    }
}
