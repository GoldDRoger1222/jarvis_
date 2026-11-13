package com.example.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import org.vosk.android.RecognitionListener as VoskListener

class JarvisService : Service() {
    companion object {
        var running = false
        val logBuf = StringBuilder()
        var statusText = ""
        var onLog: ((String) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    private val models = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    private var mi = 0
    private val baseSystem = "You are Jarvis, a friendly voice assistant that can control an Android phone with tools. " +
        "Reply in the same language the user speaks (Bangla or English). " +
        "Keep spoken replies short, 1 to 2 sentences. No markdown, no emojis, no lists. " +
        "Be efficient: use as few tool calls as possible and never call read_screen twice in a row. " +
        "For weather use get_weather with the city name and describe the WMO weather code in plain words. " +
        "For news use get_news and include the country or place in the topic. " +
        "For other live or recent facts use web_search. " +
        "To send a WhatsApp message always use whatsapp_message. " +
        "To play a video use youtube_play. For songs and music use play_music, or youtube_play if the user says YouTube. " +
        "For other web pages use open_url. " +
        "For tasks inside other apps: open_app, then read_screen to see what is there, then tap or type_text, and read_screen again to check. " +
        "Pass English app names to open_app. Never type passwords, OTPs, PINs or card numbers. " +
        "Speech recognition can mishear words, so if a request looks odd, guess the most likely meaning from context. " +
        "Sensitive actions such as calls, SMS and tapping send, pay or delete buttons ask the user for confirmation automatically, so just go ahead and call the tool. " +
        "If the user declines, stop and say so. When the task is done, say so briefly."
    private val wakeWords = listOf("jarvis", "jarvice", "jervis", "javis",
        "জার্ভিস", "জারভিস", "জার্বিস", "জারবিস")
    private val sensitive = listOf("send", "pay", "buy", "order", "purchase", "confirm", "transfer",
        "delete", "remove", "submit", "post", "checkout", "uninstall", "reset", "erase", "call", "dial",
        "পাঠা", "কিন", "পেমেন্ট", "মুছ", "অর্ডার", "নিশ্চিত", "ফোন")
    private val riskyRx = Regex("\\b(pay|payment|amount|transfer|bkash|nagad|rocket|bank|otp|pin|password|card|cvv)\\b" +
        "|পেমেন্ট|টাকা|পিন|পাসওয়ার্ড|ওটিপি")
    private val yesWords = setOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "ha", "han", "haan",
        "হ্যাঁ", "হ্যা", "হা", "করো", "করুন", "পাঠাও", "জি")
    private val noWords = setOf("no", "nope", "cancel", "na", "nah", "না", "থাক", "বাদ", "থামো", "stop")
    private val dangling = setOf("and", "then", "to", "with", "for", "the", "a", "an", "in", "on", "at", "of",
        "also", "after", "please", "call", "open", "send", "message", "type", "search", "play", "set", "tell",
        "ask", "write", "text", "ar", "ebong", "tarpor", "pore", "এবং", "আর", "তারপর", "পরে", "দিয়ে", "কে")

    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var rec: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var waking = false
    @Volatile private var talking = false
    @Volatile private var enrolling = false
    @Volatile private var capGen = 0
    private var endAfter = false
    private var misses = 0
    private var rejects = 0
    private var errs = 0
    private var carry = ""
    private var carryN = 0
    private var contactNames: List<String> = emptyList()
    private var confirmQ: LinkedBlockingQueue<Boolean>? = null
    private var bubble: TextView? = null
    private var vosk: Model? = null
    private var spk: SpeakerModel? = null
    private var vs: SpeechService? = null
    private var vr: Recognizer? = null
    private var loading = false
    private var triggered = false
    private val samples = ArrayList<DoubleArray>()
    private var tmpWake: DoubleArray? = null
    private var tmpWakeThr = 0.45

    private fun prefs() = getSharedPreferences("j", MODE_PRIVATE)
    private fun lang() = prefs().getString("l", "bn-BD") ?: "bn-BD"
    private fun tr(bn: String, en: String) = if (lang() == "bn-BD") bn else en
    private fun ack() = tr("জি, বলুন", "Yes?")
    private fun lockOn() = prefs().getBoolean("vl", false) && prefs().contains("vp")
    private fun cmdLock() = lockOn() && prefs().contains("vc")
    private fun oneShot() = prefs().getBoolean("oc", false)
    private fun needSpk() = enrolling || lockOn()
    private fun fmt(d: Double) = "%.2f".format(d)

    private fun sys(): String {
        val nick = prefs().getString("n", "")?.trim().orEmpty()
        val per = prefs().getString("ps", "")?.trim().orEmpty()
        var s = baseSystem
        if (nick.isNotEmpty()) s += " Address the user as \"$nick\"."
        if (per.isNotEmpty()) s += " Personality and style: $per."
        if (contactNames.isNotEmpty())
            s += " The user's contacts are: " + contactNames.take(150).joinToString("; ") +
                ". When using a contact in a tool, write the name exactly as in this list."
        s += JarvisMemory.prompt(this)
        return s
    }

    private fun say(s: String) {
        statusText = s
        onStatus?.invoke(s)
        if (talking) showBubble(s)
    }

    private fun addLog(who: String, t: String) {
        val l = "$who: $t\n\n"
        logBuf.append(l)
        onLog?.invoke(l)
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("API 429") -> tr("Gemini এর ফ্রি লিমিট শেষ, একটু পরে আবার বলুন",
                "Gemini free limit reached, please try again in a bit")
            m.contains("API 503") -> tr("Gemini এখন ব্যস্ত, একটু পরে আবার বলুন",
                "Gemini is busy right now, please try again in a bit")
            else -> m.take(200)
        }
    }

