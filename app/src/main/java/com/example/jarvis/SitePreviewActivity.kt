package com.example.jarvis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import java.io.File

class SitePreviewActivity : Activity() {
    private var html = ""
    private var nm = "site"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Hud.bg
        window.navigationBarColor = Hud.bg
        val path = intent.getStringExtra("path") ?: ""
        nm = intent.getStringExtra("name") ?: "site"
        html = try { File(path).readText() } catch (e: Exception) { "<html><body>File pelam na</body></html>" }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Hud.bg)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val pd = Hud.dp(this, 8)
        bar.setPadding(pd, pd, pd, pd)
        bar.addView(Hud.btn(this, "Export .html") { export(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        val l2 = LinearLayout.LayoutParams(0, -2, 1f)
        l2.leftMargin = pd
        bar.addView(Hud.btn(this, "Share") { export(true) }, l2)
        val l3 = LinearLayout.LayoutParams(-2, -2)
        l3.leftMargin = pd
        bar.addView(Hud.btn(this, "X") { finish() }, l3)

        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "preview.local") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                }
                return true
            }
        }
        wv.loadDataWithBaseURL("https://preview.local/", html, "text/html", "utf-8", null)
        root.addView(bar)
        root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun export(share: Boolean) {
        try {
            val name = nm.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30).ifBlank { "site" } + ".html"
            val r = Exporter.save(this, name, html.toByteArray(), "text/html")
            val u = r.uri
            if (share && u != null) Exporter.share(this, u, "text/html")
            Hud.toast(this, "Saved: " + r.path)
        } catch (e: Exception) {
            Hud.toast(this, "Export error: " + (e.message ?: ""))
        }
    }
}
