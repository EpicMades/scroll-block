# Scroll Block

Blocks the endless-scroll parts of Instagram and YouTube on Android, and leaves the rest of each app working.

| Blocked | Still works |
|---|---|
| Instagram Reels tab | Feed posts, Stories, DMs, profiles |
| Swiping to the next reel | A reel someone sends you |
| Instagram Explore tab | — |
| YouTube Shorts tab | Normal YouTube videos, search, subscriptions |
| Swiping to the next short | A short someone sends you |

**How it blocks:** when you open one of these screens, the app presses Back for you and shows a short message.

**Turning it on and off:** use the in-app switches, or the **Scroll Block** tile in Quick Settings.

**Battery:** Android only wakes the app while Instagram or YouTube is open on screen. When blocking is switched off, the app stops receiving events entirely. It never runs a timer, a location request, or a network call.

## Build the APK without a computer (GitHub, free)

1. Make a free account at github.com and create a new **private** repository.
2. On the repo page, tap **Add file → Upload files**.
3. Unzip `ScrollBlock.zip` and upload *everything inside the `ScrollBlock` folder*. That includes the hidden `.github` folder. If your phone hides it, create the file `.github/workflows/build.yml` by hand with **Add file → Create new file** and paste in its contents.
4. Open the **Actions** tab. The "Build APK" job runs on its own; if it doesn't, tap it and choose **Run workflow**. A run takes about 3–5 minutes.
5. Open the finished run, download **ScrollBlock-apk**, unzip it, and tap `app-debug.apk` to install.

## Or build it in Android Studio

Open the folder in Android Studio, then choose **Run** or **Build → Build APK(s)**.

## First-time setup on the S24 Ultra

1. Install the APK. If Android asks, allow installs from your browser or Files app.
2. **Allow restricted settings.** Android blocks accessibility access for apps that didn't come from the Play Store until you allow it.
   1. Go to **Settings → Apps → Scroll Block**.
   2. Tap the **⋮** menu (top right) and choose **Allow restricted settings**.
3. **Turn on the service.** Go to **Settings → Accessibility → Installed apps → Scroll Block** and turn it **On**.
4. **Keep Samsung from stopping it.** Go to **Settings → Battery → Background usage limits → Never sleeping apps** and add Scroll Block.
5. **Add the tile.** Swipe down twice, tap the ✎ (edit) button, and drag **Scroll Block** into your tiles.

## If something stops being blocked after an Instagram or YouTube update

The app recognizes screens by Instagram's and YouTube's internal view names, and those can change in an update.

1. In Scroll Block, turn on **Debug: capture screen info**.
2. Open the screen that isn't being blocked.
3. Come back to Scroll Block and tap **Copy captured info**.
4. Compare what you copied with the names in `app/src/main/java/com/kody/scrollblock/Targets.kt`, add any new ones, and rebuild.
