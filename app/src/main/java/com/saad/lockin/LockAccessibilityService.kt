package com.saad.lockin

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * The actual lock. While a focus window is running it:
 *  1. sends you Home if a blocked app comes to the front,
 *  2. reads the URL bar of known browsers and sends you Home if the site is blocked,
 *  3. blocks Settings / uninstall screens that mention "Lockin" (so you can't just switch it off).
 */
class LockAccessibilityService : AccessibilityService() {

    private var lastAction = 0L

    private val browserUrlIds: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.sec.android.app.sbrowser" to listOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/sbrowser_url_bar"
        ),
        "org.mozilla.firefox" to listOf(
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar_title"
        ),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
        "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar")
    )

    private val protectedPackages = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        if (!LockStore.isLockedNow(this, now)) return

        // 1) blocked apps
        if (pkg in LockStore.sessionApps(this)) {
            block(now)
            return
        }

        // 2) don't let the lock be switched off or uninstalled
        if (pkg in protectedPackages) {
            val root = rootInActiveWindow ?: return
            if (mentionsLockin(root)) block(now)
            return
        }

        // 3) blocked websites in browsers
        val ids = browserUrlIds[pkg] ?: return
        if (LockStore.sessionSites(this).isEmpty()) return
        val root = rootInActiveWindow ?: return
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (n in nodes) {
                val t = n.text?.toString()
                if (t != null && LockStore.isBlockedUrl(this, t)) {
                    block(now)
                    return
                }
            }
        }
    }

    private fun block(now: Long) {
        if (now - lastAction < 800) return
        lastAction = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        val end = LockStore.currentWindow(this, now)?.end ?: now
        Toast.makeText(this, "Lockin: locked until " + LockStore.hm(end), Toast.LENGTH_SHORT).show()
    }

    private fun mentionsLockin(root: AccessibilityNodeInfo): Boolean {
        var seen = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (n == null || depth > 25 || seen > 600) return false
            seen++
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if ((t != null && t.contains("lockin", true)) || (d != null && d.contains("lockin", true))) return true
            for (i in 0 until n.childCount) {
                if (walk(n.getChild(i), depth + 1)) return true
            }
            return false
        }
        return walk(root, 0)
    }

    override fun onInterrupt() {}
}
