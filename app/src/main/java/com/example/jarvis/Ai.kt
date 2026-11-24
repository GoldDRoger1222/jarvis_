package com.example.jarvis

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object Hud {
    val bg = Color.rgb(5, 8, 15)
    val cardC = Color.rgb(11, 18, 32)
    val cyan = Color.rgb(0, 229, 255)
    val dim = Color.rgb(110, 140, 155)
    val txt = Color.rgb(216, 243, 255)

    fun dp(c: Context, x: Int) = (x * c.resources.displayMetrics.density).toInt()

    fun box(c: Context, fill: Int, stroke: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, r).toFloat()
        g.setStroke(maxOf(1, dp(c, 1)), stroke)
        return g
    }

    fun lp(c: Context, top: Int = 8): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(-1, -2)
        l.topMargin = dp(c, top)
        return l
    }

    fun btn(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(if (primary) Color.WHITE else cyan)
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        b.background = box(c, if (primary) Color.argb(110, 0, 229, 255) else Color.argb(28, 0, 229, 255), cyan, 12)
        b.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        b.setOnClickListener { onClick() }
        return b
    }

    fun edit(c: Context, hint: String, secret: Boolean = false, multi: Boolean = false): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(dim)
        e.setTextColor(txt)
        e.textSize = 14f
        e.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else InputType.TYPE_CLASS_TEXT
        e.background = box(c, Color.argb(40, 0, 0, 0), Color.argb(90, 0, 229, 255), 10)
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        return e
    }

    fun toast(c: Context, s: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(c, s, Toast.LENGTH_LONG).show() }
    }
}

fun declFn(name: String, desc: String, vararg p: Pair<String, String>): JSONObject {
    val o = JSONObject().put("name", name).put("description", desc)
    if (p.isNotEmpty()) {
        val props = JSONObject()
        val req = JSONArray()
        for ((k, t) in p) {
            props.put(k, JSONObject().put("type", t))
            req.put(k)
        }
        o.put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
    }
    return o
}

object Ai {
    @Volatile var lastProvider = ""
    private val geminiLadder = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")
    private val cool = ConcurrentHashMap<String, Long>()
    private val dead = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var orCache: List<String> = emptyList()
    @Volatile private var orAt = 0L
    @Volatile private var liveCache: String? = null

    private fun sp(c: Context) = c.getSharedPreferences("j", Context.MODE_PRIVATE)

    fun orKey(c: Context): String {
        val e = sp(c).getString("okx", null) ?: return ""
        return Secure.dec(e) ?: ""
    }

    fun setOrKey(c: Context, k: String) {
        val t = k.trim()
        if (t.isEmpty()) sp(c).edit().remove("okx").apply()
        else try { sp(c).edit().putString("okx", Secure.enc(t)).apply() } catch (e: Exception) { }
    }

