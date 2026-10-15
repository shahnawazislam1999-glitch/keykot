# PhoneDeck (native Android app)

A native Kotlin app: paste your PC link, it connects, and it stays **full screen always**
(no status bar, no navigation bar, no browser bar, screen kept awake).

The keyboard / touchpad / file screens come from your PC (`phone.html`), shown inside the app,
so any change you make to `phone.html` on the PC shows up here immediately.

## Build the APK on GitHub (no Android Studio needed)
1. Create a new GitHub repository.
2. Upload **everything in this folder**, including the hidden `.github` folder
   (easiest: install Git and run `git init`, `git add .`, `git commit`, `git push`; dragging a folder
   into the GitHub web page also works if you drag the `.github` folder itself).
3. Open the repo > **Actions** tab > **Build APK** > it runs by itself on push (or tap **Run workflow**).
4. When it turns green, open the run > **Artifacts** > **PhoneDeck-apk** > download, unzip, and you get `app-debug.apk`.
5. Copy the APK to the phone and install it (allow "install unknown apps" for your file manager/browser).

## Two modes
**1. Wi-Fi / network link** (keyboard, touchpad, files, clipboard): paste the link from the PC.
Needs the tablet and PC on the same network (hotspot, router, or USB tethering). No internet needed.

**2. Bluetooth keyboard & mouse** (no network, no PC server): the tablet pretends to be a real Bluetooth keyboard + mouse.
- Tap **Bluetooth keyboard & mouse** in the app and allow the Nearby devices permission.
- Tap **Make visible**. On the PC: Settings > Bluetooth & devices > Add device > Bluetooth > pick the tablet.
- Afterwards it reconnects from the PC, or use **Devices** in the app to connect to the paired PC.
- Keys: tap a modifier (Shift/Ctrl/Alt/Win) once, then the next key (one-shot). Touchpad: move, tap, 2-finger tap = right click,
  2-finger drag = scroll, tap then tap + hold = drag. Gear = speeds, tap window, hold time.
- **Word suggestions:** a bar above the keys suggests words as you type letters/numbers. Finished words (space, Enter, punctuation) are saved, so your own words are suggested next time. Tap a suggestion to add it (it types the rest of the word + a space). Long-press a suggestion to forget it. It suggests only, it never auto-completes.
- Letter keys show small letters normally and CAPITALS while Shift is on (symbols change too, e.g. 1 becomes !). Caps Lock lights up and flips the letters. If the PC's Caps Lock was already on when you connected, tap Caps once to match.
- Limits: Android 9+, some brands block it, US keyboard keys only, no files / clipboard / preview in this mode.

## Link mode (v2.0)
**Word suggestions** (bar at the top, can be moved to the bottom in the gear menu)
- Bar: gear | **Aa** on/off | 4 suggestions | connection dot + delay. Tap a suggestion: the rest of the word + a space is typed, so your half-typed word is replaced by the whole word. Long-press to forget a word.
- **Aa** switches suggestions on/off at any time (off = nothing shown, nothing saved).
- New words you finish typing are saved in the app's local storage and suggested next time; word pairs are saved too, so after a space it suggests the next word. Gear > Learned words clears them.
- Works for the page's on-screen keys, a text box (Android keyboard) and a physical keyboard. Password boxes are ignored.

**Shift** - letters on the page's keyboard show small normally and CAPITAL after Shift (symbols too: 1 becomes !). Tap Shift = next key only, double-tap Shift = Caps Lock, hold Shift while typing = capitals until you let go. Gear > "Shift changes the key letters" turns it off.

**Downloads** - done by the app itself into Downloads, always with the real file name (zip, apk, folders become Folder.zip). A bar shows progress, Open and cancel. Works for normal links, `<a download>` links and blob: downloads made by the page. If the name already exists, Android adds (1).

**Strong connection**
- Binds to the network the PC is really on (a Wi-Fi without internet is no longer dropped for mobile data) and holds a low-latency Wi-Fi lock.
- Checks the PC every few seconds: dot shows delay (green/orange/red), banner appears when the PC is lost, the page reloads itself when the PC is back.
- If the first load fails it retries by itself with growing waits. Reloads when you return after 45 s away. Recovers if the page process crashes.
- **Find PC** (connect screen or error screen / gear menu) scans the network when the PC's IP changed and rewrites the link. **Recent** keeps the last 8 links. **Test link** says exactly why it cannot connect.
- Gear > **Test key detection** shows which keys the helper script found on the PC page.

## Code map (search for `ANCHOR:`)
`assets/pd.js` JS_SHIFT_SYNC, JS_SUGGEST, JS_INSERT, JS_DOWNLOADS, JS_PROBE | `DeckActivity.kt` DECK_CONNECTION, DECK_DOWNLOADS, DECK_SUGGESTION_BAR, DECK_MENU, DECK_BRIDGE | `Downloader.kt` DL_HTTP, DL_BLOB, DL_MEDIASTORE | `Names.kt` NAMES_CONFIG | `ConnectionGuard.kt` GUARD_BIND, GUARD_LOOP | `WordStore.kt` WORDSTORE_SUGGEST, WORDSTORE_LEARN | `Discovery.kt` DISCOVERY_SCAN

## Use (Wi-Fi mode)
- Open PhoneDeck, tap **Paste & Connect** (copy the `http://192.168.x.x:8080/?key=...` link first).
- The link is remembered; with "Connect automatically" on, opening the app goes straight in.
- You can also **Share** the link text from any app to PhoneDeck.
- Press **Back twice** to disconnect and return to the link screen.
- Needs Android 10 or newer.
