package com.example.jarvis

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

object PartGuide {
    private var dlg: Dialog? = null
    private var gen = 0
    @Volatile private var stat = ""
    private val handler = Handler(Looper.getMainLooper())

    fun dismiss() {
        val d = dlg
        dlg = null
        gen++
        if (d != null) {
            try {
                d.setOnDismissListener(null)
                d.dismiss()
            } catch (e: Exception) {
            }
        }
    }

    private fun btn(ctx: Context, label: String, dens: Float): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.rgb(0, 229, 255))
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        val bb = GradientDrawable()
        bb.setColor(Color.argb(40, 0, 229, 255))
        bb.cornerRadius = 12 * dens
        bb.setStroke(maxOf(1, dens.toInt()), Color.rgb(0, 229, 255))
        b.background = bb
        return b
    }

    fun show(
        ctx: Context,
        model: String,
        group: String,
        parts: List<String>,
        groups: List<String>,
        onClose: () -> Unit
    ) {
        dismiss()
        val dens = ctx.resources.displayMetrics.density
        val pad = (16 * dens).toInt()
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        val bg = GradientDrawable()
        bg.setColor(Color.argb(242, 6, 12, 22))
        val rr = 22 * dens
        bg.cornerRadii = floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
        bg.setStroke(maxOf(1, dens.toInt()), Color.argb(130, 0, 229, 255))
        root.background = bg

        val title = TextView(ctx)
        title.text = group.uppercase()
        title.setTextColor(Color.rgb(255, 240, 150))
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        val sub = TextView(ctx)
        sub.text = model + "  |  " + parts.size + " parts"
        sub.setTextColor(Color.rgb(110, 140, 155))
        sub.textSize = 12f

        val body = TextView(ctx)
        body.text = "Guide toiri hocche..."
        body.setTextColor(Color.rgb(216, 243, 255))
        body.textSize = 14f
        body.setPadding(0, (10 * dens).toInt(), 0, (10 * dens).toInt())
        val sv = ScrollView(ctx)
        sv.addView(body)

        val retry = btn(ctx, "Abar chesta koro", dens)
        retry.visibility = View.GONE
        val close = btn(ctx, "Model e fire jao", dens)

        root.addView(title)
        root.addView(sub)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        val rp = LinearLayout.LayoutParams(-1, -2)
        rp.bottomMargin = (6 * dens).toInt()
        root.addView(retry, rp)
        root.addView(close, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(root)

        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt())
            w.setGravity(Gravity.BOTTOM)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        d.setCanceledOnTouchOutside(false)
        d.setOnDismissListener { onClose() }
        close.setOnClickListener { d.dismiss() }
        d.show()
        dlg = d

        fun load() {
            val my = ++gen
            val t0 = System.currentTimeMillis()
            var done = false
            stat = ""
            retry.visibility = View.GONE
            body.text = "Guide toiri hocche..."
            val tick = object : Runnable {
                override fun run() {
                    if (done || my != gen || dlg !== d) return
                    body.text = "Guide toiri hocche... " + ((System.currentTimeMillis() - t0) / 1000) + "s\n" + stat
                    handler.postDelayed(this, 1000)
                }
            }
            handler.postDelayed(tick, 1000)
            thread {
                var failed = false
                val t = try {
                    HoloGen.guide(ctx, model, group, parts, groups, status = { s -> stat = s })
                } catch (e: Exception) {
                    failed = true
                    "Error: " + (e.message ?: "").take(220)
                }
                handler.post {
                    done = true
                    if (my == gen && dlg === d) {
                        body.text = t.replace("*", "")
                        retry.visibility = if (failed) View.VISIBLE else View.GONE
                    }
                }
            }
        }
        retry.setOnClickListener { load() }
        load()
    }
}
