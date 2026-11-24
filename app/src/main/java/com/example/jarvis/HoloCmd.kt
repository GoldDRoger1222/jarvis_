package com.example.jarvis

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object HoloCmd {
    fun decl(): JSONObject {
        val props = JSONObject()
            .put("action", JSONObject().put("type", "string").put("description",
                "One of: explode, assemble, select, isolate, back, rotate, zoom, spin, look, save, load, list, export_stl, undo, redo, reset_view, invent, live"))
            .put("target", JSONObject().put("type", "string").put("description",
                "Component name for select or isolate; left, right, up, down or degrees for rotate; in, out or a number for zoom; on or off for spin; hologram, solid, wire or xray for look (xray shows the internal structure); project name for save or load; size in mm for export_stl; the idea in the user's own words for invent; otherwise an empty string"))
        return JSONObject()
            .put("name", "hologram_control")
            .put("description", "Control the 3D hologram that is already shown: explode or assemble it, select or isolate a component (isolate also opens its build guide), rotate, zoom, change the look, save or load a project, export STL, undo or redo. Use the hologram tool to create or modify the model itself. Extra actions: 'invent' opens the Invention Lab when the user wants to build, make or invent something (target is the idea): it creates a full project with a hologram, a parts list with prices in BDT, build steps and code. 'live' switches to real-time live voice conversation mode (target empty).")
            .put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray().put("action").put("target")))
    }

    fun run(ctx: Context, a: JSONObject, launch: (Intent) -> Boolean): String {
        val act = a.optString("action").trim().lowercase()
        if (act == "invent") {
            val ok = launch(Intent(ctx, InventionActivity::class.java).putExtra("idea", a.optString("target").trim()))
            return if (ok) "Invention lab opened, it is building the project" else "Could not open the invention lab"
        }
        if (act == "live") {
            val ok = launch(Intent(ctx, LiveActivity::class.java).putExtra("auto", true))
            return if (ok) "Opening live voice mode" else "Could not open live mode"
        }
        var v = HoloView.active
        if (v == null || !v.isShown) {
            if (!launch(Intent(ctx, HologramActivity::class.java))) return "Could not open the hologram screen"
            v = null
            for (i in 0 until 40) {
                Thread.sleep(100)
                val c = HoloView.active
                if (c != null && c.isShown) {
                    v = c
                    break
                }
            }
            if (v == null) return "The hologram screen did not open"
            Thread.sleep(600)
        }
        val view: HoloView = v
        val latch = CountDownLatch(1)
        var res = "Done"
        view.post {
            res = try {
                view.command(a.optString("action"), a.optString("target"))
            } catch (e: Exception) {
                "Error: " + (e.message ?: "")
            }
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return res
    }
}
