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
import android.media.AudioManager
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
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    private val model = "gemini-3.5-flash-lite"
    private val baseSystem = "You are Jarvis, a friendly voice assistant that can control an Android phone with tools. " +
        "Reply in the same language the user speaks (Bangla or English). " +
        "Keep spoken replies short, 1 to 2 sentences. No markdown, no emojis, no lists. " +
        "For weather, news, sports scores, prices or any live or recent facts use web_search and include the place name in the query. " +
        "To play a video use youtube_play. For songs and music use play_music, or youtube_play if the user says YouTube. " +
        "For other web pages use open_url. " +
        "For tasks inside other apps: open_app, then read_screen to see what is there, then tap or type_text, and read_screen again to check. " +
        "Pass English app names to open_app. Never type passwords, OTPs, PINs or card numbers. " +
        "Sensitive actions such as calls, SMS and tapping send, pay or delete buttons ask the user for confirmation automatically, so just go ahead and call the tool. " +
        "If the user declines, stop and say so. When the task is done, say so briefly."
    private val wakeWords = listOf("jarvis", "jarvice", "jervis", "javis",
        "জার্ভিস", "জারভিস", "জার্বিস", "জারবিস")
    private val sensitive = listOf("send", "pay", "buy", "order", "purchase", "confirm", "transfer",
        "delete", "remove", "submit", "post", "checkout", "uninstall", "reset", "erase", "call", "dial",
        "পাঠা", "কিন", "পেমেন্ট", "মুছ", "অর্ডার", "নিশ্চিত", "ফোন")
    private val yesWords = setOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "ha", "han", "haan",
        "হ্যাঁ", "হ্যা", "হা", "করো", "করুন", "পাঠাও", "জি")
    private val noWords = setOf("no", "nope", "cancel", "na", "nah", "না", "থাক", "বাদ", "থামো", "stop")

    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var rec: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var waking = false
    private var talking = false
    private var endAfter = false
    private var misses = 0
    private var errs = 0
    private var confirmQ: LinkedBlockingQueue<Boolean>? = null
    private var bubble: TextView? = null
    private var vosk: Model? = null
    private var vs: SpeechService? = null
    private var vr: Recognizer? = null
    private var loading = false
    private var triggered = false

    private fun prefs() = getSharedPreferences("j", MODE_PRIVATE)
    private fun lang() = prefs().getString("l", "bn-BD") ?: "bn-BD"
    private fun tr(bn: String, en: String) = if (lang() == "bn-BD") bn else en
    private fun ack() = tr("জি, বলুন", "Yes?")

    private fun sys(): String {
        val nick = prefs().getString("n", "")?.trim().orEmpty()
        val per = prefs().getString("ps", "")?.trim().orEmpty()
        var s = baseSystem
        if (nick.isNotEmpty()) s += " Address the user as \"$nick\"."
        if (per.isNotEmpty()) s += " Personality and style: $per."
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
        if (intent?.action == "TALK") onWake() else startWake()
        return START_NOT_STICKY
    }

    // ---------- Wake word ----------

    private fun startWake() {
        if (talking) return
        waking = true
        errs = 0
        if (prefs().getString("w", "v") == "v") startVosk()
        else { say("Jarvis dak shunchi..."); listen() }
    }

    private fun download() {
        val zip = File(cacheDir, "vosk.zip")
        val c = URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
            .openConnection() as HttpURLConnection
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
        File(filesDir, "vosk-model-small-en-us-0.15/ok").createNewFile()
    }

    private fun startVosk() {
        val m = vosk
        if (m == null) {
            if (loading) return
            loading = true
            say("Offline model ready hocche (prothom bar ~40MB download)...")
            thread {
                try {
                    val dir = File(filesDir, "vosk-model-small-en-us-0.15")
                    if (!File(dir, "ok").exists()) download()
                    val loaded = Model(dir.absolutePath)
                    ui.post { vosk = loaded; loading = false; if (waking) startVosk() }
                } catch (e: Exception) {
                    ui.post {
                        loading = false
                        say("Offline model error: ${e.message}. Wake button e Online koro")
                    }
                }
            }
            return
        }
        try {
            stopVosk()
            triggered = false
            val r = Recognizer(m, 16000f, "[\"jarvis\", \"[unk]\"]")
            val s = SpeechService(r, 16000f)
            vr = r
            vs = s
            s.startListening(object : VoskListener {
                override fun onPartialResult(h: String?) { heard(h) }
                override fun onResult(h: String?) { heard(h) }
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { ui.post { say("Wake error: ${e?.message}") } }
                override fun onTimeout() {}
            })
            say("Jarvis dak shunchi... (offline)")
        } catch (e: Exception) {
            say("Wake error: ${e.message}")
        }
    }

    private fun heard(h: String?) {
        if (triggered || h == null || !h.lowercase().contains("jarvis")) return
        triggered = true
        ui.post { stopVosk(); handleWake("jarvis") }
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
        if (at < 0) { ui.postDelayed({ listen() }, 200); return }
        val rest = l.substring(at + len).trim(' ', ',', '.', '!', '?', '।')
        ui.removeCallbacksAndMessages(null)
        stopVosk()
        waking = false
        talking = true
        endAfter = false
        misses = 0
        applyVoice()
        if (rest.length > 2) handle(rest) else { say("Haan bolo..."); speakOrListen(ack()) }
    }

    private fun onWake() {
        if (talking) return
        ui.removeCallbacksAndMessages(null)
        stopVosk()
        rec?.cancel()
        waking = false
        talking = true
        endAfter = false
        misses = 0
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

    private fun listen() {
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
                        if (waking) ui.postDelayed({ listen() }, 200) else miss()
                    } else if (waking) handleWake(t)
                    else { misses = 0; handle(t) }
                }
                override fun onError(e: Int) {
                    if (!talking && !waking) return
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        if (waking) ui.postDelayed({ listen() }, 300) else miss()
                    } else if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        ui.postDelayed({ listen() }, 700)
                    } else if (waking && ++errs <= 5) {
                        ui.postDelayed({ listen() }, 2000)
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
            })
        }
    }

    private fun miss() {
        misses++
        if (misses >= 2) endTalk() else ui.postDelayed({ listen() }, 300)
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
            .put(fn("web_search", "Search the web for current information such as weather, news, scores, prices", "query" to "string"))
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
                    if (clean.isEmpty()) listen() else {
                        say("Bolchi...")
                        showBubble(clean.take(140))
                        speakOrListen(clean)
                    }
                }
            } catch (e: Exception) {
                while (history.length() > mark) history.remove(history.length() - 1)
                ui.post { say("Error: ${e.message}"); addLog("Error", e.message ?: ""); endTalk() }
            }
        }
    }

    private fun post(key: String, body: JSONObject): String {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
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
        if (!ok) throw Exception("API ${c.responseCode}: ${txt.take(200)}")
        return txt
    }

    private fun callGemini(key: String): JSONObject {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", "${sys()} Current date and time: $now."))))
            .put("contents", history)
            .put("tools", tools)
        for (i in 1..3) {
            try {
                val txt = post(key, body)
                val content = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .optJSONObject("content") ?: throw Exception("Empty reply from Gemini")
                if (!content.has("role")) content.put("role", "model")
                return content
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (i == 3 || !(m.contains("503") || m.contains("timeout"))) throw e
                Thread.sleep(2000)
            }
        }
        throw Exception("Gemini error")
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
        return "Search failed: ${last?.message}"
    }

    // ---------- Tools ----------

    private fun launch(i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = JarvisAccess.inst ?: this
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    private fun runTool(name: String, a: JSONObject): String {
        return when (name) {
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

    private fun resolveNumber(who: String): String? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6 && who.none { it.isLetter() }) return digits
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$who%"), null)
        c?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun makeCall(who: String): String {
        val num = resolveNumber(who) ?: return "No number found for $who (contacts permission may be missing)"
        if (!confirm(tr("$who কে ফোন করব?", "Call $who?"))) return "User declined"
        val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))
        return if (launch(i)) "Calling $who" else "Could not start the call"
    }

    private fun sendSms(to: String, body: String): String {
        val num = resolveNumber(to) ?: return "No number found for $to (contacts permission may be missing)"
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "SMS permission is missing"
        if (!confirm(tr("$to কে মেসেজ পাঠাব: $body ?", "Send to $to: $body ?"))) return "User declined"
        val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
        sm.sendMultipartTextMessage(num, null, sm.divideMessage(body), null, null)
        return "SMS sent"
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
            "type_text" -> { val r = s.typeText(a.optString("text")); Thread.sleep(400); r }
            "scroll" -> { val r = s.scroll(a.optString("direction") != "up"); Thread.sleep(600); r }
            else -> s.key(a.optString("key"))
        }
    }

    // ---------- End ----------

    private fun endTalk() {
        talking = false
        endAfter = false
        confirmQ?.offer(false)
        hideBubble()
        val r = rec
        rec = null
        ui.post { r?.cancel(); r?.destroy() }
        tts.stop()
        ui.postDelayed({ startWake() }, 600)
    }

    override fun onDestroy() {
        running = false
        talking = false
        waking = false
        confirmQ?.offer(false)
        ui.removeCallbacksAndMessages(null)
        hideBubble()
        stopVosk()
        try { vosk?.close() } catch (e: Exception) {}
        rec?.destroy()
        tts.shutdown()
        say("Jarvis bondho")
        super.onDestroy()
    }
}
