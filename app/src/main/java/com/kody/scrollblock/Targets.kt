package com.kody.scrollblock

/**
 * Everything that identifies a Reels / Shorts / Explore screen lives here.
 * Instagram and YouTube rename internal view IDs now and then. If blocking stops
 * working after an app update, turn on "Debug" in Scroll Block, open the screen,
 * and compare the captured IDs with the ones below.
 */
object Targets {
    const val IG = "com.instagram.android"
    const val YT = "com.google.android.youtube"

    class AppTargets(
        /** The full-screen vertical swipe player (Reels viewer / Shorts player). */
        val viewerIds: Set<String>,
        /** Bottom-bar tab that opens the endless feed. */
        val feedTabIds: List<String>,
        val feedTabLabel: String?,
        /** Instagram's Explore tab (YouTube has none we block). */
        val exploreTabIds: List<String> = emptyList(),
        val exploreTabLabel: String? = null,
        /** The bottom navigation bar. Tab labels are only matched inside it. */
        val tabBarIds: List<String> = emptyList(),
    )

    val instagram = AppTargets(
        viewerIds = setOf(
            "$IG:id/clips_viewer_view_pager",
            "$IG:id/clips_viewer_pager",
            "$IG:id/clips_viewer_container",
        ),
        feedTabIds = listOf("$IG:id/clips_tab"),
        feedTabLabel = "Reels",
        exploreTabIds = listOf("$IG:id/search_tab"),
        exploreTabLabel = "Search and explore",
        tabBarIds = listOf("$IG:id/tab_bar"),
    )

    val youtube = AppTargets(
        viewerIds = setOf(
            "$YT:id/reel_recycler",
            "$YT:id/reel_player_page_container",
            "$YT:id/reel_watch_player",
        ),
        feedTabIds = emptyList(),
        feedTabLabel = "Shorts",
        tabBarIds = listOf("$YT:id/pivot_bar"),
    )

    fun forPackage(pkg: String): AppTargets? = when (pkg) {
        IG -> instagram
        YT -> youtube
        else -> null
    }
}
