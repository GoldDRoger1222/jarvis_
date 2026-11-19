package com.example.jarvis

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

object JarvisTools {
    fun addTo(arr: JSONArray) {
    }

    fun run(ctx: Context, name: String, a: JSONObject, launch: (Intent) -> Boolean): String {
        return "Unknown tool: " + name
    }
}
