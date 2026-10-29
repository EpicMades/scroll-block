package com.kody.scrollblock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.min

/**
 * A see-through layer over Instagram's Reels button that draws a red slash across
 * the icon and swallows taps, so the button no longer works.
 */
class TabCover(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var current: Rect? = null

    val isShowing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(r: Rect) {
        if (view != null && r == current) return

        val lp = WindowManager.LayoutParams(
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
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val v = view
        try {
            if (v == null) {
                val nv = SlashView(ctx).apply {
                    setOnTouchListener { _, _ -> true } // eat taps
                }
                wm.addView(nv, lp)
                view = nv
            } else {
                wm.updateViewLayout(v, lp)
            }
            current = Rect(r)
        } catch (_: Exception) {
            view = null
            current = null
        }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        current = null
    }

    private class SlashView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF3B30.toInt()
            strokeWidth = 3.5f * density
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            // A slash about the size of the tab icon, centered on it.
            val half = min(width, height) * 0.30f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(cx - half, cy + half, cx + half, cy - half, red)
        }
    }
}