    fun httpPost(url: String, headers: Map<String, String>, body: String, ms: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.connectTimeout = 15000
            c.readTimeout = ms
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
            val ok = c.responseCode in 200..299
            val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!ok) throw Exception("API ${c.responseCode}: ${txt.take(800)}")
            return txt
        } finally {
            c.disconnect()
        }
    }

    fun httpGet(url: String, headers: Map<String, String>, ms: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.connectTimeout = 15000
            c.readTimeout = ms
            val ok = c.responseCode in 200..299
            val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!ok) throw Exception("API ${c.responseCode}: ${txt.take(500)}")
            return txt
        } finally {
            c.disconnect()
        }
    }

    fun text(c: Context, prompt: String, json: Boolean = false, maxMs: Int = 90000): String {
        val order = when (sp(c).getInt("ai_mode", 2)) {
            0 -> listOf("g")
            1 -> listOf("o")
            3 -> listOf("o", "g")
            else -> listOf("g", "o")
        }
        var last: Exception? = null
        for (p in order) {
            try {
                val r = if (p == "g") gemini(c, prompt, json, maxMs) else openRouter(c, prompt, json, maxMs)
                lastProvider = if (p == "g") "Gemini" else "OpenRouter"
                return r
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("AI provider nai")
    }

    private fun mark(id: String, e: Exception) {
        val msg = e.message ?: ""
        val t = System.currentTimeMillis()
        if (msg.contains("API 404")) dead.add(id)
        else if (msg.contains("API 429")) cool[id] = t + (if (msg.contains("PerDay")) 3600000L else 60000L)
        else cool[id] = t + 150000L
    }

    private fun gemini(c: Context, prompt: String, json: Boolean, ms: Int): String {
        val key = Secure.prefs(c).getString("k", "")?.trim().orEmpty()
        if (key.isEmpty()) throw Exception("Gemini key nai")
        var last: Exception? = null
        for (m in geminiLadder) {
            val id = "g:$m"
            if (dead.contains(id)) continue
            if ((cool[id] ?: 0L) > System.currentTimeMillis()) continue
            try {
                return try {
                    postG(m, key, prompt, json, ms)
                } catch (e: Exception) {
                    if (json && (e.message ?: "").contains("API 400")) postG(m, key, prompt, false, ms) else throw e
                }
            } catch (e: Exception) {
                last = e
                mark(id, e)
            }
        }
        throw last ?: Exception("Gemini busy")
    }

    private fun postG(m: String, key: String, prompt: String, json: Boolean, ms: Int): String {
        val b = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (json) b.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val txt = httpPost("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
            mapOf("x-goog-api-key" to key), b.toString(), ms)
        val ps = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
        }
        if (sb.isBlank()) throw Exception("Empty reply")
        return sb.toString()
    }

    private fun orModels(c: Context): List<String> {
        val m = sp(c).getString("or_model", "")?.trim().orEmpty()
        if (m.isNotEmpty()) return listOf(m)
        if (orCache.isNotEmpty() && System.currentTimeMillis() - orAt < 3600000L) return orCache
        val data = JSONObject(httpGet("https://openrouter.ai/api/v1/models", emptyMap())).getJSONArray("data")
        val list = ArrayList<Pair<String, Int>>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val id = o.optString("id")
            if (id.isEmpty() || id.contains("embed")) continue
            val pr = o.optJSONObject("pricing")
            val free = id.endsWith(":free") ||
                (pr != null && pr.optString("prompt") == "0" && pr.optString("completion") == "0")
            if (!free) continue
            var score = o.optInt("context_length", 0) / 1000
            for (pf in listOf("gemini", "llama-3.3", "deepseek", "qwen", "gpt-oss", "mistral")) {
                if (id.contains(pf)) score += 100
            }
            list.add(Pair(id, score))
        }
        val out = list.sortedByDescending { it.second }.take(4).map { it.first }
        if (out.isEmpty()) throw Exception("OpenRouter e free model pelam na, AI settings e model nam dao")
        orCache = out
        orAt = System.currentTimeMillis()
        return out
    }

    private fun openRouter(c: Context, prompt: String, json: Boolean, ms: Int): String {
        val key = orKey(c)
        if (key.isEmpty()) throw Exception("OpenRouter key nai")
        var last: Exception? = null
        for (m in orModels(c)) {
            val id = "o:$m"
            if ((cool[id] ?: 0L) > System.currentTimeMillis()) continue
            try {
                return try {
                    postO(m, key, prompt, json, ms)
                } catch (e: Exception) {
                    if (json && (e.message ?: "").contains("API 400")) postO(m, key, prompt, false, ms) else throw e
                }
            } catch (e: Exception) {
                last = e
                if ((e.message ?: "").contains("API 401")) throw e
                cool[id] = System.currentTimeMillis() + (if ((e.message ?: "").contains("API 429")) 90000L else 180000L)
            }
        }
        throw last ?: Exception("OpenRouter busy")
    }

    private fun postO(m: String, key: String, prompt: String, json: Boolean, ms: Int): String {
        val b = JSONObject().put("model", m)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.4)
            .put("max_tokens", 4000)
        if (json) b.put("response_format", JSONObject().put("type", "json_object"))
        val txt = httpPost("https://openrouter.ai/api/v1/chat/completions",
            mapOf("Authorization" to "Bearer $key", "HTTP-Referer" to "https://github.com/jarvis-app",
                "X-Title" to "Jarvis"), b.toString(), ms)
        val j = JSONObject(txt)
        val err = j.optJSONObject("error")
        if (err != null) throw Exception("API " + err.optInt("code", 500) + ": " + err.optString("message"))
        val msg = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val out = if (msg.isNull("content")) "" else msg.getString("content")
        if (out.isBlank()) throw Exception("Empty reply")
        return out
    }

    fun liveModel(c: Context): String {
        val man = sp(c).getString("live_model", "")?.trim().orEmpty()
        if (man.isNotEmpty()) return if (man.startsWith("models/")) man else "models/$man"
        val cached = liveCache
        if (cached != null) return cached
        val key = Secure.prefs(c).getString("k", "")?.trim().orEmpty()
        var best: String? = null
        try {
            val names = ArrayList<String>()
            var token = ""
            for (page in 0 until 3) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" +
                    (if (token.isEmpty()) "" else "&pageToken=$token")
                val j = JSONObject(httpGet(url, mapOf("x-goog-api-key" to key)))
                val arr = j.optJSONArray("models")
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    var live = false
                    if (methods != null) for (k in 0 until methods.length()) {
                        if (methods.optString(k) == "bidiGenerateContent") live = true
                    }
                    if (live) names.add(o.optString("name"))
                }
                token = j.optString("nextPageToken", "")
                if (token.isEmpty()) break
            }
            best = names.filter { it.contains("native-audio") }.maxOrNull()
                ?: names.filter { it.contains("live") }.maxOrNull()
                ?: names.maxOrNull()
        } catch (e: Exception) {
        }
        val m = best ?: "models/gemini-2.5-flash-native-audio-preview-09-2025"
        liveCache = m
        return m
    }
}

