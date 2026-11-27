package com.example.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class LabActivity : Activity() {
    private lateinit var area: EditText
    private lateinit var typeBtn: Button
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var type = 0
    private var camp: JSONObject? = null
    @Volatile private var busy = false
    @Volatile private var alive = true

    private fun sp() = getSharedPreferences("j", MODE_PRIVATE)
    private fun ui(f: () -> Unit) { if (alive) runOnUiThread { f() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Hud.bg
        window.navigationBarColor = Hud.bg
        val c = this
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Hud.bg)
        val p = Hud.dp(c, 14)
        root.setPadding(p, p, p, p)

        val head = LinearLayout(c)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(c)
        title.text = "RESEARCH LAB"
        title.setTextColor(Hud.cyan)
        title.textSize = 20f
        title.letterSpacing = 0.2f
        title.typeface = Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Hud.btn(c, "Me") { meDialog() })
        val gap = LinearLayout.LayoutParams(-2, -2)
        gap.leftMargin = Hud.dp(c, 6)
        head.addView(Hud.btn(c, "AI \u2699") { Providers.show(c) }, gap)
        root.addView(head)

        area = Hud.edit(c, "Elaka ba shohor (jemon: Gulshan, Dhaka)")
        area.setText(sp().getString("lab_area", ""))
        root.addView(area, Hud.lp(c, 10))

        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        typeBtn = Hud.btn(c, "") { type = (type + 1) % Lab.types.size; typeLabel() }
        row.addView(typeBtn, LinearLayout.LayoutParams(0, -2, 1f))
        val l2 = LinearLayout.LayoutParams(0, -2, 1f)
        l2.leftMargin = Hud.dp(c, 8)
        row.addView(Hud.btn(c, "Khojo (100)", true) { runFind(area.text.toString(), type, 100) }, l2)
        root.addView(row, Hud.lp(c, 8))

        val row2 = LinearLayout(c)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(Hud.btn(c, "Purano") { pickCamp() }, LinearLayout.LayoutParams(0, -2, 1f))
        val l3 = LinearLayout.LayoutParams(0, -2, 1f)
        l3.leftMargin = Hud.dp(c, 8)
        row2.addView(Hud.btn(c, "Top 10 demo") { bulkDemos(10) }, l3)
        val l4 = LinearLayout.LayoutParams(0, -2, 1f)
        l4.leftMargin = Hud.dp(c, 8)
        row2.addView(Hud.btn(c, "CSV") { exportCsv() }, l4)
        root.addView(row2, Hud.lp(c, 8))

        status = TextView(c)
        status.setTextColor(Hud.cyan)
        status.textSize = 12f
        root.addView(status, Hud.lp(c, 8))

        list = LinearLayout(c)
        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(c)
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        typeLabel()
        val last = sp().getString("lab_last", null)
        if (last != null) camp = Lab.loadCamp(this, last)
        render()
        val ask = intent.getStringExtra("ask")
        if (!ask.isNullOrBlank()) parseAsk(ask)
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    private fun typeLabel() {
        typeBtn.text = "Dhoron: " + Lab.types[type]
    }

    private fun parseAsk(ask: String) {
        status.text = "Bujhchi..."
        thread {
            try {
                val prompt = "The user wants to find local businesses that have no website. Extract from this sentence: " +
                    "area (a place name in English that a map search understands, a city or neighbourhood), " +
                    "type (index into [Restaurant, Cafe, Fast food, Bakery, Hotel, Salon, Gym, Clinic, Shop]), " +
                    "count (default 100). Sentence: \"" + ask + "\"\n" +
                    "Reply ONLY JSON: {\"area\":\"...\",\"type\":0,\"count\":100}"
                val t = Ai.text(this, prompt, true, 40000)
                val s = t.indexOf('{')
                val en = t.lastIndexOf('}')
                val j = JSONObject(t.substring(s, en + 1))
                val a = j.optString("area")
                val ty = j.optInt("type", 0).coerceIn(0, Lab.types.size - 1)
                val n = j.optInt("count", 100).coerceIn(10, 300)
                ui {
                    area.setText(a)
                    type = ty
                    typeLabel()
                    runFind(a, ty, n)
                }
            } catch (e: Exception) {
                ui { status.text = "Bujhte parini: " + (e.message ?: "").take(120) }
            }
        }
    }

    private fun runFind(a: String, t: Int, n: Int) {
        val ar = a.trim()
        if (ar.isEmpty()) { status.text = "Elaka likho"; return }
        if (busy) return
        busy = true
        sp().edit().putString("lab_area", ar).putInt("lab_type", t).apply()
        status.text = "Shuru..."
        thread {
            try {
                val o = Lab.find(ar, t, n) { s -> ui { status.text = s } }
                Lab.saveCamp(this, o)
                sp().edit().putString("lab_last", o.getString("name")).apply()
                ui { camp = o; busy = false; render() }
            } catch (e: Exception) {
                ui { busy = false; status.text = "Error: " + (e.message ?: "").take(200) }
            }
        }
    }

    private fun pickCamp() {
        val names = Lab.listCamps(this)
        if (names.isEmpty()) { status.text = "Kono purano campaign nai"; return }
        AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, w ->
            val o = Lab.loadCamp(this, names[w])
            if (o != null) {
                camp = o
                sp().edit().putString("lab_last", names[w]).apply()
                render()
            }
        }.show()
    }

    private fun mark(v: Boolean) = if (v) "\u2713" else "-"

    private fun render() {
        list.removeAllViews()
        val c = camp
        if (c == null) {
            status.text = "Elaka likhe Khojo tap koro. Voice: 'Dhaka te website chara restaurant khuje ber koro'"
            return
        }
        val ls = c.getJSONArray("leads")
        status.text = c.optString("area") + "  |  " + c.optString("typeName") + "  |  " + ls.length() +
            " lead  |  ajker open: " + (if (Lab.canOpen(this)) "ok" else "limit")
        for (i in 0 until ls.length()) {
            val l = ls.getJSONObject(i)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = Hud.box(this, Hud.cardC, android.graphics.Color.argb(60, 0, 229, 255), 12)
            val pd = Hud.dp(this, 10)
            row.setPadding(pd, pd, pd, pd)
            val n = TextView(this)
            n.text = l.optString("name")
            n.setTextColor(Hud.txt)
            n.textSize = 15f
            n.typeface = Typeface.DEFAULT_BOLD
            val d = TextView(this)
            d.text = "score " + l.optInt("score") + "  |  phone " + mark(l.optString("phone").isNotEmpty()) +
                "  email " + mark(l.optString("email").isNotEmpty()) +
                "  social " + mark(l.optString("fb").isNotEmpty() || l.optString("ig").isNotEmpty()) +
                "  demo " + mark(siteExists(l)) + "  |  " + l.optString("status")
            d.setTextColor(Hud.dim)
            d.textSize = 12f
            row.addView(n)
            row.addView(d)
            row.setOnClickListener { leadMenu(i) }
            list.addView(row, Hud.lp(this, 6))
        }
    }

    private fun siteExists(l: JSONObject): Boolean {
        val p = l.optString("site")
        return p.isNotEmpty() && File(p).exists()
    }

    private fun save() {
        camp?.let { Lab.saveCamp(this, it) }
    }

    private fun leadMenu(i: Int) {
        val c = camp ?: return
        val l = c.getJSONArray("leads").getJSONObject(i)
        val items = ArrayList<String>()
        items.add("Details dekho")
        if (siteExists(l)) {
            items.add("Demo site dekho")
            items.add("Demo site abar banao")
        } else items.add("Demo site banao")
        items.add("Message draft (AI)")
        items.add("Demo link set koro (hosting er por)")
        if (l.optString("phone").isNotEmpty()) {
            items.add("WhatsApp kholo")
            items.add("SMS kholo")
        }
        if (l.optString("email").isNotEmpty()) items.add("Email kholo")
        items.add("Verify: website ache ki?")
        items.add("Status bodlao")
        items.add("Maps e dekho")
        AlertDialog.Builder(this).setTitle(l.optString("name")).setItems(items.toTypedArray()) { _, w ->
            when (items[w]) {
                "Details dekho" -> details(l)
                "Demo site dekho" -> preview(l)
                "Demo site banao", "Demo site abar banao" -> makeSite(l, true)
                "Message draft (AI)" -> makeDraft(l, null)
                "Demo link set koro (hosting er por)" -> linkDialog(l)
                "WhatsApp kholo" -> openWa(l)
                "SMS kholo" -> openSms(l)
                "Email kholo" -> openMail(l)
                "Verify: website ache ki?" -> verifyLead(l)
                "Status bodlao" -> statusDialog(l)
                else -> maps(l)
            }
        }.show()
    }

    private fun details(l: JSONObject) {
        val sb = StringBuilder()
        sb.append("Nam: ").append(l.optString("name")).append("\n")
        sb.append("Score: ").append(l.optInt("score")).append("\n")
        sb.append("Phone: ").append(l.optString("phone")).append("\n")
        sb.append("Email: ").append(l.optString("email")).append("\n")
        sb.append("Facebook: ").append(l.optString("fb")).append("\n")
        sb.append("Instagram: ").append(l.optString("ig")).append("\n")
        sb.append("Thikana: ").append(l.optString("addr")).append("\n")
        sb.append("Cuisine: ").append(l.optString("cuisine")).append("\n")
        sb.append("Hours: ").append(l.optString("hours")).append("\n")
        sb.append("Verify: ").append(l.optString("verified")).append("\n")
        sb.append("Demo link: ").append(l.optString("pub")).append("\n")
        sb.append("Status: ").append(l.optString("status"))
        AlertDialog.Builder(this).setTitle("Details").setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    private fun preview(l: JSONObject) {
        startActivity(Intent(this, SitePreviewActivity::class.java)
            .putExtra("path", l.optString("site")).putExtra("name", l.optString("name")))
    }

    private fun makeSite(l: JSONObject, open: Boolean) {
        if (busy) return
        busy = true
        status.text = l.optString("name") + ": demo site toiri hocche (30-90 second)..."
        thread {
            try {
                val cn = camp?.optString("name") ?: "x"
                val html = Lab.site(this, l, camp?.optString("area") ?: "")
                val f = Lab.siteFile(this, cn, l.optString("id"))
                f.writeText(html)
                l.put("site", f.absolutePath)
                if (l.optString("status") == "new") l.put("status", "site_ready")
                save()
                ui {
                    busy = false
                    render()
                    status.text = "Demo ready: " + l.optString("name")
                    if (open) preview(l)
                }
            } catch (e: Exception) {
                ui { busy = false; status.text = "Site error: " + (e.message ?: "").take(160) }
            }
        }
    }

    private fun bulkDemos(n: Int) {
        val c = camp ?: run { status.text = "Age campaign khojo"; return }
        if (busy) return
        busy = true
        val ls = c.getJSONArray("leads")
        val targets = ArrayList<JSONObject>()
        for (i in 0 until ls.length()) {
            val l = ls.getJSONObject(i)
            val s = l.optString("status")
            if (!siteExists(l) && s != "dnc" && s != "skip" && targets.size < n) targets.add(l)
        }
        if (targets.isEmpty()) { busy = false; status.text = "Demo banabar moto lead nai"; return }
        thread {
            var done = 0
            for (l in targets) {
                if (!alive) break
                ui { status.text = "Demo " + (done + 1) + "/" + targets.size + ": " + l.optString("name") }
                try {
                    val html = Lab.site(this, l, c.optString("area"))
                    val f = Lab.siteFile(this, c.optString("name"), l.optString("id"))
                    f.writeText(html)
                    l.put("site", f.absolutePath)
                    if (l.optString("status") == "new") l.put("status", "site_ready")
                    save()
                    done++
                } catch (e: Exception) {
                    val m = e.message ?: ""
                    if (m.contains("429") || m.contains("limit", true) || m.contains("busy", true)) {
                        ui { status.text = "AI limit. " + done + " ta hoyeche, pore abar koro" }
                        break
                    }
                }
                try { Thread.sleep(3000) } catch (e: Exception) { }
            }
            ui { busy = false; render(); status.text = "Shesh: " + done + " ta demo toiri" }
        }
    }

    private fun needMe(): Boolean {
        if (sp().getString("lab_me", "")?.trim().isNullOrEmpty()) {
            meDialog()
            status.text = "Age 'Me' e tomar nam dao (message e porichoy lage)"
            return true
        }
        return false
    }

    private fun makeDraft(l: JSONObject, then: (() -> Unit)?) {
        if (busy || needMe()) return
        busy = true
        status.text = "Message toiri hocche..."
        thread {
            val r = try { Lab.message(this, l) } catch (e: Exception) { Lab.fallbackMsg(this, l) }
            l.put("wa", r[0]).put("subj", r[1]).put("body", r[2])
            save()
            ui {
                busy = false
                status.text = "Draft ready"
                if (then != null) then() else showDraft(l)
            }
        }
    }

    private fun showDraft(l: JSONObject) {
        val txt = "WhatsApp/SMS:\n" + l.optString("wa") + "\n\nEmail subject: " + l.optString("subj") +
            "\n\nEmail:\n" + l.optString("body")
        AlertDialog.Builder(this).setTitle("Message draft").setMessage(txt)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("msg", l.optString("wa")))
            }.show()
    }

    private fun ensureDraft(l: JSONObject, then: () -> Unit) {
        if (l.optString("wa").isNotEmpty()) then() else makeDraft(l, then)
    }

    private fun guardSend(l: JSONObject): Boolean {
        if (l.optString("status") == "dnc") { Hud.toast(this, "Eta Do-not-contact"); return false }
        if (!Lab.canOpen(this)) {
            Hud.toast(this, "Ajker limit (" + Lab.CAP + ") sesh. Spam theke bachte kal korbo.")
            return false
        }
        return true
    }

    private fun afterOpen(l: JSONObject) {
        Lab.bump(this)
        val s = l.optString("status")
        if (s == "new" || s == "site_ready") l.put("status", "opened")
        save()
        render()
    }

    private fun openWa(l: JSONObject) {
        if (!guardSend(l)) return
        val num = Lab.digits(l.optString("phone"), camp?.optString("cc") ?: "")
        if (num.isEmpty()) { Hud.toast(this, "Phone number bhul"); return }
        ensureDraft(l) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(l.optString("wa")))))
                afterOpen(l)
            } catch (e: Exception) {
                Hud.toast(this, "WhatsApp khulte parlam na")
            }
        }
    }

    private fun openSms(l: JSONObject) {
        if (!guardSend(l)) return
        val num = Lab.digits(l.optString("phone"), camp?.optString("cc") ?: "")
        if (num.isEmpty()) { Hud.toast(this, "Phone number bhul"); return }
        ensureDraft(l) {
            try {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+" + num))
                i.putExtra("sms_body", l.optString("wa"))
                startActivity(i)
                afterOpen(l)
            } catch (e: Exception) {
                Hud.toast(this, "SMS app khulte parlam na")
            }
        }
    }

    private fun openMail(l: JSONObject) {
        if (!guardSend(l)) return
        ensureDraft(l) {
            try {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + l.optString("email")))
                i.putExtra(Intent.EXTRA_SUBJECT, l.optString("subj"))
                i.putExtra(Intent.EXTRA_TEXT, l.optString("body"))
                startActivity(i)
                afterOpen(l)
            } catch (e: Exception) {
                Hud.toast(this, "Email app khulte parlam na")
            }
        }
    }

    private fun verifyLead(l: JSONObject) {
        if (busy) return
        busy = true
        status.text = "Verify hocche..."
        thread {
            try {
                val r = Lab.verify(this, l, camp?.optString("area") ?: "")
                l.put("verified", r)
                if (r.startsWith("has_site")) {
                    l.put("status", "skip")
                    l.put("score", 5)
                }
                save()
                ui {
                    busy = false
                    render()
                    status.text = if (r.startsWith("has_site")) "Website ache (" + r.substringAfter(':') + "), skip kora holo"
                    else if (r == "no_site") "Website nai, bhalo lead" else "Nishchit na"
                }
            } catch (e: Exception) {
                ui { busy = false; status.text = "Verify error: " + (e.message ?: "").take(150) }
            }
        }
    }

    private fun statusDialog(l: JSONObject) {
        AlertDialog.Builder(this).setItems(Lab.statuses) { _, w ->
            l.put("status", Lab.statuses[w])
            save()
            render()
        }.show()
    }

    private fun linkDialog(l: JSONObject) {
        val e = Hud.edit(this, "https://...")
        e.setText(l.optString("pub"))
        AlertDialog.Builder(this).setTitle("Demo link").setView(e)
            .setPositiveButton("Save") { _, _ ->
                l.put("pub", e.text.toString().trim())
                l.put("wa", "").put("subj", "").put("body", "")
                save()
                status.text = "Link save hoyeche, message abar toiri hobe"
            }.setNegativeButton("Cancel", null).show()
    }

    private fun maps(l: JSONObject) {
        val q = Uri.encode(l.optString("name") + " " + l.optString("addr"))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")))
        }
    }

    private fun exportCsv() {
        val c = camp ?: run { status.text = "Age campaign khojo"; return }
        try {
            val r = Exporter.save(this, c.optString("name") + ".csv", Lab.csv(c).toByteArray(), "text/csv")
            status.text = "CSV saved: " + r.path
        } catch (e: Exception) {
            status.text = "CSV error: " + (e.message ?: "").take(120)
        }
    }

    private fun meDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pd = Hud.dp(this, 16)
        box.setPadding(pd, pd, pd, pd)
        val me = Hud.edit(this, "Tomar nam / team er nam")
        me.setText(sp().getString("lab_me", ""))
        val ct = Hud.edit(this, "Reply contact (WhatsApp number ba email)")
        ct.setText(sp().getString("lab_contact", ""))
        var lang = sp().getInt("lab_lang", 0)
        val lb = Hud.btn(this, "") { }
        fun lt() { lb.text = if (lang == 0) "Message bhasha: Bangla" else "Message bhasha: English" }
        lb.setOnClickListener { lang = 1 - lang; lt() }
        lt()
        box.addView(me, Hud.lp(this, 4))
        box.addView(ct, Hud.lp(this, 8))
        box.addView(lb, Hud.lp(this, 8))
        AlertDialog.Builder(this).setTitle("Me").setView(box)
            .setPositiveButton("Save") { _, _ ->
                sp().edit().putString("lab_me", me.text.toString().trim())
                    .putString("lab_contact", ct.text.toString().trim())
                    .putInt("lab_lang", lang).apply()
            }.setNegativeButton("Cancel", null).show()
    }
}
