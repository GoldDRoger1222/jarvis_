package com.example.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Lab {
    const val CAP = 30
    val types = arrayOf("Restaurant", "Cafe", "Fast food", "Bakery", "Hotel", "Salon", "Gym", "Clinic", "Shop")
    val statuses = arrayOf("new", "site_ready", "opened", "sent", "replied", "won", "dnc", "skip")
    private const val UA = "JarvisPersonalApp/1.0 (lead research for my own web design work)"
    private val cc = mapOf("bd" to "880", "in" to "91", "pk" to "92", "np" to "977", "lk" to "94",
        "us" to "1", "ca" to "1", "gb" to "44", "ae" to "971", "sa" to "966", "my" to "60", "sg" to "65",
        "au" to "61", "id" to "62", "th" to "66", "tr" to "90", "de" to "49", "fr" to "33", "it" to "39",
        "es" to "34", "nl" to "31", "qa" to "974", "kw" to "965", "om" to "968")

    private fun sp(c: Context) = c.getSharedPreferences("j", Context.MODE_PRIVATE)
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())

    fun clean(s: String): String =
        s.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifBlank { "x" }

    fun canOpen(c: Context): Boolean {
        val n = if (sp(c).getString("lab_day", "") == day()) sp(c).getInt("lab_n", 0) else 0
        return n < CAP
    }

    fun bump(c: Context) {
        val n = if (sp(c).getString("lab_day", "") == day()) sp(c).getInt("lab_n", 0) else 0
        sp(c).edit().putString("lab_day", day()).putInt("lab_n", n + 1).apply()
    }

    // ---------- storage ----------

    private fun dir(c: Context): File {
        val d = File(c.filesDir, "lab")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun siteFile(c: Context, camp: String, id: String): File {
        val d = File(dir(c), "sites")
        if (!d.exists()) d.mkdirs()
        return File(d, clean(camp) + "_" + clean(id) + ".html")
    }

    fun saveCamp(c: Context, o: JSONObject) {
        File(dir(c), o.getString("name") + ".json").writeText(o.toString())
    }

    fun listCamps(c: Context): List<String> {
        val f = dir(c).listFiles { x -> x.name.endsWith(".json") } ?: return emptyList()
        return f.sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(".json") }
    }

    fun loadCamp(c: Context, n: String): JSONObject? {
        val f = File(dir(c), "$n.json")
        return try { if (f.exists()) JSONObject(f.readText()) else null } catch (e: Exception) { null }
    }

    // ---------- lead search (OpenStreetMap) ----------

    private fun getText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val ok = c.responseCode in 200..299
            val t = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!ok) throw Exception("HTTP " + c.responseCode)
            return t
        } finally {
            c.disconnect()
        }
    }

    private fun overpass(q: String): String {
        var last: Exception? = null
        for (ep in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            val c = URL(ep).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.connectTimeout = 15000
                c.readTimeout = 90000
                c.doOutput = true
                c.outputStream.use { it.write(("data=" + enc(q)).toByteArray()) }
                val ok = c.responseCode in 200..299
                val t = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (ok) return t
                last = Exception("Map server HTTP " + c.responseCode)
            } catch (e: Exception) {
                last = e
            } finally {
                c.disconnect()
            }
        }
        throw last ?: Exception("Map server error")
    }

    private fun typeFilter(t: Int): String = when (t) {
        0 -> "[\"amenity\"=\"restaurant\"]"
        1 -> "[\"amenity\"=\"cafe\"]"
        2 -> "[\"amenity\"=\"fast_food\"]"
        3 -> "[\"shop\"=\"bakery\"]"
        4 -> "[\"tourism\"~\"hotel|guest_house|hostel\"]"
        5 -> "[\"shop\"~\"hairdresser|beauty\"]"
        6 -> "[\"leisure\"=\"fitness_centre\"]"
        7 -> "[\"amenity\"~\"clinic|dentist|doctors\"]"
        else -> "[\"shop\"]"
    }

    private fun first(t: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = t.optString(k).trim()
            if (v.isNotEmpty()) return v.split(';', ',')[0].trim()
        }
        return ""
    }

    private fun score(l: JSONObject): Int {
        var s = 10
        if (l.optString("email").isNotEmpty()) s += 30
        if (l.optString("phone").isNotEmpty()) s += 25
        if (l.optString("fb").isNotEmpty() || l.optString("ig").isNotEmpty()) s += 15
        if (l.optString("hours").isNotEmpty()) s += 10
        if (l.optString("cuisine").isNotEmpty()) s += 5
        if (l.optString("addr").isNotEmpty()) s += 5
        return minOf(100, s)
    }

    fun find(area: String, type: Int, count: Int, st: (String) -> Unit): JSONObject {
        st("Elaka khujchi...")
        val g = JSONArray(getText("https://nominatim.openstreetmap.org/search?format=json&limit=1&addressdetails=1&q=" +
            enc(area)))
        if (g.length() == 0) throw Exception("Elaka pelam na: " + area)
        val p = g.getJSONObject(0)
        val bb = p.getJSONArray("boundingbox")
        val s = bb.getString(0).toDouble()
        val n = bb.getString(1).toDouble()
        val w = bb.getString(2).toDouble()
        val e = bb.getString(3).toDouble()
        if (n - s > 1.2 || e - w > 1.2) throw Exception("Elaka onek boro. Shohor ba elaka dao (jemon: Gulshan, Dhaka)")
        val country = p.optJSONObject("address")?.optString("country_code", "") ?: ""
        st("Map data niye ashchi (30-90 second)...")
        val f = typeFilter(type)
        val nw = "[!\"website\"][!\"contact:website\"][!\"url\"][!\"contact:url\"]"
        val bx = s.toString() + "," + w + "," + n + "," + e
        val q = "[out:json][timeout:60];(node" + f + "[\"name\"]" + nw + "(" + bx + ");way" + f +
            "[\"name\"]" + nw + "(" + bx + "););out center tags 700;"
        val els = JSONObject(overpass(q)).optJSONArray("elements") ?: JSONArray()
        val seen = HashSet<String>()
        val leads = ArrayList<JSONObject>()
        for (i in 0 until els.length()) {
            val el = els.getJSONObject(i)
            val tg = el.optJSONObject("tags") ?: continue
            val name = tg.optString("name").trim()
            if (name.isEmpty()) continue
            val ctr = el.optJSONObject("center")
            val lat = if (el.has("lat")) el.optDouble("lat") else (ctr?.optDouble("lat") ?: 0.0)
            val lon = if (el.has("lon")) el.optDouble("lon") else (ctr?.optDouble("lon") ?: 0.0)
            val key = name.lowercase() + "|" + Math.round(lat * 1000) + "|" + Math.round(lon * 1000)
            if (!seen.add(key)) continue
            val addr = listOf(tg.optString("addr:housenumber"), tg.optString("addr:street"),
                tg.optString("addr:suburb"), tg.optString("addr:city"))
                .filter { it.isNotBlank() }.joinToString(", ")
            val l = JSONObject()
                .put("id", el.optString("type") + el.optLong("id"))
                .put("name", name)
                .put("phone", first(tg, "phone", "contact:phone", "contact:mobile", "mobile"))
                .put("email", first(tg, "email", "contact:email"))
                .put("fb", first(tg, "contact:facebook", "facebook"))
                .put("ig", first(tg, "contact:instagram", "instagram"))
                .put("hours", tg.optString("opening_hours"))
                .put("cuisine", tg.optString("cuisine").replace(';', ','))
                .put("addr", addr)
                .put("lat", lat)
                .put("lon", lon)
                .put("status", "new")
                .put("verified", "")
                .put("site", "").put("pub", "").put("wa", "").put("subj", "").put("body", "").put("note", "")
            l.put("score", score(l))
            leads.add(l)
        }
        if (leads.isEmpty()) throw Exception("Ei elakay 'website nai' lead pelam na. Onno elaka ba dhoron try koro")
        leads.sortWith(compareByDescending<JSONObject> { it.optInt("score") }.thenBy { it.optString("name") })
        val arr = JSONArray()
        for (l in leads.take(count.coerceIn(10, 300))) arr.put(l)
        st("Pelam: " + arr.length() + " ta lead")
        val nm = clean(area) + "_" + types[type]
        return JSONObject().put("name", nm).put("area", area).put("type", type).put("typeName", types[type])
            .put("cc", country).put("ts", System.currentTimeMillis()).put("leads", arr)
    }

    // ---------- verify with search ----------

    private fun gemSearch(c: Context, prompt: String): String {
        val key = Secure.prefs(c).getString("k", "")?.trim().orEmpty()
        if (key.isEmpty()) throw Exception("Gemini key nai")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        var last: Exception? = null
        for (m in listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")) {
            try {
                val txt = Ai.httpPost("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                    mapOf("x-goog-api-key" to key), body.toString(), 50000)
                val ps = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
                return sb.toString()
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("Search error")
    }

    fun verify(c: Context, l: JSONObject, area: String): String {
        val prompt = "Does the business \"" + l.optString("name") + "\" located at \"" + l.optString("addr") +
            "\" (" + area + ") have its own official website? Facebook, Instagram, Google Maps, delivery apps and " +
            "directories do NOT count. Search the web, then reply ONLY with JSON like " +
            "{\"has_website\": true, \"url\": \"https://...\"} or {\"has_website\": false}."
        val t = gemSearch(c, prompt)
        val s = t.indexOf('{')
        val en = t.lastIndexOf('}')
        if (s < 0 || en <= s) return "unknown"
        return try {
            val j = JSONObject(t.substring(s, en + 1))
            if (j.optBoolean("has_website", false)) "has_site:" + j.optString("url") else "no_site"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ---------- demo site ----------

    fun site(c: Context, l: JSONObject, area: String): String {
        val bn = sp(c).getInt("lab_lang", 0) == 0
        val me = sp(c).getString("lab_me", "")?.trim().orEmpty()
        val langName = if (bn) "Bangla (Bengali script), with English for brand and food words" else "English"
        val prompt = "Create ONE complete, self-contained, mobile-first HTML5 page (inline CSS and a little inline JS; " +
            "no external files, no external fonts, no remote images; use CSS gradients, shapes and emoji only) " +
            "as a SAMPLE homepage for this local business.\n" +
            "Facts you may use (never invent anything else: no prices, reviews, awards, years or fake photos):\n" +
            "Name: " + l.optString("name") + "\nType: " + types[sp(c).getInt("lab_type", 0).coerceIn(0, types.size - 1)] + "\n" +
            "Cuisine or category: " + l.optString("cuisine") + "\nAddress: " + l.optString("addr") + ", " + area + "\n" +
            "Phone: " + l.optString("phone") + "\nOpening hours: " + l.optString("hours") + "\n\n" +
            "Sections: hero with the name and a short invented tagline; About (2 generic sentences); " +
            "a Menu or Services section clearly labelled 'Sample, to be replaced with your real menu' with 6 generic " +
            "item names and NO prices; Opening hours (only if given); Location with a button linking to " +
            "https://www.google.com/maps/search/?api=1&query=" + enc(l.optString("name") + " " + l.optString("addr") + " " + area) +
            "; Contact with a tel: link and a WhatsApp link https://wa.me/<digits> (only if a phone is given); " +
            "footer 'Sample design by " + (if (me.isEmpty()) "us" else me) + "'.\n" +
            "Design: modern, warm colours that suit the business, large tap targets, readable. " +
            "Language of the page: " + langName + ". Output ONLY the HTML starting with <!DOCTYPE html>."
        var h = Ai.text(c, prompt, false, 150000)
        val s1 = h.indexOf("<!DOCTYPE", 0, true)
        val s = if (s1 >= 0) s1 else h.indexOf("<html", 0, true)
        val e = h.lastIndexOf("</html>", h.length, true)
        if (s < 0 || e < s) throw Exception("HTML pelam na, abar chesta koro")
        h = h.substring(s, e + 7)
        h = Regex("<script[^>]*\\ssrc=[^>]*>\\s*</script>", RegexOption.IGNORE_CASE).replace(h, "")
        h = Regex("<link[^>]*href=\"https?://[^>]*>", RegexOption.IGNORE_CASE).replace(h, "")
        return h
    }

    // ---------- messages ----------

    private fun optOut(bn: Boolean) =
        if (bn) "\n(আগ্রহ না থাকলে STOP লিখে পাঠান, আর মেসেজ করব না।)"
        else "\n(Reply STOP and I will not message again.)"

    fun fallbackMsg(c: Context, l: JSONObject): Array<String> {
        val bn = sp(c).getInt("lab_lang", 0) == 0
        val me = sp(c).getString("lab_me", "")?.trim().orEmpty()
        val ct = sp(c).getString("lab_contact", "")?.trim().orEmpty()
        val pub = l.optString("pub")
        val nm = l.optString("name")
        val wa: String
        val subj: String
        val body: String
        if (bn) {
            val link = if (pub.isNotEmpty()) "দেখুন: $pub" else "দেখতে চাইলে রিপ্লাই দিন, প্রিভিউ পাঠিয়ে দেব।"
            wa = "আসসালামু আলাইকুম, আমি $me। আমরা লক্ষ্য করেছি '$nm' এর নিজস্ব ওয়েবসাইট নেই, তাই একটি ফ্রি স্যাম্পল ওয়েবসাইট বানিয়েছি। $link" +
                optOut(true)
            subj = "$nm এর জন্য ফ্রি স্যাম্পল ওয়েবসাইট"
            body = wa + "\n\nযোগাযোগ: $ct"
        } else {
            val link = if (pub.isNotEmpty()) "Preview: $pub" else "Reply if you would like to see it and I will send a preview."
            wa = "Hello, I am $me. I noticed '$nm' does not seem to have its own website, so I made a free sample homepage. $link" +
                optOut(false)
            subj = "Free sample website for $nm"
            body = wa + "\n\nContact: $ct"
        }
        return arrayOf(wa, subj, body)
    }

    fun message(c: Context, l: JSONObject): Array<String> {
        val bn = sp(c).getInt("lab_lang", 0) == 0
        val me = sp(c).getString("lab_me", "")?.trim().orEmpty()
        val ct = sp(c).getString("lab_contact", "")?.trim().orEmpty()
        val pub = l.optString("pub")
        val prompt = "Write an honest, polite outreach message to the owner of a local business.\n" +
            "Business: " + l.optString("name") + " (" + l.optString("cuisine") + "), " + l.optString("addr") + "\n" +
            "Sender: " + me + ", a small web design team. Reply contact: " + ct + "\n" +
            "Fact: the business seems to have no website, and the sender prepared a free SAMPLE homepage for it. " +
            (if (pub.isNotEmpty()) "Preview link: $pub\n" else "There is no link yet: say a preview can be sent if they reply.\n") +
            "Rules: no false claims, no pressure or urgency tricks, no mention of private data, offer only a free look, " +
            "include one short line saying they can reply STOP to never be contacted again.\n" +
            "Language: " + (if (bn) "Bangla (Bengali script)" else "English") + ".\n" +
            "Reply ONLY JSON: {\"wa\": \"WhatsApp text, max 70 words\", \"subject\": \"email subject\", " +
            "\"body\": \"email body, max 120 words\"}"
        val t = Ai.text(c, prompt, true, 60000)
        val s = t.indexOf('{')
        val en = t.lastIndexOf('}')
        if (s < 0 || en <= s) throw Exception("Message JSON pelam na")
        val j = JSONObject(t.substring(s, en + 1))
        var wa = j.optString("wa").trim()
        var body = j.optString("body").trim()
        val subj = j.optString("subject").trim()
        if (wa.isEmpty() || body.isEmpty() || subj.isEmpty()) throw Exception("Message adhura")
        if (!wa.contains("STOP", true)) wa += optOut(bn)
        if (!body.contains("STOP", true)) body += optOut(bn)
        return arrayOf(wa, subj, body)
    }

    fun digits(raw: String, country: String): String {
        val r = raw.trim()
        val plus = r.startsWith("+")
        val d = r.filter { it.isDigit() }
        if (d.isEmpty()) return ""
        if (plus) return d
        if (d.startsWith("00")) return d.substring(2)
        val code = cc[country] ?: return d
        if (d.startsWith("0")) return code + d.substring(1)
        if (d.startsWith(code)) return d
        return code + d
    }

    fun csv(camp: JSONObject): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("name,score,status,phone,email,facebook,instagram,address,cuisine,hours,verified,demo_link\n")
        val a = camp.getJSONArray("leads")
        for (i in 0 until a.length()) {
            val l = a.getJSONObject(i)
            sb.append(q(l.optString("name"))).append(',').append(l.optInt("score")).append(',')
                .append(q(l.optString("status"))).append(',').append(q(l.optString("phone"))).append(',')
                .append(q(l.optString("email"))).append(',').append(q(l.optString("fb"))).append(',')
                .append(q(l.optString("ig"))).append(',').append(q(l.optString("addr"))).append(',')
                .append(q(l.optString("cuisine"))).append(',').append(q(l.optString("hours"))).append(',')
                .append(q(l.optString("verified"))).append(',').append(q(l.optString("pub"))).append('\n')
        }
        return sb.toString()
    }
}
