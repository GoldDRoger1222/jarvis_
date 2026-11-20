package com.example.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object HoloGen {
    private val smart = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    private val fast = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")
    private val cool = ConcurrentHashMap<String, Long>()
    private val dead = ConcurrentHashMap.newKeySet<String>()
    private val guideCache = ConcurrentHashMap<String, String>()

    private val spec = """
You are a senior mechanical designer. Build an ACCURATE 3D hologram blueprint of the requested object from simple primitives. Reply with ONE JSON object only. No markdown, no explanation.
Schema: {"name":"short title","parts":[ ...parts... ]}
Part: {"t":TYPE,"n":"real part name","g":"component name","c":COLOR,"p":[x,y,z],"s":[sx,sy,sz],"r":[rx,ry,rz],"a":ANIMATION}
TYPE and size meaning:
- box: s = width, height, depth
- cyl: s = diameter x, height, diameter z (axis is y)
- cone: s = base diameter x, height, base diameter z (tip points up)
- sph: s = diameter x, y, z
- tor: s = [outer diameter, tube diameter], a flat ring with axis up
- pyr: square pyramid, s = base x, height, base z
- line: use "a":[x,y,z] and "b":[x,y,z] instead of p, s, r
Optional "seg" (3 to 48) sets how many sides a cyl, cone, sph or tor has (6 for hex bolts and nuts, 24 for round things, 12 for gear-like discs).
p = centre of the part. r = rotation in degrees about x, y, z in that order. y is UP. To lay a cyl or cone along x use r=[0,0,90], along z use r=[90,0,0]. To stand a ring up use r=[90,0,0].
n = what a real engineer calls the part (Piston, Connecting rod, Intake valve, Rotor blade, Battery cell).
g = the component the part belongs to. All parts of one component share the exact same g text (every piece of cylinder 1 uses g="Cylinder 1 assembly"). Use 5 to 14 components.
c = colour role: body (cyan, main structure), metal (silver: shafts, bolts, gears), accent (orange: key or moving parts to highlight), hot (red: heat, combustion, brakes, exhaust), glass (white: windows, lenses, screens), power (yellow: electrics, batteries, fuel), green, blue (coolant, water, air intake), dark (minor details).
a = animation, only for parts that really move:
 {"k":"rot","axis":[x,y,z],"o":[x,y,z],"dps":degrees per second} for wheels, fans, propellers, rotors, gears, crankshafts, turbines, drums. o is the pivot (default is the part centre).
 {"k":"osc","axis":[x,y,z],"amp":distance,"hz":cycles per second,"ph":phase in degrees} for pistons, valves, pumps, plungers, sliding doors. In an engine a piston hz equals crankshaft dps divided by 360, and the pistons of a multi cylinder engine get different ph values.
 {"k":"swing","axis":[x,y,z],"o":[x,y,z],"amp":degrees,"hz":cycles per second,"ph":phase in degrees} for connecting rods, wipers, flaps, robot arms, pendulums.
Rules:
- Model the REAL object accurately with correct proportions, real layout and its internal mechanism visible (an engine shows block, cylinders, pistons, rods, crankshaft, valves, camshaft, flywheel, spark plugs; a drone shows frame, arms, motors, spinning propellers, battery, flight controller, camera).
- Use 40 to 100 parts. Keep the whole object inside about -1.5 to 1.5.
- Moving parts must really move (spin, slide or swing) at believable speeds, and meshing gears turn in opposite directions.
- If the object is fictional or an invention, design a plausible working version with a visible mechanism.
- Design it so that it looks good in an exploded view: build it from clearly separate sub-assemblies (g), each a coherent component such as Turbine assembly, Fuel injector, Piston and rod, Gear train, Housing, Crankshaft.
- Round parts such as gears, flanges and turbine discs: use cyl with seg 24 or more plus tor rings for rims, and add thin boxes as blades or spokes around them. Make small parts such as bolts, injectors and pins separate small cyl or box parts.
- Use colour roles to separate materials: metal for housings and gears, accent for key moving parts, glass for transparent covers, power for energy parts.
Example (single cylinder engine, shortened):
{"name":"single cylinder engine","parts":[{"t":"box","n":"Crankcase","g":"Engine block","c":"metal","p":[0,-0.45,0],"s":[1.1,0.6,0.7]},{"t":"cyl","n":"Cylinder barrel","g":"Engine block","c":"metal","p":[0,0.2,0],"s":[0.5,0.8,0.5]},{"t":"cyl","n":"Cylinder head","g":"Cylinder head","c":"body","p":[0,0.7,0],"s":[0.56,0.2,0.56]},{"t":"cyl","n":"Piston","g":"Piston assembly","c":"accent","p":[0,0.05,0],"s":[0.46,0.22,0.46],"a":{"k":"osc","axis":[0,1,0],"amp":0.2,"hz":1.5}},{"t":"cyl","n":"Crankshaft","g":"Crank assembly","c":"hot","p":[0,-0.45,0],"s":[0.12,1.2,0.12],"r":[0,0,90],"a":{"k":"rot","axis":[1,0,0],"dps":540}},{"t":"cyl","n":"Flywheel","g":"Crank assembly","c":"power","p":[0.62,-0.45,0],"s":[0.7,0.1,0.7],"r":[0,0,90],"a":{"k":"rot","axis":[1,0,0],"o":[0.62,-0.45,0],"dps":540}},{"t":"cyl","n":"Spark plug","g":"Cylinder head","c":"glass","p":[0.15,0.88,0],"s":[0.07,0.2,0.07]}]}
""".trimIndent()

    fun generate(ctx: Context, desc: String, previous: String?): String {
        val key = Secure.prefs(ctx).getString("k", "")?.trim().orEmpty()
        if (key.isEmpty()) throw Exception("Gemini key nai, main Jarvis app e dao")
        val prompt = if (previous.isNullOrBlank()) spec + "\n\nObject to design: " + desc
        else spec + "\n\nCurrent model:\n" + previous +
            "\n\nChange it as requested and return the complete new JSON.\nRequest: " + desc
        return ask(key, prompt, true, smart, null, 170000L, 80000L)
    }

    fun guide(
        ctx: Context,
        model: String,
        group: String,
        parts: List<String>,
        groups: List<String>,
        status: ((String) -> Unit)? = null
    ): String {
        val sp = Secure.prefs(ctx)
        val key = sp.getString("k", "")?.trim().orEmpty()
        if (key.isEmpty()) throw Exception("Gemini key nai, main Jarvis app e dao")
        val bn = (sp.getString("l", "bn-BD") ?: "bn-BD") == "bn-BD"
        val ck = (if (bn) "bn|" else "en|") + model + "|" + group
        val cached = guideCache[ck]
        if (cached != null) return cached
        val lang = if (bn) "Bangla (Bengali script), keeping technical terms and part names in English" else "English"
        val prompt = "You are a senior engineer explaining to a hobbyist how to build one component of a real object.\n" +
            "Object: " + model + "\n" +
            "Component: " + group + "\n" +
            "Its parts: " + parts.joinToString(", ") + "\n" +
            "Other components of the object: " + groups.joinToString(", ") + "\n\n" +
            "Write in " + lang + ". Plain text only, no markdown, no asterisks, no tables. Use these short CAPS headings:\n" +
            "WHAT IT DOES (2 sentences)\n" +
            "SPECS (realistic dimensions, material, weight, power or speed if relevant)\n" +
            "WHAT YOU NEED (numbered list: parts with quantity and material, tools, and which parts can be bought as standard parts)\n" +
            "HOW TO BUILD (short numbered steps, concrete, with sizes and tolerances)\n" +
            "HOW IT CONNECTS (to which other components, and how it moves)\n" +
            "COMMON MISTAKES (3 short points)\n" +
            "Keep it under 350 words. Be accurate and practical. If the object is fictional, say so in one line and describe a plausible version. Mention safety for anything electrical, fuel or high speed."
        val out = ask(key, prompt, false, fast, status, 100000L, 40000L).trim()
        if (out.isNotEmpty()) guideCache[ck] = out
        return out
    }

    private fun ask(
        key: String,
        prompt: String,
        json: Boolean,
        ladder: List<String>,
        status: ((String) -> Unit)?,
        budgetMs: Long,
        perMs: Long
    ): String {
        val t0 = System.currentTimeMillis()
        var last: Exception? = null
        for (round in 1..2) {
            val now = System.currentTimeMillis()
            for ((i, m) in ladder.withIndex()) {
                if (dead.contains(m)) continue
                if ((cool[m] ?: 0L) > now && i < ladder.size - 1) continue
                val left = budgetMs - (System.currentTimeMillis() - t0)
                if (left < 8000L) break
                status?.invoke("Model: " + m)
                try {
                    return call(m, key, prompt, json, minOf(left, perMs).toInt())
                } catch (e: Exception) {
                    last = e
                    val msg = e.message ?: ""
                    val t = System.currentTimeMillis()
                    if (msg.contains("API 404")) dead.add(m)
                    else if (msg.contains("API 429")) cool[m] = t + (if (msg.contains("PerDay")) 3600000L else 60000L)
                    else cool[m] = t + 300000L
                    status?.invoke("Model " + m + " pelam na, onno model e jachchi...")
                }
            }
            if (round == 1) {
                if (System.currentTimeMillis() - t0 > budgetMs - 12000L) break
                status?.invoke("Gemini busy, 6s por abar chesta...")
                Thread.sleep(6000)
                cool.clear()
            }
        }
        throw last ?: Exception("Gemini error")
    }

    private fun call(model: String, key: String, prompt: String, json: Boolean, timeoutMs: Int): String {
        try {
            return post(model, key, prompt, json, true, timeoutMs)
        } catch (e: Exception) {
            if ((e.message ?: "").contains("API 400")) return post(model, key, prompt, false, false, timeoutMs)
            throw e
        }
    }

    private fun post(
        model: String,
        key: String,
        prompt: String,
        json: Boolean,
        think: Boolean,
        timeoutMs: Int
    ): String {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        val cfg = JSONObject()
        if (json) cfg.put("responseMimeType", "application/json")
        if (think) cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        if (cfg.length() > 0) body.put("generationConfig", cfg)
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.connectTimeout = 15000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
            if (!ok) throw Exception("API ${c.responseCode}: ${txt.take(1200)}")
            val ps = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                if (!p.optBoolean("thought")) sb.append(p.optString("text"))
            }
            val out = sb.toString()
            if (out.isBlank()) throw Exception("Empty reply")
            return out
        } finally {
            c.disconnect()
        }
    }
}
