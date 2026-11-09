package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service() {
    companion object {
        var running = false
        val logBuf = StringBuilder()
        var statusText = ""
        var onLog: ((String) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    private val model = "gemini-3.5-flash-lite"
    private val system = "You are Jarvis, a friendly voice assistant on an Android phone. " +
        "Reply in the same language the user speaks (Bangla or English). " +
        "Keep answers short and conversational, 1 to 3 sentences. No markdown, no emojis, no lists."
    private val wakeWords = listOf("jarvis", "jarvice", "jervis", "javis",
        "জার্ভিস", "জারভিস", "জার্বিস", "জারবিস")

    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var rec: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var waking = false
    private var talking = false
    private var endAfter = false
    private var misses = 0
    private var errs = 0

    private fun prefs() = getSharedPreferences("j", MODE_PRIVATE)
    private fun lang() = prefs().getString("l", "bn-BD") ?: "bn-BD"
    private fun ack() = if (lang() == "bn-BD") "জি, বলুন" else "Yes?"

    private fun say(s: String) { statusText = s; onStatus?.invoke(s) }
    private fun addLog(who: String, t: String) {
        val l = "$who: $t\n\n"
        logBuf.append(l)
        onLog?.invoke(l)
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

    private fun startWake() {
        if (talking) return
        waking = true
        errs = 0
        say("Jarvis dak shunchi...")
        listen()
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
        waking = false
        talking = true
        endAfter = false
        misses = 0
        tts.setLanguage(Locale.forLanguageTag(lang()))
        if (rest.length > 2) handle(rest) else { say("Haan bolo..."); speakOrListen(ack()) }
    }

    private fun onWake() {
        if (talking) return
        ui.removeCallbacksAndMessages(null)
        rec?.cancel()
        waking = false
        talking = true
        endAfter = false
        misses = 0
        tts.setLanguage(Locale.forLanguageTag(lang()))
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
        val stopWords = setOf("stop", "goodbye", "thamo", "bondho", "বন্ধ", "থামো")
        if (stopWords.contains(t.lowercase().trim().trimEnd('.'))) {
            endAfter = true
            speakOrListen(if (lang() == "bn-BD") "ঠিক আছে" else "Okay")
            return
        }
        ask(t)
    }

    private fun msg(role: String, text: String) = JSONObject().put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun ask(text: String) {
        history.put(msg("user", text))
        say("Bhabchi...")
        val key = prefs().getString("k", "")?.trim() ?: ""
        thread {
            try {
                var raw = ""
                for (i in 1..3) {
                    try { raw = gemini(key); break } catch (e: Exception) {
                        val m = e.message ?: ""
                        if (i == 3 || !(m.contains("503") || m.contains("timeout"))) throw e
                        Thread.sleep(2000)
                    }
                }
                val reply = raw.replace("*", "").trim()
                ui.post {
                    if (!talking) return@post
                    history.put(msg("model", reply))
                    if (history.length() > 20) { history.remove(0); history.remove(0) }
                    addLog("Jarvis", reply)
                    if (reply.isEmpty()) listen() else { say("Bolchi..."); speakOrListen(reply) }
                }
            } catch (e: Exception) {
                ui.post { say("Error: ${e.message}"); addLog("Error", e.message ?: ""); endTalk() }
            }
        }
    }

    private fun gemini(key: String): String {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
            .put("contents", history)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) throw Exception("API ${c.responseCode}: ${txt.take(200)}")
        return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun endTalk() {
        talking = false
        endAfter = false
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
        ui.removeCallbacksAndMessages(null)
        rec?.destroy()
        tts.shutdown()
        say("Jarvis bondho")
        super.onDestroy()
    }
}
