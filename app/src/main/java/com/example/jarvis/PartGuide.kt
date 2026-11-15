package com.example.jarvis

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

object PartGuide {
    private var dlg: Dialog? = null

    fun dismiss() {
        val d = dlg
        dlg = null
        if (d != null) {
            try {
                d.setOnDismissListener(null)
                d.dismiss()
            } catch (e: Exception) {
            }
        }
    }

    fun show(ctx: Context, model: String, group: String, parts: List<String>,
             groups: List<String>, onClose: () -> Unit) {
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
        body.text = "Guide toiri hocche... (10-30 second)"
        body.setTextColor(Color.rgb(216, 243, 255))
        body.textSize = 14f
        body.setPadding(0, (10 * dens).toInt(), 0, (10 * dens).toInt())
        val sv = ScrollView(ctx)
        sv.addView(body)

        val close = Button(ctx)
        close.text = "Model e fire jao"
        close.isAllCaps = false
        close.setTextColor(Color.rgb(0, 229, 255))
        close.typeface = Typeface.DEFAULT_BOLD
        close.stateListAnimator = null
        val bb = GradientDrawable()
        bb.setColor(Color.argb(40, 0, 229, 255))
        bb.cornerRadius = 12 * dens
        bb.setStroke(maxOf(1, dens.toInt()), Color.rgb(0, 229, 255))
        close.background = bb

        root.addView(title)
        root.addView(sub)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
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

        thread {
            val t = try {
                HoloGen.guide(ctx, model, group, parts, groups)
            } catch (e: Exception) {
                "Error: " + (e.message ?: "").take(200)
            }
            body.post { body.text = t.replace("*", "") }
        }
    }
}
