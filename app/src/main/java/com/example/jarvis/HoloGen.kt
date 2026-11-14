package com.example.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object HoloGen {
    private val models = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    private val spec = """
You design 3D wireframe holograms. Reply with ONE JSON object only, no markdown, no explanation.
Schema: {"name":"short title","parts":[ ...parts... ]}
Each part: {"t":TYPE,"p":[x,y,z],"s":[sx,sy,sz],"r":[rx,ry,rz]}
TYPE and size meaning:
- box: s = width, height, depth
- cyl: s = diameter x, height, diameter z (axis is y, up)
- cone: s = base diameter x, height, base diameter z (tip points up)
- sph: s = diameter x, y, z (ellipsoids allowed)
- tor: s = [outer diameter, tube diameter], a flat ring with axis up
- pyr: square pyramid, s = base x, height, base z
- line: use "a":[x,y,z] and "b":[x,y,z] instead of p, s, r
p = centre of the part. r = rotation in degrees about x, y, z (applied in that order).
y is UP. To lay a cyl or cone along x use r=[0,0,90], along z use r=[90,0,0]. To stand a ring up use r=[90,0,0].
Keep the whole object inside about -1.5 to 1.5. Parts should overlap and connect like real construction.
Use 12 to 40 parts. Make the object clearly recognisable with real details (fins, wheels, windows, panels, struts). Mirror symmetric parts. Prefer cyl, cone, box, sph, tor.
Example (rocket):
{"name":"rocket","parts":[{"t":"cyl","p":[0,0,0],"s":[0.5,1.4,0.5]},{"t":"cone","p":[0,0.95,0],"s":[0.5,0.55,0.5]},{"t":"cyl","p":[0,-0.85,0],"s":[0.38,0.3,0.38]},{"t":"box","p":[0.4,-0.5,0],"s":[0.45,0.6,0.05]},{"t":"box","p":[-0.4,-0.5,0],"s":[0.45,0.6,0.05]},{"t":"box","p":[0,-0.5,0.4],"s":[0.05,0.6,0.45]},{"t":"box","p":[0,-0.5,-0.4],"s":[0.05,0.6,0.45]},{"t":"tor","p":[0,0.3,0],"s":[0.56,0.04]},{"t":"sph","p":[0,0.3,0.25],"s":[0.18,0.18,0.1]}]}
""".trimIndent()

    fun generate(ctx: Context, desc: String, previous: String?): String {
        val key = ctx.getSharedPreferences("j", Context.MODE_PRIVATE).getString("k", "")?.trim().orEmpty()
        if (key.isEmpty()) throw Exception("Gemini key nai, main Jarvis app e dao")
        val prompt = if (previous.isNullOrBlank()) spec + "\n\nObject to design: " + desc
        else spec + "\n\nCurrent model:\n" + previous +
            "\n\nChange it as requested and return the complete new JSON.\nRequest: " + desc
        var last: Exception? = null
        for (m in models) {
            try {
                return call(m, key, prompt, true)
            } catch (e: Exception) {
                last = e
                if ((e.message ?: "").contains("API 400")) {
                    try { return call(m, key, prompt, false) } catch (e2: Exception) { last = e2 }
                }
            }
        }
        throw last ?: Exception("Gemini error")
    }

    private fun call(model: String, key: String, prompt: String, json: Boolean): String {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) throw Exception("API ${c.responseCode}: ${txt.take(300)}")
        val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
        }
        return sb.toString()
    }
}
