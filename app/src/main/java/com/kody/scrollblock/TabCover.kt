package com.kody.scrollblock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A small patch, the same color as Instagram's tab bar, over the Reels button.
 * It swallows taps, so the button is effectively gone.
 */
class TabCover(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var current: Rect? = null
    private var currentColor = 0

    val isShowing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(r: Rect) {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        if (view != null && r == current && color == currentColor) return

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
                val nv = View(ctx).apply {
                    setBackgroundColor(color)
                    setOnTouchListener { _, _ -> true } // eat taps
                }
                wm.addView(nv, lp)
                view = nv
            } else {
                v.setBackgroundColor(color)
                wm.updateViewLayout(v, lp)
            }
            current = Rect(r)
            currentColor = color
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
}
