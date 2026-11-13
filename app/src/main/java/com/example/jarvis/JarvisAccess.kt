package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccess : AccessibilityService() {
    companion object { var inst: JarvisAccess? = null }

    private val nodes = ArrayList<AccessibilityNodeInfo>()
    private val labels = ArrayList<String>()

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun own(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.trim()?.replace("\n", " ")?.take(80) ?: ""

    private fun deep(n: AccessibilityNodeInfo?, d: Int): String {
        if (n == null || d > 4) return ""
        val o = own(n)
        if (o.isNotEmpty()) return o
        for (i in 0 until n.childCount) {
            val r = deep(n.getChild(i), d + 1)
            if (r.isNotEmpty()) return r
        }
        return ""
    }

    fun labelOf(i: Int): String = labels.getOrNull(i - 1) ?: ""

    fun readScreen(register: Boolean = true): String {
        val root = rootInActiveWindow ?: return "Screen not readable"
        if (register) { nodes.clear(); labels.clear() }
        val sb = StringBuilder("App: ${root.packageName}\n")
        fun tag(n: AccessibilityNodeInfo, l: String): String {
            if (!register) return ""
            nodes.add(n)
            labels.add(l)
            return "[${nodes.size}] "
        }
        fun walk(n: AccessibilityNodeInfo?, d: Int, covered: Boolean) {
            if (n == null || sb.length > 4500 || d > 30 || !n.isVisibleToUser) return
            val o = own(n)
            var cov = covered
            if (n.isPassword) {
                sb.append(tag(n, "password")).append("input (password field, never type here)\n")
            } else if (n.isEditable) {
                val l = if (o.isEmpty()) "empty" else o
                sb.append(tag(n, l)).append("input '").append(l).append("'\n")
            } else if (n.isClickable || n.isCheckable) {
                val l = if (o.isNotEmpty()) o else deep(n, 0)
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append(tag(n, l)).append("tap '").append(if (l.isEmpty()) "no label" else l).append("'")
                if (n.isChecked) sb.append(" (on)")
                sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
                cov = true
            } else if (o.isNotEmpty() && !covered) {
                sb.append("text '").append(o).append("'\n")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1, cov)
        }
        walk(root, 0, false)
        return sb.toString()
    }

    private fun gesture(x: Float, y: Float): Boolean {
        return try {
            val p = Path()
            p.moveTo(x, y)
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) {
            false
        }
    }

    fun tapIndex(i: Int): String {
        val n = nodes.getOrNull(i - 1) ?: return "No element [$i] on the last screen. Use the numbers from the latest screen."
        try { n.refresh() } catch (e: Exception) {}
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable && !c.isEditable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$i] ${labels[i - 1]}"
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (gesture(r.centerX().toFloat(), r.centerY().toFloat())) "Tapped [$i] by touch"
        else "Could not tap [$i]"
    }

    fun tap(text: String): String {
        val root = rootInActiveWindow ?: return "Screen not readable"
        val found = root.findAccessibilityNodeInfosByText(text)
        if (found.isNullOrEmpty()) return "Nothing on screen matches: $text"
        for (n in found) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped $text"
        }
        val r = Rect()
        found[0].getBoundsInScreen(r)
        return if (gesture(r.centerX().toFloat(), r.centerY().toFloat())) "Tapped $text by touch"
        else "Found but not tappable: $text"
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "Screen not readable"
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
            ?: return "No input field found. Tap the input field first."
        if (f.isPassword) return "Refused: password field"
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "Typed" else "Could not type"
    }

    fun scroll(down: Boolean): String {
        val root = rootInActiveWindow ?: return "Screen not readable"
        val s = findScrollable(root) ?: return "Nothing scrollable"
        val ok = s.performAction(
            if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        return if (ok) "Scrolled" else "Cannot scroll further"
    }

    fun key(k: String): String {
        val ok = when (k) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }
        return if (ok) "Done" else "Failed"
    }
}
