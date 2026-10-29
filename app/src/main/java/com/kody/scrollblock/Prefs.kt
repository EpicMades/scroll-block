package com.kody.scrollblock

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "scrollblock"

    const val ENABLED = "enabled"
    const val IG_REELS = "ig_reels"
    const val IG_FEED_REELS = "ig_feed_reels"
    const val IG_EXPLORE = "ig_explore_full"
    const val YT_SHORTS = "yt_shorts"
    const val ALLOW_SHARED = "allow_shared"
    const val BLUR_MODE = "blur_mode"
    const val DEBUG = "debug"
    const val LAST_DUMP = "last_dump"
    const val BLOCK_COUNT = "block_count"

    /** Default value for each on/off setting. */
    fun default(key: String): Boolean = when (key) {
        IG_EXPLORE, DEBUG -> false
        else -> true
    }

    fun get(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
