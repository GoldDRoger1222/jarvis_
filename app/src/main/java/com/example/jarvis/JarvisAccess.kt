package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccess : AccessibilityService() {
    companion object { var inst: JarvisAccess? = null }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Screen not readable"
        val sb = StringBuilder("App: ${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || sb.length > 3500 || d > 25) return
            val t = (n.text ?: n.contentDescription)?.toString()
            if (n.isPassword) sb.append("[password field]\n")
            else if (!t.isNullOrBlank()) {
                sb.append(if (n.isClickable) "[tap] " else if (n.isEditable) "[input] " else "")
                    .append(t.take(120)).append('\n')
            } else if (n.isEditable) sb.append("[input] (empty)\n")
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    fun tap(text: String): String {
        val root = rootInActiveWindow ?: return "Screen not readable"
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) return "Nothing on screen matches: $text"
        for (n in nodes) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped $text"
        }
        return "Found but not tappable: $text"
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
            ?: return "No input field found"
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
