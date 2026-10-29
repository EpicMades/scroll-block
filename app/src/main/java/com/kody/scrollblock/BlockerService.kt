package com.kody.scrollblock

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.math.abs

/**
 * Watches only Instagram and YouTube (restricted in accessibility_config.xml, so the
 * system never wakes this service for any other app).
 *
 * Rules:
 *  - Reels tab / Shorts tab / Explore tab  -> pressed Back immediately.
 *  - A single reel or short opened from a DM, link or post -> allowed to play.
 *  - Swiping to the next reel/short -> pressed Back (returns you to where you came from).
 */
class BlockerService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var enabled = true
    private var igReels = true
    private var igExplore = true
    private var ytShorts = true
    private var allowShared = true
    private var debug = false

    private var lastBackAt = 0L
    private var lastCheckAt = 0L
    private var lastDumpAt = 0L
    private var lastToastAt = 0L
    private var pendingPkg: String? = null

    // State of the single reel/short currently allowed on screen
    private var viewerPkg: String? = null
    private var viewerStartIndex = -1
    private var viewerStartScrollY = Int.MIN_VALUE
    private var viewerOpenedAt = 0L

    private val trailingCheck = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        pendingPkg = null
        checkScreen(pkg, SystemClock.uptimeMillis())
    }

    override fun onServiceConnected() {
        prefs = Prefs.get(this)
        loadPrefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.LAST_DUMP || key == Prefs.BLOCK_COUNT) return
        loadPrefs()
    }

    private fun loadPrefs() {
        enabled = prefs.getBoolean(Prefs.ENABLED, true)
        igReels = prefs.getBoolean(Prefs.IG_REELS, true)
        igExplore = prefs.getBoolean(Prefs.IG_EXPLORE, true)
        ytShorts = prefs.getBoolean(Prefs.YT_SHORTS, true)
        allowShared = prefs.getBoolean(Prefs.ALLOW_SHARED, true)
        debug = prefs.getBoolean(Prefs.DEBUG, false)

        // When switched off, ask the system to stop sending events entirely (zero battery cost).
        serviceInfo?.let { info ->
            info.eventTypes = if (enabled) EVENT_MASK else 0
            serviceInfo = info
        }
        if (!enabled) {
            handler.removeCallbacks(trailingCheck)
            viewerPkg = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled) return
        val pkg = event.packageName?.toString() ?: return
        if (Targets.forPackage(pkg) == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < BACK_COOLDOWN_MS) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(pkg, event, now)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkScreen(pkg, now)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Throttle: content changes fire constantly while the feed animates.
                if (now - lastCheckAt >= CHECK_INTERVAL_MS) {
                    checkScreen(pkg, now)
                } else if (pendingPkg == null) {
                    pendingPkg = pkg
                    handler.postDelayed(trailingCheck, CHECK_INTERVAL_MS - (now - lastCheckAt))
                }
            }
        }
    }

    private fun featureOn(pkg: String) = if (pkg == Targets.IG) igReels else ytShorts

    private fun checkScreen(pkg: String, now: Long) {
        lastCheckAt = now
        val t = Targets.forPackage(pkg) ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val viewer = hasAnyId(root, t.viewerIds)
        val onFeedTab = isTabSelected(root, t.feedTabIds, t.feedTabLabel)
        val onExplore = pkg == Targets.IG &&
            isTabSelected(root, t.exploreTabIds, t.exploreTabLabel)

        if (debug) dump(root, pkg, viewer, onFeedTab, onExplore, now)

        if (igExplore && onExplore) { block("Explore is blocked", now); return }
        if (featureOn(pkg) && onFeedTab) {
            block(if (pkg == Targets.IG) "Reels feed is blocked" else "Shorts feed is blocked", now)
            return
        }
        if (featureOn(pkg) && viewer && !allowShared) {
            block(if (pkg == Targets.IG) "Reels are blocked" else "Shorts are blocked", now)
            return
        }

        if (viewer) {
            if (viewerPkg != pkg) {
                viewerPkg = pkg
                viewerStartIndex = -1
                viewerStartScrollY = Int.MIN_VALUE
                viewerOpenedAt = now
            }
        } else if (viewerPkg == pkg) {
            viewerPkg = null
        }
    }

    private fun onScrolled(pkg: String, e: AccessibilityEvent, now: Long) {
        if (!featureOn(pkg)) return
        val t = Targets.forPackage(pkg) ?: return
        val id = e.source?.viewIdResourceName ?: return
        if (id !in t.viewerIds) return

        if (viewerPkg != pkg) {
            checkScreen(pkg, now)
            if (viewerPkg != pkg) return
        }

        val idx = e.fromIndex
        if (idx >= 0) {
            if (viewerStartIndex < 0) viewerStartIndex = idx
            else if (idx != viewerStartIndex) block("Scrolling is blocked", now)
            return
        }

        // Fallback for players that report pixel offsets instead of item indexes
        val y = e.scrollY
        if (y < 0) return
        if (viewerStartScrollY == Int.MIN_VALUE) { viewerStartScrollY = y; return }
        val half = resources.displayMetrics.heightPixels / 2
        if (abs(y - viewerStartScrollY) > half && now - viewerOpenedAt > 800) {
            block("Scrolling is blocked", now)
        }
    }

    private fun block(msg: String, now: Long) {
        lastBackAt = now
        viewerPkg = null
        handler.removeCallbacks(trailingCheck)
        pendingPkg = null
        performGlobalAction(GLOBAL_ACTION_BACK)
        prefs.edit().putInt(Prefs.BLOCK_COUNT, prefs.getInt(Prefs.BLOCK_COUNT, 0) + 1).apply()
        if (now - lastToastAt > 2500) {
            lastToastAt = now
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- node helpers ----

    private fun hasAnyId(root: AccessibilityNodeInfo, ids: Collection<String>): Boolean =
        ids.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }

    private fun isTabSelected(root: AccessibilityNodeInfo, ids: List<String>, label: String?): Boolean {
        for (id in ids) {
            for (n in root.findAccessibilityNodeInfosByViewId(id)) {
                if (isSelectedish(n)) return true
            }
        }
        if (label != null) {
            for (n in root.findAccessibilityNodeInfosByText(label)) {
                val desc = n.contentDescription?.toString()
                val text = n.text?.toString()
                val exact = desc.equals(label, true) || text.equals(label, true)
                if (exact && isSelectedish(n)) return true
            }
        }
        return false
    }

    private fun isSelectedish(n: AccessibilityNodeInfo): Boolean =
        n.isSelected || n.parent?.isSelected == true

    // ---- debug capture (only runs when Debug is switched on) ----

    private fun dump(
        root: AccessibilityNodeInfo, pkg: String,
        viewer: Boolean, feedTab: Boolean, explore: Boolean, now: Long,
    ) {
        if (now - lastDumpAt < 2000) return
        lastDumpAt = now
        val ids = linkedSetOf<String>()
        val selected = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val n = queue.removeFirst()
            visited++
            n.viewIdResourceName?.let { ids.add(it.substringAfter(":id/")) }
            if (n.isSelected) {
                selected.add(n.contentDescription?.toString() ?: n.text?.toString()
                    ?: n.viewIdResourceName ?: "?")
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        val report = buildString {
            appendLine("App: $pkg")
            appendLine("Detected → viewer=$viewer feedTab=$feedTab explore=$explore")
            appendLine("Selected: ${selected.joinToString()}")
            appendLine("IDs on screen:")
            appendLine(ids.joinToString(", "))
        }
        prefs.edit().putString(Prefs.LAST_DUMP, report).apply()
    }

    companion object {
        private const val BACK_COOLDOWN_MS = 700L
        private const val CHECK_INTERVAL_MS = 400L
        private const val EVENT_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
    }
}
