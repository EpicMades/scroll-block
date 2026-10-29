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
        /** Home tab, where reels mixed into the feed get covered. */
        val homeTabIds: List<String> = emptyList(),
        val homeTabLabel: String? = null,
        /** Explore page search bar. */
        val searchBarIds: List<String> = emptyList(),
        /** Home screen top bar (Instagram logo, likes, messages). */
        val homeActionBarIds: List<String> = emptyList(),
        /** The stories row at the top of Home. */
        val storiesTrayIds: List<String> = emptyList(),
        /** Posts and units in the home feed, used to find where stories end. */
        val feedItemIds: List<String> = emptyList(),
    )

    /**
     * Reels inside the Instagram home feed and Explore grid are found by their
     * accessibility description, which starts with one of these words
     * (e.g. "Reel by …", "Video by …").
     */
    val feedReelWords = listOf("Reel", "Video")

    /** The "Suggested reels" carousel in the home feed (header + the row of reels). */
    val feedReelRowIds = listOf("$IG:id/clips_header_container", "$IG:id/clips_items_view")

    /** YouTube's bottom tabs, found by their label inside the bottom bar. */
    val ytTabs = listOf(
        Targets.Tab("home", emptyList(), listOf("Home")),
        Targets.Tab("shorts", emptyList(), listOf("Shorts")),
        Targets.Tab("create", emptyList(), listOf("Create")),
        Targets.Tab("subscriptions", emptyList(), listOf("Subscriptions")),
        Targets.Tab("you", emptyList(), listOf("You", "Library")),
    )

    /** Instagram's bottom tabs, found by ID first and then by their label. */
    class Tab(val kind: String, val ids: List<String>, val labels: List<String>)

    val igTabs = listOf(
        Tab("home", listOf("$IG:id/feed_tab"), listOf("Home")),
        Tab("reels", listOf("$IG:id/clips_tab"), listOf("Reels")),
        Tab("search", listOf("$IG:id/search_tab"), listOf("Search and explore", "Search", "Explore")),
        Tab("messages", listOf("$IG:id/direct_tab", "$IG:id/inbox_tab"), listOf("Messages", "Direct", "Messenger")),
        Tab("profile", listOf("$IG:id/profile_tab"), listOf("Profile")),
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
        homeTabIds = listOf("$IG:id/feed_tab"),
        homeTabLabel = "Home",
        searchBarIds = listOf("$IG:id/action_bar_search_edit_text"),
        homeActionBarIds = listOf("$IG:id/main_feed_action_bar"),
        storiesTrayIds = listOf("$IG:id/reels_tray_container", "$IG:id/reels_tray", "$IG:id/stories_tray"),
        feedItemIds = listOf(
            "$IG:id/row_feed_profile_header", "$IG:id/clips_header_container",
            "$IG:id/row_feed_photo_imageview", "$IG:id/media_group",
        ),
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
