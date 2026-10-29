package com.kody.scrollblock

import android.content.Context
import android.media.AudioManager

/** Mutes media audio while any reason is active, and restores it afterwards. */
class Muter(ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val reasons = mutableSetOf<String>()
    private var weMuted = false

    fun set(reason: String, on: Boolean) {
        if (on) reasons.add(reason) else reasons.remove(reason)
        apply()
    }

    fun clear() {
        reasons.clear()
        apply()
    }

    private fun apply() {
        if (reasons.isNotEmpty()) {
            if (!weMuted && !audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                weMuted = true
            }
        } else if (weMuted) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            weMuted = false
        }
    }
}
