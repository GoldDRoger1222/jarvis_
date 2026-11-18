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
                "One of: explode, assemble, select, isolate, back, rotate, zoom, spin, look, save, load, list, export_stl, undo, redo, reset_view"))
            .put("target", JSONObject().put("type", "string").put("description",
                "Component name for select or isolate; left, right, up, down or degrees for rotate; in, out or a number for zoom; on or off for spin; hologram, solid, wire or xray for look (xray shows the internal structure); project name for save or load; size in mm for export_stl; otherwise an empty string"))
        return JSONObject()
            .put("name", "hologram_control")
            .put("description", "Control the 3D hologram that is already shown: explode or assemble it, select or isolate a component (isolate also opens its build guide), rotate, zoom, change the look, save or load a project, export STL, undo or redo. Use the hologram tool to create or modify the model itself.")
            .put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray().put("action").put("target")))
    }

    fun run(ctx: Context, a: JSONObject, launch: (Intent) -> Boolean): String {
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
