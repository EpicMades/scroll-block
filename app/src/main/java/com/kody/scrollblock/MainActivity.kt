package com.kody.scrollblock

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var dumpText: TextView
    private val switches = mutableMapOf<String, Switch>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(12), 0, dp(4)) }
        col.addView(status)
        col.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        fun section(title: String) = col.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(4))
        })

        fun toggle(key: String, label: String, def: Boolean) {
            val s = Switch(this).apply {
                text = label
                textSize = 17f
                setPadding(0, dp(10), 0, dp(10))
                isChecked = prefs.getBoolean(key, def)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                    if (key == Prefs.ENABLED) {
                        TileService.requestListeningState(
                            this@MainActivity,
                            ComponentName(this@MainActivity, ToggleTileService::class.java)
                        )
                    }
                    if (key == Prefs.DEBUG) refreshDump()
                }
            }
            switches[key] = s
            col.addView(s)
        }

        section("MASTER")
        toggle(Prefs.ENABLED, "Blocking on", true)

        section("INSTAGRAM")
        toggle(Prefs.IG_REELS, "Hide the Reels tab + block reel scrolling", true)
        toggle(Prefs.IG_EXPLORE, "Hide the Explore tab", true)
        toggle(Prefs.IG_HOME_STORIES_ONLY, "Home: show stories only (feed hidden, no scrolling)", true)
        toggle(Prefs.IG_EXPLORE_GRID, "If Explore is allowed: cover its grid (search still works)", false)

        section("YOUTUBE")
        toggle(Prefs.YT_SHORTS, "Block Shorts tab + shorts scrolling", true)

        section("BOTH")
        toggle(Prefs.ALLOW_SHARED, "Allow a single reel/short opened from a DM or link", true)
        toggle(Prefs.BLUR_MODE, "Blur & mute blocked screens (off = send me back)", true)

        count = TextView(this).apply { textSize = 14f; setPadding(0, dp(16), 0, 0) }
        col.addView(count)

        col.addView(TextView(this).apply {
            text = "Tip: add the \"Scroll Block\" tile to Quick Settings (swipe down twice → ✎ edit) to switch blocking on and off without opening this app."
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
        })

        section("TROUBLESHOOTING")
        toggle(Prefs.DEBUG, "Debug: capture screen info", false)
        dumpText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(dumpText)
        col.addView(Button(this).apply {
            text = "Copy captured info"
            setOnClickListener {
                val txt = Prefs.get(this@MainActivity).getString(Prefs.LAST_DUMP, "") ?: ""
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Scroll Block debug", txt))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val prefs = Prefs.get(this)
        switches.forEach { (key, s) ->
            s.isChecked = prefs.getBoolean(key, Prefs.default(key))
        }
        val on = isServiceEnabled()
        status.text = if (on) "✅ Accessibility permission is on. Blocking works in the background."
        else "⚠️ Turn on Scroll Block under Settings → Accessibility → Installed apps."
        count.text = "Blocks so far: ${prefs.getInt(Prefs.BLOCK_COUNT, 0)}"
        refreshDump()
    }

    private fun refreshDump() {
        val prefs = Prefs.get(this)
        val dbg = prefs.getBoolean(Prefs.DEBUG, false)
        dumpText.visibility = if (dbg) View.VISIBLE else View.GONE
        dumpText.text = prefs.getString(Prefs.LAST_DUMP, null)
            ?: "Open Instagram or YouTube on the screen in question, then come back here."
    }

    private fun isServiceEnabled(): Boolean {
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, BlockerService::class.java)
        val names = setOf(cn.flattenToString(), cn.flattenToShortString())
        return list.split(':').any { entry -> names.any { it.equals(entry, ignoreCase = true) } }
    }
}
