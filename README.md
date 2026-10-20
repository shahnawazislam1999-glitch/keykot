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
**Word suggestions** - a thin strip appears inside the page, directly above the keyboard's top key row (Esc / F1..F12), only while there is something to suggest. No extra bar at the top of the screen.
- Tap a suggestion: the rest of the word + a space is typed, so your half-typed word is replaced by the whole word. Long-press to forget a word.
- New words you finish typing are saved in the app's local storage and suggested next time; word pairs are saved too, so after a space it suggests the next word.
- Works for the page's on-screen keys, a text box (Android keyboard) and a physical keyboard. Password boxes are ignored.
- **All PhoneDeck settings are in the page's own Settings (gear) panel**, in a "PhoneDeck app" section: suggestions on/off, next-word, Shift letters, pretty tabs, hide touchpad controls, clear learned words, reload, find PC, test keys, disconnect. If the panel is not recognised, the same list opens as an app dialog.

**Shift** - letters on the page's keyboard show small normally and CAPITAL after Shift (symbols too: 1 becomes !). Tap Shift = next key only, double-tap Shift = Caps Lock, hold Shift while typing = capitals until you let go. The "Shift changes the key letters" switch in Settings turns it off.

**Downloads** - done by the app itself into Downloads, always with the real file name (zip, apk, folders become Folder.zip). A bar shows progress, Open and cancel. Works for normal links, `<a download>` links and blob: downloads made by the page. If the name already exists, Android adds (1).

**Strong connection**
- Binds to the network the PC is really on (a Wi-Fi without internet is no longer dropped for mobile data) and holds a low-latency Wi-Fi lock.
- Checks the PC every few seconds: dot shows delay (green/orange/red), banner appears when the PC is lost, the page reloads itself when the PC is back.
- If the first load fails it retries by itself with growing waits. Reloads when you return after 45 s away. Recovers if the page process crashes.
- **Find PC** (connect screen or error screen / gear menu) scans the network when the PC's IP changed and rewrites the link. **Recent** keeps the last 8 links. **Test link** says exactly why it cannot connect.
- Gear > **Test key detection** shows which keys the helper script found on the PC page.

**Tabs & touchpad controls (v2.1)**
- The PC page's tab bar (Keys, Numpad, Text, Touchpad, Files, Clipboard) becomes pill-shaped and slides sideways when it does not fit; the selected tab scrolls to the centre. The "Pretty scrolling tabs" switch in Settings turns it off.
- Settings > "Touchpad: hide Speed/Scroll sliders and Left/Right buttons" hides those controls, and the touchpad grows to fill the freed space.
- Settings > "Touchpad: hide L / M / R buttons" hides the L / M / R mouse-button row in link mode.
- Bluetooth mode: gear > "Show L / M / R buttons under the touchpad" hides or shows that row.

## Code map (search for `ANCHOR:`)
`assets/pd.js` JS_UI_TABS, JS_SHIFT_SYNC, JS_SUGBAR, JS_SETTINGS, JS_SUGGEST, JS_INSERT, JS_DOWNLOADS, JS_PROBE | `DeckActivity.kt` DECK_CONNECTION, DECK_DOWNLOADS, DECK_SUGGESTIONS, DECK_MENU, DECK_BRIDGE | `Downloader.kt` DL_HTTP, DL_BLOB, DL_MEDIASTORE | `Names.kt` NAMES_CONFIG | `ConnectionGuard.kt` GUARD_BIND, GUARD_LOOP | `WordStore.kt` WORDSTORE_SUGGEST, WORDSTORE_LEARN | `Discovery.kt` DISCOVERY_SCAN

## Use (Wi-Fi mode)
- Open PhoneDeck, tap **Paste & Connect** (copy the `http://192.168.x.x:8080/?key=...` link first).
- The link is remembered; with "Connect automatically" on, opening the app goes straight in.
- You can also **Share** the link text from any app to PhoneDeck.
- Press **Back twice** to disconnect and return to the link screen.
- Needs Android 10 or newer.

