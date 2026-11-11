package com.example.jarvis

import android.content.Context
import org.json.JSONArray

object JarvisMemory {
    private fun prefs(c: Context) = c.getSharedPreferences("mem", Context.MODE_PRIVATE)

    private fun load(c: Context): JSONArray =
        try { JSONArray(prefs(c).getString("notes", "[]")) } catch (e: Exception) { JSONArray() }

    private fun save(c: Context, a: JSONArray) {
        prefs(c).edit().putString("notes", a.toString()).apply()
    }

    fun list(c: Context): List<String> {
        val a = load(c)
        return List(a.length()) { a.getString(it) }
    }

    fun add(c: Context, fact: String): String {
        val f = fact.trim().take(200)
        if (f.isEmpty()) return "Nothing to remember"
        val a = load(c)
        for (i in 0 until a.length()) if (a.getString(i).equals(f, true)) return "Already remembered"
        val out = JSONArray()
        val start = if (a.length() >= 100) a.length() - 99 else 0
        for (i in start until a.length()) out.put(a.getString(i))
        out.put(f)
        save(c, out)
        return "Remembered"
    }

    fun forget(c: Context, key: String): String {
        val k = key.trim().lowercase()
        if (k.isEmpty()) return "Say what to forget"
        val a = load(c)
        if (k == "all" || k == "everything") {
            save(c, JSONArray())
            return "Forgot everything (${a.length()} notes)"
        }
        val out = JSONArray()
        var n = 0
        for (i in 0 until a.length()) {
            val s = a.getString(i)
            if (s.lowercase().contains(k)) n++ else out.put(s)
        }
        save(c, out)
        return if (n == 0) "No matching memory" else "Forgot $n note(s)"
    }

    fun prompt(c: Context): String {
        val notes = list(c)
        var s = " You have a long-term memory. When the user tells you something worth keeping for later " +
            "(name, family, preferences, routines, important dates), call remember with a short fact, without being asked. " +
            "Never remember passwords, OTPs, PINs or card numbers. " +
            "When asked what you remember, call list_memories. " +
            "When asked to forget something, call forget (use all to clear everything)."
        if (notes.isNotEmpty()) s += " Things you remember about the user: " + notes.joinToString("; ") + "."
        return s
    }
}
