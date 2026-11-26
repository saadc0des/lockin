# Lockin for Android

Pomodoro/timer that locks distracting **apps** and **websites** until the time is up.
Native Kotlin, no libraries. Tested target: Samsung Galaxy A51 (Android 10-13).

## Build the APK (pick one)

**A) GitHub Actions (no Android Studio needed)**
1. Create a new GitHub repo, upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. When it is green, open the run and download the artifact `Lockin-debug-apk`.
4. Unzip it and send `app-debug.apk` to your phone.

**B) Android Studio (Zorin)**
1. Open this folder in Android Studio, wait for Gradle sync.
2. Build > Build APK(s). The file is `app/build/outputs/apk/debug/app-debug.apk`.
   Or plug in the A51 (USB debugging on) and press Run.

## First-time setup on the A51
1. Install the APK (allow "install unknown apps" for the app you opened it with).
2. Open Lockin and allow notifications.
3. Settings > Apps > Lockin > three dots (top right) > **Allow restricted settings**.
   (Android 13 greys out the accessibility switch for sideloaded apps until you do this.)
4. In Lockin tap **Open accessibility settings** > Installed apps > **Lockin** > turn on.
5. Settings > Apps > Lockin > Battery > **Unrestricted**.
   Device care > Battery > Background usage limits: make sure Lockin is not in "Sleeping apps".
   Without this, One UI can kill the timer notification.
6. Optional: open Recents, tap the Lockin icon, choose **Keep open**.

## How the lock works
- Blocked app comes to the front during a focus window: you are sent Home.
- Browser URL bar shows a blocked site: you are sent Home. Reads Chrome, Samsung Internet,
  Firefox, Brave, Edge, Opera, Kiwi, Vivaldi.
- Settings and uninstall screens that mention "Lockin" are blocked during focus, so you can't switch off the
  service, force-stop, or uninstall mid-session.
- Pomodoro: lock is on in focus, off in breaks. Long break (15 min) after every 4th round. Rounds = 1 is a plain timer.
- Max session 4 hours. Closing the app or rebooting does not unlock.
- **Emergency exit**: tap it, wait 15 minutes (still locked), then confirm. You can cancel during the wait.
  Change `EXIT_DELAY_MS` in `LockStore.kt` to make it longer or shorter.

## Sounds
Same four sounds as the desktop app: session start, break start, focus back after a break, session end.
Switch them off with **Notification sounds** on the main screen, or **Sounds: on/off** while a session runs.
They play on the notification volume, so silent mode silences them (the phone still vibrates on phase changes).

## Stats
Same idea as the desktop app, shown at the bottom of the main screen: **Today**, **Last 7 days** (with daily average),
**Day streak**, **All time** (with session count) and a 14-day bar chart (today in pink).
- Every finished focus block counts in full. If you use the emergency exit, the part of the block you did still counts.
- Blocks shorter than 30 seconds are ignored.
- Everything is stored on the phone only. Uninstalling the app deletes it.

## PC link (optional)
Lock the phone and the PC together, same Wi-Fi only, nothing goes online.
1. On the PC (updated Lockin desktop app): Focus > Settings > **Link with phone** on. It shows a pairing code and the PC address.
2. On the phone: **PC link** section, switch on, type the pairing code, **Save and reconnect**. The PC is found automatically
   (type its address only if your router blocks discovery). If the PC uses a firewall, allow port 47653 (TCP and UDP).
3. Starting a session: tick **Also lock my PC** (phone) or **Also lock my phone** (PC) before you start. The other device
   shows a notification/banner and only locks after you confirm there. Not ticked = nothing happens on the other device.
- The PC lock can't be released from the phone, and the phone's emergency exit only ends the phone.
- The link keeps a small silent notification. Samsung may pause it when the screen is off for a long time; it reconnects by itself.
- If the PC asks and the phone's accessibility service is off, or no apps/sites are chosen, the phone says no and tells the PC why.

## Honest limits
- Medium strictness by design: someone with ADB, Safe Mode or a factory reset can undo it. Changing the phone clock
  can also confuse it. It is a speed bump against impulse, not security.
- Websites are only caught in the browsers listed above, and only when the URL bar is visible. Incognito tabs work,
  but a page that is already open with the URL bar hidden is caught on the next navigation.
- Add more browsers in `browserUrlIds` in `LockAccessibilityService.kt` (find the URL bar id with Android Studio's Layout Inspector).
- Desktop features not ported yet: tasks, calendar, notes, allow-one-link, floating mini timer.
