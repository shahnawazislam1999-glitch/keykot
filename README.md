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
- Limits: Android 9+, some brands block it, US keyboard keys only, no files / clipboard / preview in this mode.

## Use (Wi-Fi mode)
- Open PhoneDeck, tap **Paste & Connect** (copy the `http://192.168.x.x:8080/?key=...` link first).
- The link is remembered; with "Connect automatically" on, opening the app goes straight in.
- You can also **Share** the link text from any app to PhoneDeck.
- Press **Back twice** to disconnect and return to the link screen.
- Needs Android 10 or newer.
