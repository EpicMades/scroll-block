package com.kody.scrollblock

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * A blurred, non-touchable layer drawn over Reels/Shorts/Explore, plus muting media audio.
 * It stops above the app's bottom tab bar, so you can still tap a tab to leave.
 */
class ReelOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var dialog: Dialog? = null
    private var label: TextView? = null
    private var weMuted = false

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(heightPx: Int, message: String, mute: Boolean) {
        try {
            val d = dialog ?: build().also { dialog = it }
            label?.text = message
            val w = d.window
            if (w != null) {
                val lp = w.attributes
                if (lp.height != heightPx) {
                    lp.height = heightPx
                    w.attributes = lp
                }
            }
            if (!d.isShowing) d.show()
        } catch (e: Exception) {
            dialog = null
        }
        if (mute) muteAudio() else unmuteAudio()
    }

    fun hide() {
        try { dialog?.dismiss() } catch (_: Exception) {}
        unmuteAudio()
    }

    private fun muteAudio() {
        if (weMuted) return
        if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            weMuted = true
        }
    }

    private fun unmuteAudio() {
        if (!weMuted) return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        weMuted = false
    }

    private fun build(): Dialog {
        val d = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        d.setCancelable(false)
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        label = tv
        d.setContentView(
            tv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val w = d.window ?: return d
        w.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        w.setWindowAnimations(0)
        w.setGravity(Gravity.TOP or Gravity.START)
        w.decorView.setPadding(0, 0, 0, 0)

        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled
        if (canBlur) {
            w.setBackgroundDrawable(ColorDrawable(0x55000000))
            w.setBackgroundBlurRadius(160)
        } else {
            // Blur unavailable (e.g. Power saving on): use a near-opaque frosted color instead.
            w.setBackgroundDrawable(ColorDrawable(0xF2101418.toInt()))
        }
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        return d
    }
}