    private fun fail(e: Exception) {
        val f = friendly(e)
        val speak = (e.message ?: "").let { it.contains("API 429") || it.contains("API 503") }
        ui.post {
            say(f)
            addLog("Error", f)
            if (speak && talking) { endAfter = true; speakOrListen(f) } else endTalk()
        }
    }

    // ---------- Voice lock helpers ----------

    private fun loadPrint(key: String): DoubleArray? {
        val s = prefs().getString(key, null) ?: return null
        return try {
            val a = JSONArray(s)
            DoubleArray(a.length()) { a.getDouble(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun threshold(): Double {
        val base = prefs().getFloat("vt", 0.45f).toDouble()
        val delta = (1 - prefs().getInt("vs", 1)) * 0.07
        return (base + delta).coerceIn(0.2, 0.8)
    }

    private fun cmdThreshold(): Double {
        val base = prefs().getFloat("vct", 0.42f).toDouble()
        val delta = (1 - prefs().getInt("vs", 1)) * 0.06
        return (base + delta).coerceIn(0.2, 0.7)
    }

    private fun norm(v: DoubleArray): DoubleArray {
        var n = 0.0
        for (x in v) n += x * x
        n = Math.sqrt(n) + 1e-9
        return DoubleArray(v.size) { v[it] / n }
    }

    private fun cosDist(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 1.0
        var d = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return 1.0 - d / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9)
    }

    private fun meanOf(list: List<DoubleArray>): DoubleArray {
        val dim = list[0].size
        val mean = DoubleArray(dim)
        for (s in list) for (i in 0 until dim) mean[i] += s[i] / list.size
        return mean
    }

    private fun startEnroll() {
        capGen++
        ui.removeCallbacksAndMessages(null)
        stopVosk()
        rec?.cancel()
        talking = false
        waking = false
        enrolling = true
        triggered = false
        samples.clear()
        tmpWake = null
        startVosk()
    }

    private fun addSample(arr: JSONArray?) {
        if (arr == null) { say("Chhoto hoye gese, abar bolo: Jarvis (${samples.size}/8)"); return }
        samples.add(norm(DoubleArray(arr.length()) { arr.getDouble(it) }))
        say("Bhalo! Abar bolo: Jarvis (${samples.size}/8)")
        if (samples.size >= 8) finishEnroll()
    }

    private fun finishEnroll() {
        val mean = meanOf(samples)
        var mx = 0.0
        for (s in samples) mx = maxOf(mx, cosDist(s, mean))
        tmpWake = mean
        tmpWakeThr = (mx + 0.12).coerceIn(0.35, 0.60)
        samples.clear()
        stopVosk()
        startCmdEnroll()
    }

    private fun startCmdEnroll() {
        val g = ++capGen
        thread {
            val vecs = ArrayList<DoubleArray>()
            var tries = 0
            while (vecs.size < 5 && enrolling && g == capGen && tries < 30) {
                tries++
                ui.post {
                    say("Ekhon ekta lomba bakko bolo (${vecs.size + 1}/5), kompokkhe 3 second. " +
                        "Jemon: 'aj amar mon bhalo, ami ekta natun app banachhi'")
                }
                val pcm = captureUtterance(8000, 12000, g) ?: continue
                if (pcm.size < 16000 * 2) { ui.post { say("Aro lomba bolo (kompokkhe 3 second)") }; continue }
                val v = try { embed(pcm) } catch (e: Exception) { null }
                if (v == null) { ui.post { say("Voice bujhte parini, abar bolo") }; continue }
                vecs.add(v)
            }
            if (enrolling && g == capGen && vecs.size >= 5) ui.post { finishCmdEnroll(vecs) }
            else if (enrolling && g == capGen) ui.post { say("Voice shekhano hoyni, abar chesta koro"); enrolling = false }
        }
    }

    private fun finishCmdEnroll(vecs: List<DoubleArray>) {
        val mean = meanOf(vecs)
        var mx = 0.0
        for (s in vecs) mx = maxOf(mx, cosDist(s, mean))
        val thr = (mx + 0.10).coerceIn(0.30, 0.55)
        val w = tmpWake
        if (w == null) { enrolling = false; return }
        prefs().edit()
            .putString("vp", JSONArray(w.toList()).toString())
            .putFloat("vt", tmpWakeThr.toFloat())
            .putString("vc", JSONArray(mean.toList()).toString())
            .putFloat("vct", thr.toFloat())
            .putBoolean("vl", true)
            .apply()
        enrolling = false
        tmpWake = null
        say("Voice save hoyeche. Voice lock ON (wake + command)")
        tts.setLanguage(Locale.forLanguageTag(lang()))
        tts.speak(tr("ভয়েস সেভ হয়েছে", "Voice saved"), TextToSpeech.QUEUE_FLUSH, null, "e")
        ui.postDelayed({ startWake() }, 2000)
    }

    // ---------- Bubble ----------

    private fun showBubble(text: String) {
        ui.post {
            try {
                if (!Settings.canDrawOverlays(this)) return@post
                val wm = getSystemService(WindowManager::class.java)
                if (bubble == null) {
                    val tv = TextView(this)
                    tv.setTextColor(Color.WHITE)
                    tv.textSize = 14f
                    tv.setPadding(36, 20, 36, 20)
                    val bg = GradientDrawable()
                    bg.setColor(0xDD202020.toInt())
                    bg.cornerRadius = 60f
                    tv.background = bg
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT)
                    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    lp.y = 120
                    wm.addView(tv, lp)
                    bubble = tv
                }
                bubble?.text = text
            } catch (e: Exception) {
            }
        }
    }

    private fun hideBubble() {
        ui.post {
            try {
                val b = bubble
                if (b != null) getSystemService(WindowManager::class.java).removeView(b)
            } catch (e: Exception) {
            }
            bubble = null
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { ui.post { afterSpeech() } }
            override fun onError(id: String?) { ui.post { afterSpeech() } }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "jarvis"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ch)
            .setContentTitle("Jarvis cholche")
            .setContentText("\"Jarvis\" bole dako")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) {
            say("Service error: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        loadContactNames()
        when (intent?.action) {
            "TALK" -> {
                if (lockOn()) loadModels { }
                onWake()
            }
            "ENROLL" -> startEnroll()
            else -> startWake()
        }
        return START_NOT_STICKY
    }

    private fun loadContactNames() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val out = LinkedHashSet<String>()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 200) c.getString(0)?.let { out.add(it) }
            }
            contactNames = out.toList()
        } catch (e: Exception) {
        }
    }

    // ---------- Wake word ----------

    private fun startWake() {
        if (talking || enrolling) return
        waking = true
        errs = 0
        if (prefs().getString("w", "v") == "v" || lockOn()) startVosk()
        else { say("Jarvis dak shunchi..."); listenSR() }
    }

    private fun download(url: String, name: String) {
        val zip = File(cacheDir, "$name.zip")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.inputStream.use { i -> zip.outputStream().use { o -> i.copyTo(o) } }
        val base = filesDir.canonicalPath
        ZipInputStream(zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(filesDir, e.name)
                if (!f.canonicalPath.startsWith(base)) throw Exception("bad zip")
                if (e.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o -> z.copyTo(o) }
                }
                e = z.nextEntry
            }
        }
        zip.delete()
        File(filesDir, "$name/ok").createNewFile()
    }

    private fun loadModels(then: () -> Unit) {
        if (vosk != null && (!needSpk() || spk != null)) { then(); return }
        if (loading) { ui.postDelayed({ loadModels(then) }, 1000); return }
        loading = true
        say("Offline model ready hocche (prothom bar download, WiFi e korle bhalo)...")
        thread {
            try {
                val n1 = "vosk-model-small-en-us-0.15"
                if (!File(filesDir, "$n1/ok").exists())
                    download("https://alphacephei.com/vosk/models/$n1.zip", n1)
                val loaded = vosk ?: Model(File(filesDir, n1).absolutePath)
                var spkLoaded: SpeakerModel? = spk
                if (needSpk() && spkLoaded == null) {
                    val n2 = "vosk-model-spk-0.4"
                    if (!File(filesDir, "$n2/ok").exists())
                        download("https://alphacephei.com/vosk/models/$n2.zip", n2)
                    spkLoaded = SpeakerModel(File(filesDir, n2).absolutePath)
                }
                ui.post {
                    vosk = loaded
                    spk = spkLoaded
                    loading = false
                    then()
                }
            } catch (e: Exception) {
                ui.post {
                    loading = false
                    say("Offline model error: ${e.message}. Wake button e Online koro")
                }
            }
        }
    }

    private fun startVosk() {
        val m = vosk
        val sp = spk
        if (m == null || (needSpk() && sp == null)) {
            loadModels { if (waking || enrolling) startVosk() }
            return
        }
        try {
            stopVosk()
            triggered = false
            val r = Recognizer(m, 16000f, "[\"jarvis\", \"[unk]\"]")
            if (needSpk() && sp != null) r.setSpeakerModel(sp)
            val s = SpeechService(r, 16000f)
            vr = r
            vs = s
            s.startListening(object : VoskListener {
                override fun onPartialResult(h: String?) { if (!needSpk()) heard(h) }
                override fun onResult(h: String?) {
                    if (needSpk()) ui.post { voskFinal(h) } else heard(h)
                }
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { ui.post { say("Wake error: ${e?.message}") } }
                override fun onTimeout() {}
            })
            say(if (enrolling) "Bolo: Jarvis (${samples.size}/8). Prottekbar ektu thamo"
            else if (lockOn()) "Jarvis dak shunchi... (offline, voice lock ON)"
            else "Jarvis dak shunchi... (offline)")
        } catch (e: Exception) {
            say("Wake error: ${e.message}")
        }
    }

    private fun heard(h: String?) {
        if (triggered || h == null || !h.lowercase().contains("jarvis")) return
        triggered = true
        ui.post { stopVosk(); handleWake("jarvis") }
    }

    private fun voskFinal(h: String?) {
        if (triggered || h == null || (!waking && !enrolling)) return
        val j = try { JSONObject(h) } catch (e: Exception) { return }
        if (!j.optString("text").lowercase().contains("jarvis")) return
        val arr = j.optJSONArray("spk")
        if (enrolling) { addSample(arr); return }
        if (arr == null) { say("Voice onek chhoto, abar bolo: Jarvis"); return }
        val saved = loadPrint("vp")
        if (saved == null) { say("Voiceprint nai, abar voice shekhao"); return }
        val v = DoubleArray(arr.length()) { arr.getDouble(it) }
        val d = cosDist(v, saved)
        if (d <= threshold()) {
            triggered = true
            stopVosk()
            handleWake("jarvis")
        } else {
            say("Ei voice chena na (${fmt(d)} > ${fmt(threshold())}). Jarvis ghumay")
        }
    }

    private fun stopVosk() {
        try { vs?.stop(); vs?.shutdown() } catch (e: Exception) {}
        try { vr?.close() } catch (e: Exception) {}
        vs = null
        vr = null
    }

    private fun applyVoice() {
        tts.setLanguage(Locale.forLanguageTag(lang()))
        tts.setSpeechRate(floatArrayOf(0.8f, 1.0f, 1.3f)[prefs().getInt("sr", 1).coerceIn(0, 2)])
        tts.setPitch(floatArrayOf(0.8f, 1.0f, 1.2f)[prefs().getInt("pt", 1).coerceIn(0, 2)])
    }

    private fun handleWake(t: String) {
        val l = t.lowercase()
        var at = -1
        var len = 0
        for (w in wakeWords) {
            val i = l.indexOf(w)
            if (i >= 0) { at = i; len = w.length; break }
        }
        if (at < 0) { ui.postDelayed({ listenSR() }, 200); return }
        val rest = l.substring(at + len).trim(' ', ',', '.', '!', '?', '।')
        capGen++
        ui.removeCallbacksAndMessages(null)
        stopVosk()
        waking = false
        talking = true
        endAfter = false
        misses = 0
        rejects = 0
        carry = ""
        carryN = 0
        applyVoice()
        if (rest.length > 2) joinOrHandle(rest) else { say("Haan bolo..."); speakOrListen(ack()) }
    }

    private fun onWake() {
        if (talking) return
        capGen++
        ui.removeCallbacksAndMessages(null)
        stopVosk()
        rec?.cancel()
        enrolling = false
        waking = false
        talking = true
        endAfter = false
        misses = 0
        rejects = 0
        carry = ""
        carryN = 0
        applyVoice()
        say("Haan bolo...")
        speakOrListen(ack())
    }

    private fun speakOrListen(t: String) {
        val r = tts.speak(t.take(3500), TextToSpeech.QUEUE_FLUSH, null, "u")
        if (r != TextToSpeech.SUCCESS) afterSpeech()
    }

    private fun afterSpeech() {
        if (!talking) return
        if (endAfter) endTalk() else listen()
    }

    // ---------- Listening ----------

    private fun listen() {
        if (talking && cmdLock()) listenAudio() else listenSR()
    }

    private fun listenSR() {
        if (!talking && !waking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Phone e speech service nai")
            waking = false; talking = false
            return
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    errs = 0
                    if (talking) say("Shunchi...")
                }
                override fun onResults(r: Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) {
                        if (waking) ui.postDelayed({ listenSR() }, 200) else miss()
                    } else if (waking) handleWake(t)
                    else { misses = 0; joinOrHandle(t) }
                }
                override fun onError(e: Int) {
                    if (!talking && !waking) return
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        if (waking) ui.postDelayed({ listenSR() }, 300) else miss()
                    } else if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        ui.postDelayed({ listenSR() }, 700)
                    } else if (waking && ++errs <= 5) {
                        ui.postDelayed({ listenSR() }, 2000)
                    } else {
                        say("Mic error $e. Jarvis Chalu Koro abar tap koro")
                        waking = false; talking = false
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang())
                if (talking) {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    if (Build.VERSION.SDK_INT >= 33 && contactNames.isNotEmpty())
                        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(contactNames))
                }
            })
        }
    }

    private fun listenAudio() {
        if (!talking) return
        val g = ++capGen
        say("Shunchi...")
        thread {
            val pcm = captureUtterance(7000, 15000, g)
            ui.post { onAudio(pcm, g) }
        }
    }

    private fun captureUtterance(startTimeoutMs: Int, maxMs: Int, gen: Int): ShortArray? {
        val rate = 16000
        val frame = 320
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 16))
        } catch (e: Exception) {
            return null
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return null }
        val cap = rate * (startTimeoutMs + maxMs) / 1000 + frame
        val buf = ShortArray(cap)
        val fr = ShortArray(frame)
        var n = 0
        var noise = 0.0
        var frames = 0
        var loud = 0
        var started = false
        var startAt = 0
        var quiet = 0
        try {
            Thread.sleep(250)
            ar.startRecording()
            while (n + frame <= cap && gen == capGen && (talking || enrolling)) {
                val r = ar.read(fr, 0, frame)
                if (r <= 0) break
                System.arraycopy(fr, 0, buf, n, r)
                n += r
                var sum = 0.0
                for (i in 0 until r) sum += fr[i].toDouble() * fr[i]
                val rms = Math.sqrt(sum / r)
                frames++
                if (frames <= 10) { noise += rms / 10; continue }
                val hi = maxOf(noise * 2.5, 450.0)
                val lo = maxOf(noise * 1.8, 300.0)
                if (!started) {
                    if (rms > hi) loud++ else loud = 0
                    if (loud >= 3) { started = true; startAt = maxOf(0, n - frame * 13); quiet = 0 }
                    else if (n * 1000L / rate > startTimeoutMs) break
                } else {
                    if (rms < lo) quiet++ else quiet = 0
                    val speechMs = (n - startAt) * 1000L / rate
                    if (quiet >= 60 || speechMs > maxMs) break
                }
            }
        } catch (e: Exception) {
        } finally {
            try { ar.stop() } catch (e: Exception) {}
            ar.release()
        }
        if (!started || gen != capGen) return null
        return buf.copyOfRange(startAt, n)
    }

    private fun embed(pcm: ShortArray): DoubleArray? {
        val m = vosk ?: return null
        val sp = spk ?: return null
        val vecs = ArrayList<DoubleArray>()
        val ws = ArrayList<Double>()
        fun addVec(js: String) {
            val j = try { JSONObject(js) } catch (e: Exception) { return }
            val a = j.optJSONArray("spk") ?: return
            vecs.add(norm(DoubleArray(a.length()) { a.getDouble(it) }))
            ws.add(maxOf(1, j.optInt("spk_frames", 1)).toDouble())
        }
        val r = Recognizer(m, 16000f)
        try {
            r.setSpeakerModel(sp)
            var i = 0
            while (i < pcm.size) {
                val n = minOf(4000, pcm.size - i)
                if (r.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) addVec(r.result)
                i += n
            }
            addVec(r.finalResult)
        } finally {
            try { r.close() } catch (e: Exception) {}
        }
        if (vecs.isEmpty()) return null
        val dim = vecs[0].size
        val out = DoubleArray(dim)
        var wsum = 0.0
        for (k in vecs.indices) {
            wsum += ws[k]
            for (d in 0 until dim) out[d] += vecs[k][d] * ws[k]
        }
        for (d in 0 until dim) out[d] /= wsum
        return norm(out)
    }

    private fun wavBytes(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(16000)
        bb.putInt(32000)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    private fun transcribe(pcm: ShortArray, key: String): String {
        val b64 = Base64.encodeToString(wavBytes(pcm), Base64.NO_WRAP)
        val prompt = "Transcribe this audio exactly as spoken. The speaker uses Bangla, English or Hindi, often mixed. " +
            "Write Bangla speech in Bangla script and English words in English letters. " +
            "Output only the transcript text. If there is no clear human speech, output exactly NOTHING."
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64)))
        val body = JSONObject().put("contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val j = JSONObject(postRetry(key, body))
        val ps = j.getJSONArray("candidates").getJSONObject(0).optJSONObject("content")
            ?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
        }
        val t = sb.toString().trim()
        return if (t.equals("NOTHING", true)) "" else t
    }

    private fun onAudio(pcm: ShortArray?, g: Int) {
        if (!talking || g != capGen) return
        if (pcm == null || pcm.size < 8000) { miss(); return }
        if (vosk == null || spk == null) {
            if (loading) {
                say("Voice model ready hocche, ektu opekkha...")
                ui.postDelayed({ if (talking && g == capGen) listen() }, 1500)
            } else {
                say("Voice model load hoyni, Jarvis abar chalu koro")
                endTalk()
            }
            return
        }
        misses = 0
        say("Voice check korchi...")
        val key = prefs().getString("k", "")?.trim() ?: ""
        thread {
            val v = try { embed(pcm) } catch (e: Exception) { null }
            val saved = loadPrint("vc")
            if (v == null || saved == null) {
                ui.post {
                    if (g != capGen || !talking) return@post
                    say("Voice check korte parini, abar bolo")
                    listen()
                }
                return@thread
            }
            val d = cosDist(v, saved)
            val secs = pcm.size / 16000.0
            val thr = cmdThreshold() + (if (secs < 1.5) 0.10 else 0.0)
            if (d > thr) {
                ui.post {
                    if (g != capGen || !talking) return@post
                    rejects++
                    addLog("Lock", "Onno voice ignore korlam (${fmt(d)} > ${fmt(thr)})")
                    say("Ei voice chena na (${fmt(d)} > ${fmt(thr)})")
                    if (rejects >= 3) endTalk() else listen()
                }
                return@thread
            }
            val text = try { transcribe(pcm, key) } catch (e: Exception) {
                fail(e)
                return@thread
            }
            ui.post {
                if (g != capGen || !talking) return@post
                rejects = 0
                addLog("Lock", "Voice mile geche (${fmt(d)} <= ${fmt(thr)})")
                if (text.isBlank()) miss() else joinOrHandle(text)
            }
        }
    }

    private fun miss() {
        if (carry.isNotEmpty()) {
            val c = carry
            carry = ""
            carryN = 0
            handle(c)
            return
        }
        misses++
        if (misses >= 2) endTalk() else ui.postDelayed({ listen() }, 300)
    }

    private fun joinOrHandle(t: String) {
        val full = (carry + " " + t).trim()
        val last = full.split(' ').last().lowercase().trim('.', ',', '!', '?')
        if (last in dangling && carryN < 2 && confirmQ == null) {
            carry = full
            carryN++
            say("Bolo, shunchi...")
            ui.postDelayed({ listen() }, 200)
        } else {
            carry = ""
            carryN = 0
            handle(full)
        }
    }

    private fun handle(t: String) {
        addLog("Tumi", t)
        val low = t.lowercase()
        confirmQ?.let { qq ->
            val w = low.split(' ', ',', '.', '!', '?', '।').filter { it.isNotEmpty() }
            val no = w.any { it in noWords } || low.contains("don't")
            val yes = w.any { it in yesWords } || low.contains("ঠিক আছে") || low.contains("go ahead")
            if (no) qq.offer(false)
            else if (yes) qq.offer(true)
            else speakOrListen(tr("হ্যাঁ বা না বলুন", "Please say yes or no"))
            return
        }
        val stopWords = setOf("stop", "goodbye", "thamo", "bondho", "বন্ধ", "থামো")
        if (stopWords.contains(low.trim().trimEnd('.'))) {
            endAfter = true
            speakOrListen(tr("ঠিক আছে", "Okay"))
            return
        }
        ask(t)
    }

    private fun confirm(question: String): Boolean {
        val qq = LinkedBlockingQueue<Boolean>()
        ui.post {
            confirmQ = qq
            misses = 0
            addLog("Jarvis", question)
            speakOrListen(question)
        }
        val r = qq.poll(45, TimeUnit.SECONDS)
        ui.post { confirmQ = null }
        return r == true
    }

    // ---------- Gemini ----------

    private fun msg(role: String, text: String) = JSONObject().put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun fn(name: String, desc: String, vararg p: Pair<String, String>): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (p.isNotEmpty()) {
            val props = JSONObject()
            val req = JSONArray()
            for ((k, t) in p) { props.put(k, JSONObject().put("type", t)); req.put(k) }
            o.put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
        }
        return o
    }

    private val tools: JSONArray by lazy {
        JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(fn("whatsapp_message", "Send a WhatsApp text message to a contact name or phone number", "to" to "string", "message" to "string"))
            .put(fn("get_weather", "Get current weather and today's forecast for a city", "city" to "string"))
            .put(fn("remember", "Save a short fact about the user to long-term memory", "fact" to "string"))
            .put(fn("forget", "Forget saved memories that contain a keyword, or all of them", "keyword" to "string"))
            .put(fn("list_memories", "List everything saved in long-term memory"))
            .put(fn("get_news", "Get the latest news headlines about a topic", "topic" to "string"))
            .put(fn("web_search", "Search the web for other current information such as scores or prices", "query" to "string"))
            .put(fn("youtube_play", "Search YouTube and play the first video", "query" to "string"))
            .put(fn("play_music", "Play a song, artist or playlist in the default music app", "query" to "string"))
            .put(fn("open_app", "Open an installed app by name", "app_name" to "string"))
            .put(fn("open_url", "Open a web link in the browser or matching app", "url" to "string"))
            .put(fn("set_alarm", "Set an alarm", "hour" to "integer", "minute" to "integer", "label" to "string"))
            .put(fn("set_timer", "Start a countdown timer", "seconds" to "integer", "label" to "string"))
            .put(fn("make_call", "Call a contact name or phone number", "who" to "string"))
            .put(fn("send_sms", "Send an SMS to a contact name or number", "to" to "string", "message" to "string"))
            .put(fn("flashlight", "Turn the flashlight on or off", "on" to "boolean"))
            .put(fn("set_volume", "Set media volume 0 to 100 percent", "percent" to "integer"))
            .put(fn("media_control", "Control media playback: play_pause, next or previous", "action" to "string"))
            .put(fn("read_screen", "Read the text currently visible on the phone screen"))
            .put(fn("tap", "Tap a button or item on screen by its visible text", "text" to "string"))
            .put(fn("type_text", "Type text into the focused input field", "text" to "string"))
            .put(fn("scroll", "Scroll the screen, direction is up or down", "direction" to "string"))
            .put(fn("press_key", "Press a system key: back, home or recents", "key" to "string"))
        ))
    }

    private fun trimHistory() {
        while (history.length() > 30) history.remove(0)
        while (history.length() > 0) {
            val f = history.getJSONObject(0)
            val p = f.optJSONArray("parts")?.optJSONObject(0)
            if (f.optString("role") == "user" && p != null && p.has("text")) break
            history.remove(0)
        }
    }

    private fun ask(text: String) {
        trimHistory()
        val mark = history.length()
        history.put(msg("user", text))
        say("Bhabchi...")
        val key = prefs().getString("k", "")?.trim() ?: ""
        thread {
            try {
                var reply = ""
                var finished = false
                for (step in 1..12) {
                    val content = callGemini(key)
                    history.put(content)
                    val parts = content.optJSONArray("parts") ?: JSONArray()
                    val calls = ArrayList<JSONObject>()
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (p.has("functionCall")) calls.add(p.getJSONObject("functionCall"))
                        else if (p.has("text") && !p.optBoolean("thought")) sb.append(p.getString("text"))
                    }
                    if (calls.isEmpty()) { reply = sb.toString(); finished = true; break }
                    val resp = JSONArray()
                    for (c in calls) {
                        val name = c.getString("name")
                        val args = c.optJSONObject("args") ?: JSONObject()
                        ui.post { say("Kaj korchi: $name") }
                        val out = try { runTool(name, args) } catch (e: Exception) { "Error: ${e.message}" }
                        resp.put(JSONObject().put("functionResponse", JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put("result", out))))
                    }
                    history.put(JSONObject().put("role", "user").put("parts", resp))
                }
                if (!finished) reply = tr("কাজটা শেষ করতে পারিনি", "I could not finish the task")
                val clean = reply.replace("*", "").trim()
                ui.post {
                    if (!talking) return@post
                    addLog("Jarvis", clean)
                    if (clean.isEmpty()) {
                        if (oneShot()) endTalk() else listen()
                    } else {
                        say("Bolchi...")
                        showBubble(clean.take(140))
                        if (oneShot() && !clean.trimEnd().endsWith("?")) endAfter = true
                        speakOrListen(clean)
                    }
                }
            } catch (e: Exception) {
                while (history.length() > mark) history.remove(history.length() - 1)
                fail(e)
            }
        }
    }

    private fun post(key: String, body: JSONObject): String {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/${models[mi]}:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) throw Exception("API ${c.responseCode}: ${txt.take(1500)}")
        return txt
    }

    private fun postRetry(key: String, body: JSONObject): String {
        for (i in 1..4) {
            try {
                return post(key, body)
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (m.contains("API 429")) {
                    if (m.contains("PerDay")) {
                        if (mi < models.size - 1) { mi++; continue }
                        throw e
                    }
                    if (i == 4) throw e
                    val w = Regex("\"retryDelay\":\\s*\"(\\d+)").find(m)?.groupValues?.get(1)?.toLongOrNull() ?: 15L
                    ui.post { say("Limit: ${w}s opekkha korchi...") }
                    Thread.sleep(minOf(w, 40L) * 1000)
                    continue
                }
                if (i == 4 || !(m.contains("503") || m.contains("timeout"))) throw e
                Thread.sleep(2000)
            }
        }
        throw Exception("Gemini error")
    }

    private fun callGemini(key: String): JSONObject {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", "${sys()} Current date and time: $now."))))
            .put("contents", history)
            .put("tools", tools)
        val txt = postRetry(key, body)
        val content = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .optJSONObject("content") ?: throw Exception("Empty reply from Gemini")
        if (!content.has("role")) content.put("role", "model")
        return content
    }

    private fun webSearch(q: String): String {
        val key = prefs().getString("k", "")?.trim() ?: ""
        val body = JSONObject()
            .put("contents", JSONArray().put(msg("user", "$q\nAnswer briefly and factually using current information.")))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        var last: Exception? = null
        for (i in 1..2) {
            try {
                val parts = JSONObject(post(key, body)).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (j in 0 until parts.length()) sb.append(parts.getJSONObject(j).optString("text"))
                return sb.toString().take(1500)
            } catch (e: Exception) {
                last = e
                Thread.sleep(1500)
            }
        }
        return "Search failed: ${last?.message?.take(200)}"
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return c.inputStream.bufferedReader().readText()
    }

    private fun getWeather(city: String): String {
        val g = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + Uri.encode(city)))
            .optJSONArray("results") ?: return "City not found: $city"
        val p = g.getJSONObject(0)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.getDouble("latitude")}" +
            "&longitude=${p.getDouble("longitude")}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1"
        val j = JSONObject(httpGet(url))
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        return "Weather in ${p.optString("name")}: now ${cur.getDouble("temperature_2m")} C, " +
            "feels like ${cur.getDouble("apparent_temperature")} C, " +
            "humidity ${cur.getInt("relative_humidity_2m")} percent, " +
            "wind ${cur.getDouble("wind_speed_10m")} km/h, WMO weather code ${cur.getInt("weather_code")}. " +
            "Today high ${d.getJSONArray("temperature_2m_max").getDouble(0)} C, " +
            "low ${d.getJSONArray("temperature_2m_min").getDouble(0)} C, " +
            "rain chance ${d.getJSONArray("precipitation_probability_max").optInt(0)} percent."
    }

    private fun getNews(topic: String): String {
        val q = if (topic.isBlank()) "top stories" else topic
        val xml = httpGet("https://news.google.com/rss/search?hl=en-US&gl=US&ceid=US:en&q=" + Uri.encode(q))
        val rx = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val items = xml.split("<item>").drop(1).take(6).mapNotNull { rx.find(it)?.groupValues?.get(1) }
        if (items.isEmpty()) return "No news found for $q"
        return items.joinToString("\n") {
            it.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        }
    }

    // ---------- Tools ----------

    private fun launch(i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = JarvisAccess.inst ?: this
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    private fun runTool(name: String, a: JSONObject): String {
        return when (name) {
            "whatsapp_message" -> whatsappMessage(a.optString("to"), a.optString("message"))
            "get_weather" -> getWeather(a.optString("city"))
            "remember" -> JarvisMemory.add(this, a.optString("fact"))
            "forget" -> JarvisMemory.forget(this, a.optString("keyword"))
            "list_memories" -> JarvisMemory.list(this).let { if (it.isEmpty()) "Nothing saved yet" else it.joinToString("\n") }
            "get_news" -> getNews(a.optString("topic"))
            "web_search" -> webSearch(a.optString("query"))
            "youtube_play" -> {
                val u = "https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query"))
                if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) "Could not open YouTube"
                else { Thread.sleep(4000); tapFirstVideo() }
            }
            "play_music" -> {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(SearchManager.QUERY, a.optString("query"))
                if (launch(i)) "Asked the music app to play ${a.optString("query")}" else "No music app could handle that"
            }
            "open_app" -> { val r = openApp(a.optString("app_name")); Thread.sleep(2000); r }
            "open_url" -> {
                var u = a.optString("url")
                if (!u.startsWith("http")) u = "https://$u"
                val r = if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) "Opened $u" else "Could not open the link"
                Thread.sleep(1500); r
            }
            "set_alarm" -> {
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (launch(i)) "Alarm set" else "Could not set the alarm"
            }
            "set_timer" -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (launch(i)) "Timer started" else "Could not start the timer"
            }
            "make_call" -> makeCall(a.optString("who"))
            "send_sms" -> sendSms(a.optString("to"), a.optString("message"))
            "flashlight" -> flash(a.optBoolean("on"))
            "set_volume" -> {
                val am = getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, a.optInt("percent").coerceIn(0, 100) * max / 100, 0)
                "Volume set"
            }
            "media_control" -> {
                val code = when (a.optString("action")) {
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                val am = getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                "Done"
            }
            "read_screen", "tap", "type_text", "scroll", "press_key" -> uiTool(name, a)
            else -> "Unknown tool"
        }
    }

    private fun tapFirstVideo(): String {
        val s = JarvisAccess.inst ?: return "Accessibility is off, so I could not tap the video"
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            if (n.isClickable && d.contains("play video")) return n
            for (i in 0 until n.childCount) {
                val r = find(n.getChild(i))
                if (r != null) return r
            }
            return null
        }
        for (k in 1..4) {
            val n = find(s.rootInActiveWindow)
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Playing the first video"
            Thread.sleep(1500)
        }
        return "Could not find a video to tap. Use read_screen and tap instead."
    }

    private fun tapDesc(label: String): Boolean {
        val s = JarvisAccess.inst ?: return false
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val d = n.contentDescription?.toString()?.trim()?.lowercase()
            if (d == label && n.isClickable) return n
            for (i in 0 until n.childCount) {
                val r = find(n.getChild(i))
                if (r != null) return r
            }
            return null
        }
        val n = find(s.rootInActiveWindow) ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.lowercase().trim()
        val hit = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "App not found: $name"
        val li = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "Cannot launch that app"
        return if (launch(li)) "Opened ${hit.loadLabel(pm)}"
        else "Could not open the app. The user may need to allow Display over other apps for Jarvis."
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun sim(q: String, n: String): Double {
        if (n.contains(q)) return 1.0
        if (q.contains(n) && n.length >= 3) return 0.9
        return 1.0 - lev(q, n).toDouble() / maxOf(q.length, n.length)
    }

    private fun findContact(who: String): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6 && who.none { it.isLetter() }) return Pair(who, digits)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val q = who.lowercase().trim()
        if (q.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bs = 0.0
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val norm = c.getString(1)
                val num = if (!norm.isNullOrBlank()) norm else (c.getString(2) ?: "")
                val n = name.lowercase()
                var s = sim(q, n)
                for (w in n.split(' ')) if (w.length >= 2) s = maxOf(s, sim(q, w))
                if (s > bs) { bs = s; best = Pair(name, num) }
            }
        }
        return if (bs >= 0.5) best else null
    }

    private fun makeCall(who: String): String {
        val c = findContact(who) ?: return "No contact found for $who (contacts permission may be missing)"
        if (!confirm(tr("${c.first} কে ফোন করব?", "Call ${c.first}?"))) return "User declined"
        val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.second)))
        return if (launch(i)) "Calling ${c.first}" else "Could not start the call"
    }

    private fun sendSms(to: String, body: String): String {
        val c = findContact(to) ?: return "No contact found for $to (contacts permission may be missing)"
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "SMS permission is missing"
        if (!confirm(tr("${c.first} কে মেসেজ পাঠাব: $body ?", "Send to ${c.first}: $body ?"))) return "User declined"
        val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
        sm.sendMultipartTextMessage(c.second, null, sm.divideMessage(body), null, null)
        return "SMS sent to ${c.first}"
    }

    private fun whatsappMessage(to: String, text: String): String {
        val c = findContact(to) ?: return "No contact found for $to (contacts permission may be missing)"
        if (!confirm(tr("${c.first} কে WhatsApp এ পাঠাব: $text ?", "Send to ${c.first} on WhatsApp: $text ?")))
            return "User declined"
        val num = c.second.filter { it.isDigit() }
        val u = "https://wa.me/$num?text=" + Uri.encode(text)
        if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) return "Could not open WhatsApp"
        if (JarvisAccess.inst == null)
            return "WhatsApp opened with the message typed, but Accessibility is off so I could not press send"
        for (k in 1..6) {
            Thread.sleep(1500)
            if (tapDesc("send") || tapDesc("পাঠান")) return "Message sent to ${c.first}"
        }
        return "WhatsApp opened with the message typed, but I could not press Send"
    }

    private fun flash(on: Boolean): String {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No flashlight on this phone"
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on" else "Flashlight off"
    }

    private fun uiTool(name: String, a: JSONObject): String {
        val s = JarvisAccess.inst
            ?: return "Accessibility service is off. Ask the user to turn on Jarvis in Accessibility settings."
        return when (name) {
            "read_screen" -> s.readScreen()
            "tap" -> {
                val t = a.optString("text")
                val l = t.lowercase()
                if (sensitive.any { l.contains(it) } &&
                    !confirm(tr("'$t' চাপব? নিশ্চিত?", "Tap '$t'? Are you sure?"))) return "User declined"
                val r = s.tap(t)
                Thread.sleep(1200)
                r
            }
            "type_text" -> {
                val t = a.optString("text")
                val screen = s.readScreen().lowercase()
                if (riskyRx.containsMatchIn(screen)) {
                    val numeric = t.count { it.isDigit() } >= 4 && t.all { it.isDigit() || it in " -+" }
                    if (numeric) return "Refused: I do not type numbers or codes on payment or login screens"
                    if (!confirm(tr("এখানে '$t' লিখব?", "Type '$t' here?"))) return "User declined"
                }
                val r = s.typeText(t)
                Thread.sleep(400)
                r
            }
            "scroll" -> { val r = s.scroll(a.optString("direction") != "up"); Thread.sleep(600); r }
            else -> s.key(a.optString("key"))
        }
    }

    // ---------- End ----------

    private fun endTalk() {
        capGen++
        talking = false
        endAfter = false
        carry = ""
        carryN = 0
        confirmQ?.offer(false)
        hideBubble()
        val r = rec
        rec = null
        ui.post { r?.cancel(); r?.destroy() }
        tts.stop()
        ui.postDelayed({ startWake() }, 600)
    }

    override fun onDestroy() {
        capGen++
        running = false
        talking = false
        waking = false
        enrolling = false
        confirmQ?.offer(false)
        ui.removeCallbacksAndMessages(null)
        hideBubble()
        stopVosk()
        try { vosk?.close() } catch (e: Exception) {}
        try { spk?.close() } catch (e: Exception) {}
        rec?.destroy()
        tts.shutdown()
        say("Jarvis bondho")
        super.onDestroy()
    }
}
