package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val model = "gemini-3.8-flash"
    private val system = "You are Jarvis, a friendly voice assistant on an Android phone. " +
        "Reply in the same language the user speaks (Bangla or English). " +
        "Keep answers short and conversational, 1 to 3 sentences. No markdown, no emojis, no lists."

    private lateinit var tts: TextToSpeech
    private var rec: SpeechRecognizer? = null
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var active = false
    private var lang = "bn-BD"

    private lateinit var keyBox: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var mic: Button
    private lateinit var langBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        keyBox = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("j", MODE_PRIVATE).getString("k", ""))
        }
        langBtn = Button(this).apply {
            text = "Bhasha: Bangla"
            setOnClickListener { toggleLang() }
        }
        status = TextView(this).apply { text = "Mic tap koro"; textSize = 16f }
        log = TextView(this).apply { textSize = 15f }
        scroll = ScrollView(this).apply { addView(log) }
        mic = Button(this).apply {
            text = "Shuru"
            textSize = 20f
            setOnClickListener { toggle() }
        }
        root.addView(keyBox)
        root.addView(langBtn)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(mic, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        tts = TextToSpeech(this) { ui.post { applyLang() } }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { ui.post { if (active) listen() } }
            override fun onError(id: String?) { ui.post { if (active) listen() } }
        })
    }

    private fun applyLang() {
        val r = tts.setLanguage(Locale.forLanguageTag(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            Toast.makeText(this, "Google TTS e $lang voice install koro", Toast.LENGTH_LONG).show()
    }

    private fun toggleLang() {
        lang = if (lang == "bn-BD") "en-US" else "bn-BD"
        langBtn.text = if (lang == "bn-BD") "Bhasha: Bangla" else "Bhasha: English"
        applyLang()
    }

    private fun toggle() {
        if (active) { stopAll(); return }
        val key = keyBox.text.toString().trim()
        if (key.isEmpty()) { status.text = "Age API key dao"; return }
        getSharedPreferences("j", MODE_PRIVATE).edit().putString("k", key).apply()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        active = true
        mic.text = "Thamo"
        listen()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        status.text = if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED)
            "Abar Shuru tap koro" else "Mic permission dao"
    }

    private fun listen() {
        if (!active) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Phone e speech service nai"; stopAll(); return
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text = "Shunchi..." }
                override fun onResults(r: Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) ui.postDelayed({ listen() }, 300) else ask(t)
                }
                override fun onError(e: Int) {
                    if (!active) return
                    if (e == SpeechRecognizer.ERROR_NO_MATCH ||
                        e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                        ui.postDelayed({ listen() }, 500)
                    else { status.text = "Mic error $e"; stopAll() }
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            })
        }
    }

    private fun msg(role: String, text: String) = JSONObject().put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun add(who: String, text: String) {
        log.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun ask(text: String) {
        add("Tumi", text)
        history.put(msg("user", text))
        status.text = "Bhabchi..."
        val key = keyBox.text.toString().trim()
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
                    history.put(msg("model", reply))
                    add("Jarvis", reply)
                    if (reply.isEmpty()) listen() else {
                        status.text = "Bolchi..."
                        tts.speak(reply.take(3500), TextToSpeech.QUEUE_FLUSH, null, "j")
                    }
                }
            } catch (e: Exception) {
                ui.post { status.text = "Error: ${e.message}"; stopAll() }
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

    private fun stopAll() {
        active = false
        rec?.cancel()
        tts.stop()
        mic.text = "Shuru"
    }

    override fun onDestroy() {
        rec?.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
