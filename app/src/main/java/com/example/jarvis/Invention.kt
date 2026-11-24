package com.example.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Invention {
    private fun dir(c: Context): File {
        val d = File(c.filesDir, "invent")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun obj(t: String): JSONObject {
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) throw Exception("Plan e JSON pelam na")
        val o = JSONObject(t.substring(s, e + 1))
        if (o.optJSONArray("parts") == null || o.optJSONArray("steps") == null) throw Exception("Plan adhura")
        return o
    }

    private fun strip(t: String): String {
        var s = t.trim()
        if (s.startsWith("```")) s = s.substringAfter('\n', s)
        if (s.endsWith("```")) s = s.substringBeforeLast("```")
        return s.trim()
    }

    fun generate(c: Context, idea: String, st: (String) -> Unit): JSONObject {
        val bn = (Secure.prefs(c).getString("l", "bn-BD") ?: "bn-BD") == "bn-BD"
        val lang = if (bn) "Bangla (Bengali script; keep part names, units and technical terms in English)" else "English"
        st("1/4 Plan toiri hocche...")
        val promptA = "You are an expert maker and engineer who knows the Bangladesh market. " +
            "The user wants to invent or build: \"" + idea + "\".\n" +
            "Reply with ONE JSON object only (no markdown) with exactly these keys:\n" +
            "title (short), summary (3 sentences), difficulty (easy, medium or hard), time_hours (number), " +
            "budget_bdt (number, total estimate), " +
            "parts (array of {name, qty (integer), spec, est_bdt (integer unit price in BDT), " +
            "where (kind of place to buy, for example online marketplace, local electronics market, hardware store; never invent shop names or URLs)}), " +
            "tools (array of strings), steps (array of 6 to 12 {title, detail} with concrete sizes), safety (array of strings), " +
            "needs_code (true if a microcontroller or software is needed), code_lang (arduino, python, micropython or none), " +
            "hologram (one English sentence describing the 3D model to show: main components and which parts move).\n" +
            "Prices are realistic estimates in BDT; write 'approx' in spec when unsure. " +
            "Write all text values in " + lang + ", but keep the hologram sentence in English."
        var p: JSONObject? = null
        var err: Exception? = null
        for (t in 1..2) {
            try {
                p = obj(Ai.text(c, promptA, true, 100000))
                break
            } catch (e: Exception) {
                err = e
            }
        }
        val proj = p ?: throw (err ?: Exception("Plan hoyni"))
        proj.put("idea", idea)
        proj.put("provider", Ai.lastProvider)
        val cl = proj.optString("code_lang", "none").lowercase()
        if (proj.optBoolean("needs_code", false) && cl != "none" && cl.isNotBlank()) {
            st("2/4 Code likhchi...")
            try {
                val names = StringBuilder()
                val pa = proj.optJSONArray("parts") ?: JSONArray()
                for (i in 0 until minOf(pa.length(), 20)) names.append(pa.getJSONObject(i).optString("name")).append(", ")
                val promptB = "Write complete, working, well commented " + cl + " code for this project.\n" +
                    "Project: " + proj.optString("title") + "\nParts: " + names + "\n" +
                    "Output ONLY the code with no markdown fences and no explanation. Write comments in " + lang + "."
                proj.put("code", strip(Ai.text(c, promptB, false, 100000)))
            } catch (e: Exception) {
                proj.put("code_error", e.message ?: "")
            }
        }
        st("3/4 3D hologram toiri hocche...")
        try {
            val h = HoloGen.generate(c, proj.optString("hologram", idea).ifBlank { idea }, null)
            HoloModel.parse(h)
            proj.put("holo", h)
        } catch (e: Exception) {
            proj.put("holo_error", e.message ?: "")
        }
        st("4/4 Save hocche...")
        save(c, proj)
        return proj
    }

    fun save(c: Context, p: JSONObject): String {
        val n = p.optString("title", "project").replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").trim().take(40)
            .ifBlank { "project" }
        File(dir(c), "$n.json").writeText(p.toString())
        val files = dir(c).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        for (i in 30 until files.size) files[i].delete()
        c.getSharedPreferences("j", Context.MODE_PRIVATE).edit().putString("inv_last", n).apply()
        return n
    }

    fun list(c: Context): List<String> {
        val files = dir(c).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(".json") }
    }

    fun load(c: Context, name: String): JSONObject? {
        val f = File(dir(c), "$name.json")
        return try { if (f.exists()) JSONObject(f.readText()) else null } catch (e: Exception) { null }
    }

    fun overview(p: JSONObject): String {
        val sb = StringBuilder()
        sb.append(p.optString("title")).append("\n\n").append(p.optString("summary")).append("\n\n")
        sb.append("Kothin: ").append(p.optString("difficulty")).append("   Shomoy: ")
            .append(p.optString("time_hours")).append(" ghonta   Bajet: ~")
            .append(p.optString("budget_bdt")).append(" BDT\n\n")
        val tl = p.optJSONArray("tools")
        if (tl != null && tl.length() > 0) {
            sb.append("TOOLS\n")
            for (i in 0 until tl.length()) sb.append("- ").append(tl.optString(i)).append("\n")
            sb.append("\n")
        }
        val sf = p.optJSONArray("safety")
        if (sf != null && sf.length() > 0) {
            sb.append("SAFETY\n")
            for (i in 0 until sf.length()) sb.append("- ").append(sf.optString(i)).append("\n")
        }
        return sb.toString()
    }

    fun parts(p: JSONObject): String {
        val a = p.optJSONArray("parts") ?: return "Parts nai"
        val sb = StringBuilder()
        var total = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val q = maxOf(1, o.optInt("qty", 1))
            val e = o.optInt("est_bdt", 0)
            total += q * e
            sb.append(i + 1).append(". ").append(q).append("x ").append(o.optString("name")).append("\n")
            sb.append("   ").append(o.optString("spec")).append("\n")
            sb.append("   ").append(e).append(" BDT x ").append(q).append(" = ").append(q * e)
                .append(" BDT  |  ").append(o.optString("where")).append("\n\n")
        }
        sb.append("MOT (parts): ~").append(total).append(" BDT (ondaj, dokane check koro)")
        return sb.toString()
    }

    fun plan(p: JSONObject): String {
        val a = p.optJSONArray("steps") ?: return "Steps nai"
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            sb.append(i + 1).append(". ").append(o.optString("title")).append("\n")
                .append(o.optString("detail")).append("\n\n")
        }
        return sb.toString()
    }

    fun code(p: JSONObject): String {
        val c = p.optString("code")
        if (c.isNotBlank()) return c
        val e = p.optString("code_error")
        return if (e.isNotBlank()) "Code toiri hoyni: $e" else "Ei project e code lage na."
    }

    fun markdown(p: JSONObject): String =
        "# " + p.optString("title") + "\n\n" + overview(p).substringAfter("\n\n") +
            "\n## Parts\n\n" + parts(p) + "\n\n## Build plan\n\n" + plan(p) +
            (if (p.optString("code").isNotBlank()) "\n## Code\n\n```\n" + p.optString("code") + "\n```\n" else "")
}