object Providers {
    private val modeNames = arrayOf("Gemini only", "OpenRouter only",
        "Auto: Gemini, then OpenRouter", "Auto: OpenRouter, then Gemini")
    private val voices = arrayOf("Puck", "Charon", "Kore", "Fenrir", "Aoede")

    fun show(c: Context) {
        val sp = c.getSharedPreferences("j", Context.MODE_PRIVATE)
        val d = Dialog(c)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val pad = Hud.dp(c, 16)
        box.setPadding(pad, pad, pad, pad)
        box.background = Hud.box(c, Color.argb(248, 6, 12, 22), Color.argb(150, 0, 229, 255), 20)
        val t = TextView(c)
        t.text = "AI PROVIDERS"
        t.setTextColor(Hud.cyan)
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.15f
        box.addView(t)
        val key = Hud.edit(c, "OpenRouter API key", true)
        key.setText(Ai.orKey(c))
        val model = Hud.edit(c, "OpenRouter model (khali = free auto)")
        model.setText(sp.getString("or_model", ""))
        val live = Hud.edit(c, "Live model (khali = auto)")
        live.setText(sp.getString("live_model", ""))
        val st = intArrayOf(sp.getInt("ai_mode", 2).coerceIn(0, 3), sp.getInt("live_voice", 0).coerceIn(0, 4))
        val modeB = Hud.btn(c, "") { }
        val voiceB = Hud.btn(c, "") { }
        fun labels() {
            modeB.text = "AI: " + modeNames[st[0]]
            voiceB.text = "Live voice: " + voices[st[1]]
        }
        modeB.setOnClickListener { st[0] = (st[0] + 1) % 4; labels() }
        voiceB.setOnClickListener { st[1] = (st[1] + 1) % 5; labels() }
        labels()
        fun persist() {
            Ai.setOrKey(c, key.text.toString())
            sp.edit().putString("or_model", model.text.toString().trim())
                .putString("live_model", live.text.toString().trim())
                .putInt("ai_mode", st[0]).putInt("live_voice", st[1]).apply()
        }
        val test = Hud.btn(c, "Save + Test") {
            persist()
            Hud.toast(c, "Test hocche...")
            thread {
                try {
                    Ai.text(c, "Reply with the single word OK", false, 45000)
                    Hud.toast(c, "Kaj korche: " + Ai.lastProvider)
                } catch (e: Exception) {
                    Hud.toast(c, "Error: " + (e.message ?: "").take(160))
                }
            }
        }
        val ok = Hud.btn(c, "Save", true) { persist(); d.dismiss() }
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.addView(key, Hud.lp(c, 10))
        col.addView(model, Hud.lp(c, 8))
        col.addView(live, Hud.lp(c, 8))
        col.addView(modeB, Hud.lp(c, 8))
        col.addView(voiceB, Hud.lp(c, 8))
        col.addView(test, Hud.lp(c, 8))
        col.addView(ok, Hud.lp(c, 8))
        val sv = ScrollView(c)
        sv.addView(col)
        box.addView(sv, LinearLayout.LayoutParams(-1, (c.resources.displayMetrics.heightPixels * 0.7f).toInt()))
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((c.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        d.show()
    }
}