## v2.3 additions
- **Type layout** (✍): alphabet-only keyboard for two-hand typing. Marker above the **F** and **J** keys (and a longer buzz on them), `?123` page with symbols + arrows, 🌐 = Win+Space (language), Shift = tap (next key) / double-tap (Caps) / hold. **Adjust** lets you drag each block and pull any edge or corner to resize; **Split** gives a left-hand and a right-hand block you can place independently; **Reset** restores the default. Link mode: tab "✍ Type" in `PC/phone.html`. Bluetooth mode: ✍ button in the top bar.
- **Xiaomi Smart Pen mode** (✒): pen on the touchpad = left button held, so it draws/drags. 1:1 movement, all pen samples, One Euro smoothing (Pen smoothing slider), cursor follows the hovering pen, palm rejection while the pen is near, barrel button = right button. Tip: in Windows turn off *Enhance pointer precision* (Mouse settings) for exact 1:1 strokes.
- **Gestures**: 3-finger tap = Win+Tab, 4-finger tap = Win+S (search). Can be turned off in Settings.
- `PC/phone.html` is the updated page for the PC side (replace your phone.html with it).

## Floating keyboard + mouse (Type tab) over other apps
Gear > **🪟 Floating keyboard + mouse** > switch on. First time Android opens **Display over other apps**: allow PhoneDeck, come back, switch on again.
The app goes to the background and the Type keyboard + mouse pad float above everything (also when PhoneDeck is minimised or closed from recents).
- Window bar: drag **⠿** to move, **◢** corner to resize, **◐ slider** = opacity (20-100 %), **Adjust / Split / Reset** = same as the Type tab (move and resize every block), **▁** fold to the bar only, **✕** close. The notification has Stop / Open.
- **Keep always active** button (Settings) = ignore battery optimisation, so Android does not stop the service. A foreground service, a CPU wake lock and the network binding keep the link alive with the screen dark.
- The floating window keeps its own layout (separate from the Type tab). Needs a rebuilt APK (new permissions + FloatService).

## v2.4: switching between hotspots / Wi-Fi networks
- **Automatic Find PC.** When the PC cannot be reached for ~4 s (about 1 s after Android reports a different network), the app searches the networks the device is on now and reconnects by itself. The banner says "PC moved to <ip> - reconnected". Nothing to tap.
- **Real PhoneDeck check.** Only a device that answers like PhoneDeck counts (not any device with port 8080 open). The secret key is sent only to a device that already proved to be PhoneDeck. If the PC is found but the saved key is old, the banner says so.
- **Never mobile data.** Every search packet is bound to the Wi-Fi / USB-tether / Ethernet network itself, and while the PC address is unknown the app still prefers the local Wi-Fi over mobile data (a hotspot without internet used to lose against mobile data).
- **Smarter order.** The old PC address and its neighbours are tried first, then the rest of the subnet.
- **Floating keyboard follows.** It updates its link and reconnects too (it also searches by itself when the app is in the background).
- Manual **Find PC** (menu / connect screen) uses the same checks and marks results whose key is old.
- Code: `Discovery.kt` (DISCOVERY_SCAN, `AutoFinder`), `ConnectionGuard.kt` (GUARD_BIND, `onNetworkChanged`), `DeckActivity.kt` (`switchTo`), `FloatService.kt` (`relinkTo`).

## v2.5: PC mouse + keyboard on the tablet (reverse direction)
Push the PC's mouse off the right edge of the PC screen and the pointer appears on the tablet's left edge. Everything on the tablet can then be used with the PC mouse and keyboard; push left off the tablet's left edge to come back.
- **Setup (tablet):** open the app > **PC mouse on this tablet** > turn on the switch > **Open Accessibility settings** > switch on **PhoneDeck PC mouse**. (Android 13+: if the switch is greyed out use App info > ⋮ > *Allow restricted settings* first.) Connect once with **Paste & Connect** so the PC link is saved; the feature then reconnects by itself, even when the app is closed.
- **Setup (PC):** replace `server.py`, add `tabletlink.py` (both in the PhoneDeck folder), run `stop-phonedeck.bat` then `start-lan.bat`.
- **Mouse:** left = tap / hold (long-press) / drag, wheel = scroll, right = Back, middle = Home, side buttons = Back / Recents. Speed and which PC edge to use are in the settings screen.
- **Keyboard:** text, Backspace, Delete, arrows, Home/End, Enter, Esc (= Back), Ctrl+A/C/X/V.
- **Clipboard:** copy on the PC, cross over (the text is copied to the tablet), Ctrl+V in any text field. Ctrl+C on the tablet sends the selection to the PC clipboard; the tablet clipboard is also sent when you cross back.
- **Panic key:** SCROLL LOCK on the PC always returns the mouse and keyboard to the PC.
- Code: `RemoteInputService.kt` (pointer overlay, gestures, typing, clipboard), `TabletLink.kt` (connection to the PC), `ClipReadActivity.kt`, `MouseShareActivity.kt`; PC side `PhoneDeck/tabletlink.py`.
