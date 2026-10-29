package com.kody.scrollblock

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Covers a rectangle of the screen with a heavily blurred snapshot of what was there.
 * The snapshot is taken just before the cover appears, then shrunk and blurred, so
 * nothing underneath is recognizable. Falls back to a solid dark color if a snapshot
 * isn't available.
 *
 * touchable = true  -> swallows taps/scrolls in that area (Explore grid)
 * touchable = false -> taps pass through (full-screen Reels/Shorts cover)
 */
class BlurCover(private val service: AccessibilityService, private val touchable: Boolean) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var image: ImageView? = null
    private var tint: View? = null
    private var label: TextView? = null
    private var rect: Rect? = null
    private var message = ""
    private var generation = 0
    private var waiting = false

    val isShowing: Boolean get() = root != null || waiting

    fun show(r: Rect, msg: String) {
        message = msg
        label?.text = msg
        if (root != null) {
            if (r != rect) {
                rect = Rect(r)
                updateLayout(r)
            }
            return
        }
        if (waiting && r == rect) return
        rect = Rect(r)
        // Snapshot first, then add the cover, so the cover isn't in its own picture.
        snapshot(r, addAfter = true)
    }

    fun hide() {
        generation++
        waiting = false
        handler.removeCallbacksAndMessages(null)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        image = null
        tint = null
        label = null
        rect = null
    }

    private fun snapshot(r: Rect, addAfter: Boolean) {
        val gen = ++generation
        if (addAfter) {
            waiting = true
            // Never leave the reel visible for long: add the cover after 250 ms regardless.
            handler.postDelayed({ if (gen == generation && waiting) addCover(null) }, 250)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (addAfter) addCover(null)
            return
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        try {
                            if (gen != generation) return
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val blurred = hw?.let { shrink(it, r) }
                            hw?.recycle()
                            if (waiting) addCover(blurred) else setPicture(blurred)
                        } catch (_: Exception) {
                            if (waiting) addCover(null)
                        } finally {
                            hb.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // Screenshots are rate-limited; fall back to the solid cover.
                        if (gen == generation && waiting) addCover(null)
                    }
                })
        } catch (_: Exception) {
            if (addAfter) addCover(null)
        }
    }

    /** Crop to the covered area and shrink 10x: scaling back up is itself a strong blur. */
    private fun shrink(hw: Bitmap, r: Rect): Bitmap? {
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val crop = Rect(r)
        if (!crop.intersect(0, 0, soft.width, soft.height)) { soft.recycle(); return null }
        val cut = Bitmap.createBitmap(soft, crop.left, crop.top, crop.width(), crop.height())
        val small = Bitmap.createScaledBitmap(
            cut, (crop.width() / 10).coerceAtLeast(1), (crop.height() / 10).coerceAtLeast(1), true
        )
        if (cut !== soft) cut.recycle()
        soft.recycle()
        return small
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addCover(picture: Bitmap?) {
        waiting = false
        if (root != null) { setPicture(picture); return }
        val r = rect ?: return
        val ctx = service
        val frame = FrameLayout(ctx)
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
            }
        }
        val shade = View(ctx)
        val tv = TextView(ctx).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 0f, Color.BLACK)
        }
        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        frame.addView(img, full)
        frame.addView(shade, FrameLayout.LayoutParams(full))
        frame.addView(tv, FrameLayout.LayoutParams(full))
        if (touchable) frame.setOnTouchListener { _, _ -> true }

        root = frame
        image = img
        tint = shade
        label = tv
        setPicture(picture)
        try {
            wm.addView(frame, params(r))
        } catch (_: Exception) {
            root = null
        }
    }

    private fun setPicture(picture: Bitmap?) {
        val img = image ?: return
        if (picture != null) {
            img.setImageBitmap(picture)
            tint?.setBackgroundColor(0x40000000)
        } else if (img.drawable == null) {
            tint?.setBackgroundColor(0xF2101418.toInt())
        }
    }

    private fun updateLayout(r: Rect) {
        val v = root ?: return
        try { wm.updateViewLayout(v, params(r)) } catch (_: Exception) {}
    }

    private fun params(r: Rect): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0 // appear and disappear instantly
            x = r.left
            y = r.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }
}
