package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LiveActivity : Activity(), LiveCb {
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var you: TextView
    private lateinit var ai: TextView
    private lateinit var startBtn: Button
    private lateinit var muteBtn: Button
    private var session: LiveSession? = null
    private var wasRunning = false
    private var lastWho = ""
    private val youB = StringBuilder()
    private val aiB = StringBuilder()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        title.text = "JARVIS LIVE"
        title.setTextColor(Hud.cyan)
        title.textSize = 20f
        title.letterSpacing = 0.25f
        title.typeface = Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Hud.btn(c, "AI \u2699") { Providers.show(c) })
        root.addView(head)

        orb = OrbView(c)
        root.addView(orb, LinearLayout.LayoutParams(-1, Hud.dp(c, 240)))
        status = TextView(c)
        status.gravity = Gravity.CENTER
        status.setTextColor(Hud.cyan)
        status.textSize = 13f
        status.text = "START tap koro"
        root.addView(status, Hud.lp(c, 2))

        you = TextView(c)
        you.setTextColor(Hud.dim)
        you.textSize = 14f
        ai = TextView(c)
        ai.setTextColor(Hud.txt)
        ai.textSize = 15f
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.addView(you)
        col.addView(ai, Hud.lp(c, 10))
        val sv = ScrollView(c)
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        startBtn = Hud.btn(c, "START", true) { if (session == null) begin() else end() }
        muteBtn = Hud.btn(c, "MIC ON") {
            val s = session
            if (s != null) {
                s.muted = !s.muted
                muteBtn.text = if (s.muted) "MIC OFF" else "MIC ON"
            }
        }
        row.addView(startBtn, LinearLayout.LayoutParams(0, -2, 1f))
        val l2 = LinearLayout.LayoutParams(0, -2, 1f)
        l2.leftMargin = Hud.dp(c, 10)
        row.addView(muteBtn, l2)
        root.addView(row, Hud.lp(c, 8))
        setContentView(root)
        orb.state = OrbView.OFF
        if (intent.getBooleanExtra("auto", false)) begin()
    }

    private fun begin() {
        if (session != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        if (JarvisService.running) {
            wasRunning = true
            stopService(Intent(this, JarvisService::class.java))
        }
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true
        you.text = ""
        ai.text = ""
        youB.setLength(0)
        aiB.setLength(0)
        val s = LiveSession(this, this)
        session = s
        s.start()
        startBtn.text = "STOP"
        status.text = "Connect hocche..."
    }

    private fun end() {
        session?.stop()
        session = null
        try {
            val am = getSystemService(AudioManager::class.java)
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        } catch (e: Exception) {
        }
        startBtn.text = "START"
        muteBtn.text = "MIC ON"
        orb.state = OrbView.OFF
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) begin()
        else status.text = "Mic permission dao"
    }

    override fun state(s: Int) {
        runOnUiThread {
            orb.state = when (s) {
                1, 3 -> OrbView.THINK
                2 -> OrbView.LISTEN
                4 -> OrbView.SPEAK
                else -> OrbView.OFF
            }
            status.text = when (s) {
                1 -> "Connect hocche..."
                2 -> "Shunchi... bolo"
                3 -> "Kaj korchi..."
                4 -> "Jarvis bolche"
                else -> ""
            }
        }
    }

    override fun text(who: String, t: String) {
        runOnUiThread {
            if (who != lastWho && who == "user") {
                youB.setLength(0)
                aiB.setLength(0)
            }
            lastWho = who
            if (who == "user") youB.append(t) else aiB.append(t)
            you.text = if (youB.isEmpty()) "" else "Tumi: $youB"
            ai.text = if (aiB.isEmpty()) "" else "Jarvis: $aiB"
        }
    }

    override fun info(s: String) {
        runOnUiThread { status.text = s }
    }

    override fun closed(reason: String) {
        runOnUiThread {
            end()
            status.text = reason
        }
    }

    override fun onDestroy() {
        end()
        if (wasRunning) {
            try {
                startForegroundService(Intent(this, JarvisService::class.java))
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }
}
