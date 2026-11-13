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
import android.widget.Toast

class MainActivity : Activity() {
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
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var log: TextView
    private var pending: String? = null
    private val rateNames = arrayOf("Dhire", "Normal", "Druto")
    private val pitchNames = arrayOf("Mota", "Normal", "Sharu")
    private val strictNames = arrayOf("Lenient (shohoje chine)", "Normal", "Strict (kothin)")

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
        lockBtn = btn("") { toggleLock() }
        strictBtn = btn("") { cycle("vs") }
        oneBtn = btn("") { toggleOne() }
        startBtn = btn("") {
            if (JarvisService.running) stopService(Intent(this, JarvisService::class.java))
            else go(null)
        }
        startBtn.textSize = 18f
        val talkBtn = btn("Ekhoni kotha bolo") { go("TALK") }
        val enrollBtn = btn("Amar voice shekhao (8 bar Jarvis + 5 lomba bakko)") { go("ENROLL") }
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
        val permBtn = btn("4. App permission (contacts, call, sms) dekho") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        status = TextView(this).apply { textSize = 16f }
        log = TextView(this).apply { textSize = 15f }
        listOf(gemBox, nickBox, personaBox, startBtn, talkBtn, enrollBtn, lockBtn, strictBtn, oneBtn,
            langBtn, wakeBtn, rateBtn, pitchBtn, ttsBtn, batBtn, accBtn, ovBtn, permBtn, status, log)
            .forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
        refreshBtns()
    }

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
            status.text = "Age 'Amar voice shekhao' tap koro"
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

    private fun cycle(k: String) {
        prefs().edit().putInt(k, (prefs().getInt(k, 1) + 1) % 3).apply()
        refreshBtns()
    }

    private fun refreshBtns() {
        langBtn.text = when (prefs().getString("l", "bn-BD")) {
            "bn-BD" -> "Bhasha: Bangla"
            "en-IN" -> "Bhasha: English (India accent)"
            else -> "Bhasha: English (US)"
        }
        wakeBtn.text = if (prefs().getString("w", "v") == "v") "Wake: Offline (beep nai, English Jarvis)"
        else "Wake: Online (Google, Bangla-o cholbe, voice lock cholbe na)"
        rateBtn.text = "Gotir: " + rateNames[prefs().getInt("sr", 1).coerceIn(0, 2)]
        pitchBtn.text = "Awajer sur: " + pitchNames[prefs().getInt("pt", 1).coerceIn(0, 2)]
        lockBtn.text = when {
            !prefs().contains("vp") -> "Voice lock: OFF (age voice shekhao)"
            prefs().getBoolean("vl", false) -> "Voice lock: ON (shudhu amar voice)"
            else -> "Voice lock: OFF"
        }
        strictBtn.text = "Lock strictness: " + strictNames[prefs().getInt("vs", 1).coerceIn(0, 2)]
        oneBtn.text = if (prefs().getBoolean("oc", false)) "Ek wake e ek kaj: ON" else "Ek wake e ek kaj: OFF"
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
        if (action != "ENROLL" && prefs().getString("k", "").isNullOrEmpty()) {
            status.text = "Gemini key dao"; return
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
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (missing(Manifest.permission.RECORD_AUDIO)) {
            status.text = "Mic permission dao (4. App permission button)"; return
        }
        val denied = perms.filterIndexed { i, _ -> res[i] != PackageManager.PERMISSION_GRANTED }
            .map { it.substringAfterLast('.') }
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Deoa hoyni: " + denied.joinToString() +
                ". '4. App permission' button diye dao. Baki kaj cholbe.", Toast.LENGTH_LONG).show()
        }
        startJarvis(pending)
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
