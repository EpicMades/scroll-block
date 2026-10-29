package com.kody.scrollblock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Draws a replacement for Instagram's bottom tab bar with only the allowed tabs
 * (Home, Messages, Profile, plus Search if Explore is allowed). Tapping one of our
 * buttons taps the real tab underneath. The background color is sampled from the
 * real tab bar once, so it matches Instagram's theme.
 */
class CustomTabBar(
    private val service: AccessibilityService,
    private val onTap: (String) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = Prefs.get(service)
    private val density = service.resources.displayMetrics.density

    private var root: LinearLayout? = null
    private var rect: Rect? = null
    private var tabs: List<String> = emptyList()
    private var selected: String? = null
    private var bgColor = 0
    private var waiting = false
    private var generation = 0

    val isShowing: Boolean get() = root != null || waiting

    private var app = ""

    fun show(pkg: String, r: Rect, tabKinds: List<String>, sel: String?) {
        if (pkg != app && root != null) hide()
        app = pkg
        val bar = root
        if (bar != null) {
            if (r != rect) {
                rect = Rect(r)
                try { wm.updateViewLayout(bar, params(r)) } catch (_: Exception) {}
            }
            if (tabKinds != tabs || sel != selected) {
                tabs = tabKinds
                selected = sel
                fill(bar)
            }
            return
        }
        rect = Rect(r)
        tabs = tabKinds
        selected = sel
        if (waiting) return
        // Read Instagram's real bar color fresh every time our bar appears.
        sampleThenAdd(r)
    }

    fun hide() {
        generation++
        waiting = false
        handler.removeCallbacksAndMessages(null)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        rect = null
    }

    private fun night() = (service.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun colorKey() = (if (night()) "bar_color_night_v2_" else "bar_color_day_v2_") + app

    private fun savedOrDefault(): Int = prefs.getInt(colorKey(), 0).takeIf { it != 0 } ?: fallbackColor()

    private fun fallbackColor() = if (night()) {
        if (app == Targets.YT) 0xFF0F0F0F.toInt() else 0xFF0C1014.toInt()
    } else Color.WHITE

    /** Take one screenshot before our bar exists and read the real bar's background color. */
    private fun sampleThenAdd(r: Rect) {
        val gen = ++generation
        waiting = true
        handler.postDelayed({ if (gen == generation && waiting) add(savedOrDefault()) }, 300)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { add(savedOrDefault()); return }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        try {
                            if (gen != generation) return
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return
                            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            if (soft != null) {
                                val c = sampleBackground(soft, r)
                                soft.recycle()
                                prefs.edit().putInt(colorKey(), c).apply()
                                if (waiting) add(c) else recolor(c)
                            }
                        } catch (_: Exception) {
                        } finally {
                            hb.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (gen == generation && waiting) add(savedOrDefault())
                    }
                })
        } catch (_: Exception) {
            add(savedOrDefault())
        }
    }

    /**
     * Read many points in the gaps between Instagram's tab icons (low in the bar,
     * away from the top divider) and use the most common color.
     */
    private fun sampleBackground(bmp: Bitmap, r: Rect): Int {
        val counts = HashMap<Int, Int>()
        val ys = listOf(0.55f, 0.7f, 0.85f).map { (r.top + r.height() * it).toInt() }
        val xs = (0..10).map { (r.left + r.width() * (it / 10f)).toInt() }
        for (y in ys) for (x in xs) {
            val px = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
            val c = px or 0xFF000000.toInt()
            counts[c] = (counts[c] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: fallbackColor()
    }

    private fun add(color: Int) {
        waiting = false
        if (root != null) { recolor(color); return }
        val r = rect ?: return
        bgColor = color
        val bar = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        fill(bar)
        root = bar
        try { wm.addView(bar, params(r)) } catch (_: Exception) { root = null }
    }

    private fun recolor(color: Int) {
        bgColor = color
        root?.let { fill(it) }
    }

    private fun fill(bar: LinearLayout) {
        bar.removeAllViews()
        bar.setBackgroundColor(bgColor)
        val dark = luminance(bgColor) < 0.5
        val iconColor = if (dark) 0xFFF5F5F5.toInt() else 0xFF262626.toInt()
        // Thin divider along the top, like Instagram's own bar.
        bar.foreground = TopLine(iconColor and 0x1FFFFFFF, density)
        for (kind in tabs) {
            val cell = FrameLayout(service).apply {
                isClickable = true
                setOnClickListener { onTap(kind) }
            }
            val size = (26 * density).toInt()
            cell.addView(
                TabIcon(service, kind, iconColor, kind == selected),
                FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            )
            bar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun params(r: Rect) = WindowManager.LayoutParams(
        r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left
        y = r.top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private class TopLine(color: Int, density: Float) : android.graphics.drawable.Drawable() {
        private val paint = Paint().apply { this.color = color; strokeWidth = density * 0.7f }
        override fun draw(canvas: Canvas) {
            canvas.drawLine(0f, 0f, bounds.width().toFloat(), 0f, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Simple line icons in Instagram's style. Selected tab is drawn bolder. */
    private class TabIcon(ctx: Context, private val kind: String, color: Int, selected: Boolean) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = (if (selected) 2.6f else 1.8f) * d
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val p = paint.strokeWidth
            path.reset()
            when (kind) {
                "home" -> {
                    path.moveTo(w * 0.12f, h * 0.45f)
                    path.lineTo(w * 0.5f, h * 0.1f)
                    path.lineTo(w * 0.88f, h * 0.45f)
                    path.lineTo(w * 0.88f, h * 0.9f)
                    path.lineTo(w * 0.12f, h * 0.9f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "messages" -> { // paper plane
                    path.moveTo(w * 0.08f, h * 0.12f)
                    path.lineTo(w * 0.92f, h * 0.12f)
                    path.lineTo(w * 0.52f, h * 0.9f)
                    path.lineTo(w * 0.42f, h * 0.48f)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawLine(w * 0.42f, h * 0.48f, w * 0.92f, h * 0.12f, paint)
                }
                "search" -> {
                    canvas.drawCircle(w * 0.43f, h * 0.43f, w * 0.32f, paint)
                    canvas.drawLine(w * 0.67f, h * 0.67f, w * 0.9f - p / 2, h * 0.9f - p / 2, paint)
                }
                "subscriptions" -> { // stacked screens with a play triangle
                    canvas.drawLine(w * 0.25f, h * 0.12f, w * 0.75f, h * 0.12f, paint)
                    canvas.drawLine(w * 0.17f, h * 0.24f, w * 0.83f, h * 0.24f, paint)
                    canvas.drawRoundRect(w * 0.08f, h * 0.36f, w * 0.92f, h * 0.9f, w * 0.08f, w * 0.08f, paint)
                    path.moveTo(w * 0.43f, h * 0.5f)
                    path.lineTo(w * 0.62f, h * 0.63f)
                    path.lineTo(w * 0.43f, h * 0.76f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "profile", "you" -> {
                    canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.5f - p, paint)
                    canvas.drawCircle(w * 0.5f, h * 0.4f, w * 0.16f, paint)
                    path.moveTo(w * 0.22f, h * 0.82f)
                    path.cubicTo(w * 0.3f, h * 0.6f, w * 0.7f, h * 0.6f, w * 0.78f, h * 0.82f)
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}
