package com.kody.scrollblock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.math.abs

/**
 * Watches only Instagram and YouTube (restricted in accessibility_config.xml, so the
 * system never wakes this service for any other app).
 *
 * Instagram:
 *  - Reels button in the tab bar -> covered, taps do nothing.
 *  - Reels in the home feed and Explore grid -> covered with a patch, audio muted.
 *  - Tapping through to a feed/Explore reel anyway -> player blurred + muted.
 *  - Reels tab (if reached some other way) -> blurred + muted.
 *  - Optionally, all of Explore -> blurred.
 * YouTube:
 *  - Shorts tab -> blurred + muted.
 * Both:
 *  - A single reel/short opened from a DM or link -> plays normally.
 *  - Swiping to the next one -> Back.
 */
class BlockerService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var blur: BlurCover
    private lateinit var exploreCover: BlurCover
    private lateinit var patches: PatchOverlay
    private lateinit var tabCover: TabCover
    private lateinit var muter: Muter
    private val handler = Handler(Looper.getMainLooper())

    private var enabled = true
    private var igReels = true
    private var igFeedReels = true
    private var igExplore = false
    private var igExploreGrid = true
    private var ytShorts = true
    private var allowShared = true
    private var blurMode = true
    private var debug = false

    private var lastBackAt = 0L
    private var lastCheckAt = 0L
    private var lastDumpAt = 0L
    private var lastToastAt = 0L
    private var pendingPkg: String? = null
    private var lastBlurMsg: String? = null

    /** True while the Instagram home feed or Explore is on screen (patches follow scrolling). */
    private var patchMode = false
    private var lastPatchedAt = 0L
    private var lastFullCheckAt = 0L
    private var patchClipBottom = Int.MAX_VALUE

    // Any swipe player on screen (allowed or not)
    private var seenViewerPkg: String? = null
    private var viewerFromFeed = false

    // The single reel/short currently allowed to play
    private var viewerPkg: String? = null
    private var viewerStartIndex = -1
    private var viewerStartScrollY = Int.MIN_VALUE
    private var viewerOpenedAt = 0L

    private val trailingCheck = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        pendingPkg = null
        checkScreen(pkg, SystemClock.uptimeMillis())
    }

    /** While anything is drawn on screen, make sure it disappears the moment you leave the app. */
    private val overlayWatch = Runnable {
        if (!anyOverlay()) return@Runnable
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg == null || Targets.forPackage(pkg) == null) {
            hideAll()
        } else {
            checkScreen(pkg, SystemClock.uptimeMillis())
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs.get(this)
        blur = BlurCover(this, touchable = false)
        exploreCover = BlurCover(this, touchable = true)
        patches = PatchOverlay(this)
        tabCover = TabCover(this)
        muter = Muter(this)
        loadPrefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideAll()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.LAST_DUMP || key == Prefs.BLOCK_COUNT) return
        loadPrefs()
    }

    private fun pref(key: String) = prefs.getBoolean(key, Prefs.default(key))

    private fun loadPrefs() {
        enabled = pref(Prefs.ENABLED)
        igReels = pref(Prefs.IG_REELS)
        igFeedReels = pref(Prefs.IG_FEED_REELS)
        igExplore = pref(Prefs.IG_EXPLORE)
        igExploreGrid = pref(Prefs.IG_EXPLORE_GRID)
        ytShorts = pref(Prefs.YT_SHORTS)
        allowShared = pref(Prefs.ALLOW_SHARED)
        blurMode = pref(Prefs.BLUR_MODE)
        debug = pref(Prefs.DEBUG)

        // When switched off, ask the system to stop sending events entirely (zero battery cost).
        serviceInfo?.let { info ->
            info.eventTypes = if (enabled) EVENT_MASK else 0
            serviceInfo = info
        }
        hideAll() // redrawn on the next event with the new settings
        if (!enabled) {
            handler.removeCallbacks(trailingCheck)
            viewerPkg = null
            seenViewerPkg = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled) return
        val pkg = event.packageName?.toString() ?: return
        if (Targets.forPackage(pkg) == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < BACK_COOLDOWN_MS) return

        // Patches have to follow the feed as it scrolls, so check more often there.
        val interval = if (patchMode && pkg == Targets.IG) FAST_INTERVAL_MS else CHECK_INTERVAL_MS

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                onScrolled(pkg, event, now)
                if (now - lastBackAt >= BACK_COOLDOWN_MS && patchMode) throttledCheck(pkg, now, interval)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkScreen(pkg, now)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> throttledCheck(pkg, now, interval)
        }
    }

    /**
     * While scrolling the feed, only move the patches (2 quick lookups) and do the
     * full screen check a few times a second. This keeps patches glued to the reels.
     */
    private fun quickPatchUpdate(pkg: String, now: Long): Boolean {
        if (!patchMode || pkg != Targets.IG || now - lastFullCheckAt >= FULL_CHECK_MS) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        lastCheckAt = now
        val rects = findReelRects(root, resources.displayMetrics.widthPixels, patchClipBottom)
        patches.show(rects)
        muter.set("patch", rects.isNotEmpty())
        if (rects.isNotEmpty()) lastPatchedAt = now
        scheduleWatch()
        return true
    }

    private fun throttledCheck(pkg: String, now: Long, interval: Long) {
        if (now - lastCheckAt >= interval) {
            if (quickPatchUpdate(pkg, now)) return
            checkScreen(pkg, now)
        } else if (pendingPkg == null) {
            pendingPkg = pkg
            handler.postDelayed(trailingCheck, interval - (now - lastCheckAt))
        }
    }

    private fun featureOn(pkg: String) = if (pkg == Targets.IG) igReels else ytShorts

    private fun checkScreen(pkg: String, now: Long) {
        lastCheckAt = now
        lastFullCheckAt = now
        val t = Targets.forPackage(pkg) ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val screen = screenBounds()
        val screenW = screen.width()
        val screenH = screen.height()
        val tabBars = t.tabBarIds.flatMap { root.findAccessibilityNodeInfosByViewId(it) }
        val barTop = tabBarTop(tabBars, screenH)
        val viewer = hasAnyId(root, t.viewerIds)
        // The feed tab only counts when the swipe player is actually on screen.
        val onFeedTab = viewer && isTabSelected(root, tabBars, t.feedTabIds, t.feedTabLabel)
        val isIg = pkg == Targets.IG
        val onExplore = isIg && isTabSelected(root, tabBars, t.exploreTabIds, t.exploreTabLabel)
        val onHome = isIg && isTabSelected(root, tabBars, t.homeTabIds, t.homeTabLabel)

        // Remember where a player came from: right after reels were covered = tapped from the feed.
        if (viewer) {
            if (seenViewerPkg != pkg) {
                seenViewerPkg = pkg
                viewerFromFeed = isIg && igFeedReels && now - lastPatchedAt < FROM_FEED_WINDOW_MS
            }
        } else if (seenViewerPkg == pkg) {
            seenViewerPkg = null
            viewerFromFeed = false
        }

        if (debug) dump(root, pkg, viewer, onFeedTab, onExplore, onHome, now)

        // Hide the Reels button whenever Instagram's tab bar is showing.
        if (isIg && igReels) updateTabCover(root, tabBars) else tabCover.hide()

        val name = if (isIg) "Reels" else "Shorts"
        var exploreCovered = false
        when {
            igExplore && onExplore -> {
                clearPatches()
                restrict("Explore", mute = false, barTop, screenW, screenH, now)
            }
            featureOn(pkg) && onFeedTab -> {
                clearPatches()
                restrict(name, mute = true, barTop, screenW, screenH, now)
            }
            featureOn(pkg) && viewer && (!allowShared || viewerFromFeed) -> {
                clearPatches()
                restrict(name, mute = true, barTop, screenW, screenH, now)
            }
            isIg && igExploreGrid && onExplore && !viewer && !isSearching(root, screenH) -> {
                // Explore page itself: cover everything between the search bar and the tab bar.
                clearPatches()
                hideBlur()
                val top = searchBarBottom(root, screenH) ?: (screenH * 0.18f).toInt()
                val bottom = barTop ?: screenH
                if (bottom - top > 0) {
                    if (!exploreCover.isShowing) countBlock()
                    exploreCover.show(
                        Rect(0, top, screenW, bottom),
                        "Explore hidden by Scroll Block\nTap the search bar to look something up"
                    )
                    exploreCovered = true
                }
            }
            else -> {
                hideBlur()
                val patchable = isIg && igFeedReels && !viewer && (onHome || onExplore)
                patchMode = patchable
                if (patchable) {
                    patchClipBottom = barTop ?: screenH
                    val rects = findReelRects(root, screenW, patchClipBottom)
                    patches.show(rects)
                    muter.set("patch", rects.isNotEmpty())
                    if (rects.isNotEmpty()) lastPatchedAt = now
                } else {
                    clearPatches()
                }
                trackAllowedViewer(pkg, viewer, now)
            }
        }
        if (!exploreCovered) exploreCover.hide()
        scheduleWatch()
    }

    private fun trackAllowedViewer(pkg: String, viewer: Boolean, now: Long) {
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

    private fun restrict(what: String, mute: Boolean, barTop: Int?, screenW: Int, screenH: Int, now: Long) {
        viewerPkg = null
        if (!blurMode) {
            block("$what is blocked", now)
            return
        }
        val msg = if (barTop != null) "$what hidden by Scroll Block\nTap a tab below to leave"
        else "$what hidden by Scroll Block\nSwipe back to leave"
        val wasShowing = blur.isShowing
        blur.show(Rect(0, 0, screenW, barTop ?: screenH), msg)
        muter.set("blur", mute)
        if (!wasShowing || lastBlurMsg != msg) {
            lastBlurMsg = msg
            if (!wasShowing) countBlock()
        }
    }

    private fun anyOverlay() = ::blur.isInitialized &&
        (blur.isShowing || exploreCover.isShowing || patches.isShowing || tabCover.isShowing)

    private fun scheduleWatch() {
        handler.removeCallbacks(overlayWatch)
        if (anyOverlay()) handler.postDelayed(overlayWatch, WATCH_INTERVAL_MS)
    }

    private fun hideBlur() {
        if (!::blur.isInitialized) return
        lastBlurMsg = null
        blur.hide()
        muter.set("blur", false)
    }

    private fun clearPatches() {
        if (!::patches.isInitialized) return
        patchMode = false
        patches.hide()
        muter.set("patch", false)
    }

    private fun hideAll() {
        if (!::blur.isInitialized) return
        handler.removeCallbacks(overlayWatch)
        hideBlur()
        clearPatches()
        exploreCover.hide()
        tabCover.hide()
        muter.clear()
    }

    // ---- Instagram: Explore search bar ----

    private fun findSearchBar(root: AccessibilityNodeInfo, screenH: Int): AccessibilityNodeInfo? {
        for (id in Targets.instagram.searchBarIds) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        return root.findAccessibilityNodeInfosByText("Search").firstOrNull { n ->
            if (!n.isVisibleToUser) return@firstOrNull false
            val r = Rect()
            n.getBoundsInScreen(r)
            r.top < screenH / 4 && (n.isEditable || n.isClickable ||
                n.className?.toString()?.contains("EditText") == true)
        }
    }

    private fun searchBarBottom(root: AccessibilityNodeInfo, screenH: Int): Int? {
        val bar = findSearchBar(root, screenH) ?: return null
        val r = Rect()
        bar.getBoundsInScreen(r)
        return if (r.bottom in 1 until screenH / 3) r.bottom + 8 else null
    }

    /** True once you tap into the search bar or have typed something. */
    private fun isSearching(root: AccessibilityNodeInfo, screenH: Int): Boolean {
        val bar = findSearchBar(root, screenH) ?: return false
        if (bar.isFocused) return true
        val text = bar.text?.toString()?.trim().orEmpty()
        if (bar.isShowingHintText || text.isEmpty() || text.equals("Search", true)) return false
        return true
    }

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        } else {
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

    // ---- Instagram: Reels button and feed reels ----

    private fun updateTabCover(root: AccessibilityNodeInfo, tabBars: List<AccessibilityNodeInfo>) {
        val t = Targets.instagram
        var target: AccessibilityNodeInfo? =
            t.feedTabIds.flatMap { root.findAccessibilityNodeInfosByViewId(it) }.firstOrNull()
        if (target == null && t.feedTabLabel != null) {
            target = tabBars.flatMap { it.findAccessibilityNodeInfosByText(t.feedTabLabel) }
                .firstOrNull { n ->
                    n.contentDescription?.toString().equals(t.feedTabLabel, true) ||
                        n.text?.toString().equals(t.feedTabLabel, true)
                }
        }
        val node = target
        if (node == null || !node.isVisibleToUser) { tabCover.hide(); return }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) { tabCover.hide(); return }
        tabCover.show(r)
    }

    private fun findReelRects(root: AccessibilityNodeInfo, screenW: Int, clipBottom: Int): List<Rect> {
        val found = mutableListOf<Rect>()
        for (word in Targets.feedReelWords) {
            for (n in root.findAccessibilityNodeInfosByText(word)) {
                val desc = n.contentDescription?.toString() ?: continue
                if (!desc.startsWith(word, ignoreCase = true)) continue
                if (!n.isVisibleToUser) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                // Skip small icons/labels; reels are big tiles or full-width posts.
                if (r.width() < screenW / 4 || r.height() < screenW / 5) continue
                if (r.bottom > clipBottom) r.bottom = clipBottom
                if (r.top < 0) r.top = 0
                if (r.height() <= 0) continue
                found.add(r)
            }
        }
        // Drop duplicates and patches fully inside another patch.
        return found.distinct().filter { a ->
            found.none { b -> b != a && b.contains(a) }
        }
    }

    // ---- scrolling inside a swipe player ----

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
        hideBlur()
        performGlobalAction(GLOBAL_ACTION_BACK)
        countBlock()
        if (now - lastToastAt > 2500) {
            lastToastAt = now
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun countBlock() {
        prefs.edit().putInt(Prefs.BLOCK_COUNT, prefs.getInt(Prefs.BLOCK_COUNT, 0) + 1).apply()
    }

    // ---- node helpers ----

    private fun hasAnyId(root: AccessibilityNodeInfo, ids: Collection<String>): Boolean =
        ids.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }

    private fun tabBarTop(tabBars: List<AccessibilityNodeInfo>, screenH: Int): Int? {
        for (bar in tabBars) {
            if (!bar.isVisibleToUser) continue
            val r = Rect()
            bar.getBoundsInScreen(r)
            if (r.top in (screenH / 2)..screenH) return r.top
        }
        return null
    }

    /**
     * A tab counts as open only if its own node is selected. Text labels are only
     * matched inside the bottom tab bar, so a "Reels"/"Shorts" heading in the home
     * feed can never trigger a block.
     */
    private fun isTabSelected(
        root: AccessibilityNodeInfo,
        tabBars: List<AccessibilityNodeInfo>,
        ids: List<String>,
        label: String?,
    ): Boolean {
        for (id in ids) {
            for (n in root.findAccessibilityNodeInfosByViewId(id)) {
                if (n.isSelected) return true
            }
        }
        if (label != null) {
            for (bar in tabBars) {
                for (n in bar.findAccessibilityNodeInfosByText(label)) {
                    val desc = n.contentDescription?.toString()
                    val text = n.text?.toString()
                    if (!desc.equals(label, true) && !text.equals(label, true)) continue
                    if (n.isSelected) return true
                    val parent = n.parent
                    if (parent != null && parent != bar && parent.isSelected) return true
                }
            }
        }
        return false
    }

    // ---- debug capture (only runs when Debug is switched on) ----

    private fun dump(
        root: AccessibilityNodeInfo, pkg: String,
        viewer: Boolean, feedTab: Boolean, explore: Boolean, home: Boolean, now: Long,
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
        val mediaLines = mutableListOf<String>()
        for (word in listOf("reel", "video", "clip")) {
            for (n in root.findAccessibilityNodeInfosByText(word)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val id = n.viewIdResourceName?.substringAfter(":id/") ?: "-"
                val d = (n.contentDescription ?: n.text)?.toString()?.take(60) ?: ""
                mediaLines.add("$id | $d | ${r.width()}x${r.height()}")
            }
        }
        val report = buildString {
            appendLine("App: $pkg")
            appendLine("Detected → viewer=$viewer feedTab=$feedTab explore=$explore home=$home")
            appendLine("Selected: ${selected.joinToString()}")
            appendLine("Reel/video nodes:")
            mediaLines.distinct().take(25).forEach { appendLine("  $it") }
            appendLine("IDs on screen:")
            appendLine(ids.joinToString(", "))
        }
        prefs.edit().putString(Prefs.LAST_DUMP, report).apply()
    }

    companion object {
        private const val BACK_COOLDOWN_MS = 700L
        private const val CHECK_INTERVAL_MS = 150L
        private const val FAST_INTERVAL_MS = 16L
        private const val FULL_CHECK_MS = 300L
        private const val WATCH_INTERVAL_MS = 500L
        private const val FROM_FEED_WINDOW_MS = 3000L
        private const val EVENT_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
    }
}
