package com.kody.scrollblock

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * One transparent, non-touchable full-screen layer that paints solid patches over
 * reels in the Instagram home feed and Explore grid. Touches pass straight through,
 * so scrolling the feed works normally.
 */
class PatchOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: PatchView? = null

    val isShowing: Boolean get() = view != null

    fun show(rects: List<Rect>) {
        if (rects.isEmpty()) { hide(); return }
        val v = view ?: add() ?: return
        v.setRects(rects)
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    private fun add(): PatchView? {
        val v = PatchView(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.windowAnimations = 0 // appear and disappear instantly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return try {
            wm.addView(v, lp)
            view = v
            v
        } catch (e: Exception) {
            null
        }
    }

    private class PatchView(ctx: Context) : View(ctx) {
        private val rects = mutableListOf<Rect>()
        private val loc = IntArray(2)
        private val box = RectF()
        private val density = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 15f * density
        }

        fun setRects(r: List<Rect>) {
            if (r == rects) return
            rects.clear()
            rects.addAll(r.map { Rect(it) })
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            fill.color = if (night) 0xFF1C1F26.toInt() else 0xFFE6E8EC.toInt()
            label.color = if (night) 0xFF8E95A1.toInt() else 0xFF5F6670.toInt()
            getLocationOnScreen(loc)
            val radius = 10f * density
            for (r in rects) {
                box.set(
                    (r.left - loc[0]).toFloat(), (r.top - loc[1]).toFloat(),
                    (r.right - loc[0]).toFloat(), (r.bottom - loc[1]).toFloat()
                )
                canvas.drawRoundRect(box, radius, radius, fill)
                if (box.height() > 48f * density) {
                    canvas.drawText("Reel hidden", box.centerX(), box.centerY() + label.textSize / 3f, label)
                }
            }
        }
    }
}
