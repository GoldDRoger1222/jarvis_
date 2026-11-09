package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
    private lateinit var picoBox: EditText
    private lateinit var langBtn: Button
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private var pending: String? = null
    private var askedNotif = false

    private fun prefs() = getSharedPreferences("j", MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        fun field(h: String, k: String) = EditText(this).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs().getString(k, ""))
        }
        gemBox = field("Gemini API key", "k")
        picoBox = field("Picovoice AccessKey", "p")
        langBtn = Button(this).apply { setOnClickListener { toggleLang() } }
        startBtn = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                if (JarvisService.running) stopService(Intent(this@MainActivity, JarvisService::class.java))
                else go(null)
            }
        }
        val talkBtn = Button(this).apply { text = "Ekhoni kotha bolo"; setOnClickListener { go("TALK") } }
        val batBtn = Button(this).apply {
            text = "Battery optimization bondho koro"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        status = TextView(this).apply { textSize = 16f }
        log = TextView(this).apply { textSize = 15f }
        scroll = ScrollView(this).apply { addView(log) }
        listOf(gemBox, picoBox, langBtn, startBtn, talkBtn, batBtn, status).forEach { root.addView(it) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshLang()
    }

    private fun refreshLang() {
        langBtn.text = if (prefs().getString("l", "bn-BD") == "bn-BD") "Bhasha: Bangla" else "Bhasha: English"
    }

    private fun toggleLang() {
        val next = if (prefs().getString("l", "bn-BD") == "bn-BD") "en-US" else "bn-BD"
        prefs().edit().putString("l", next).apply()
        refreshLang()
    }

    private fun refresh() {
        startBtn.text = if (JarvisService.running) "Jarvis Bondho Koro" else "Jarvis Chalu Koro"
    }

    private fun go(action: String?) {
        prefs().edit()
            .putString("k", gemBox.text.toString().trim())
            .putString("p", picoBox.text.toString().trim())
            .apply()
        if (prefs().getString("k", "").isNullOrEmpty()) { status.text = "Gemini key dao"; return }
        if (action == null && prefs().getString("p", "").isNullOrEmpty()) {
            status.text = "Picovoice AccessKey dao"; return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS")
        }
        if (need.isNotEmpty()) {
            askedNotif = true
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
        JarvisService.onLog = { s -> log.append(s); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
        JarvisService.onStatus = { s -> status.text = s; refresh() }
        log.text = JarvisService.logBuf.toString()
        status.text = JarvisService.statusText
        refresh()
    }

    override fun onPause() {
        JarvisService.onLog = null
        JarvisService.onStatus = null
        super.onPause()
    }
}
