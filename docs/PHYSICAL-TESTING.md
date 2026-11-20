# Physical device tests

Cloud builds prove code compiles and logic is correct. They **do not** prove
Bluetooth, audio capture, Cast or latency behaviour. These tests do.

How to report: reply in Claude Code with the test ID, *Pass/Fail*, and a screenshot.

---

## HW-01 — Android diagnostics & Bluetooth visibility (Motorola + WH-1000XM4)

**Objective:** confirm the APK installs and that Android exposes the XM4 to the app as a Bluetooth A2DP output.

**You need:** the Motorola phone, the WH-1000XM4, the APK from the PR (see [CLOUD-WORKFLOW.md](CLOUD-WORKFLOW.md)).

**Steps**
1. Install `TheatreSync-debug.apk` and open **TheatreSync**.
2. Tap **Grant permissions** and allow both prompts.
3. Turn on the XM4 and make sure it's connected to the phone (Bluetooth settings).
4. Return to TheatreSync and tap **Refresh**.
5. Screenshot the whole screen (scroll if needed).
6. Turn the XM4 off; wait 5 s; screenshot again.

**Expected**
* Top lines show your phone model and Android version.
* With XM4 on: an output named like **WH-1000XM4** with `route=BLUETOOTH_A2DP`.
* With XM4 off: that entry disappears (the list updates on its own).

**Interpreting results**
* XM4 missing while connected → device-callback or permission problem; Claude fixes in cloud.
* Name shows "(grant Bluetooth…)" → permission denied; re-tap Grant.
* App won't install → report the exact message.

---

## HW-02 — iPad Swift Playgrounds build & audio route

**Objective:** confirm the iOS package compiles in Swift Playgrounds and reports the audio route.

**Steps**
1. Import the package ([IOS-SWIFT-PLAYGROUNDS.md](IOS-SWIFT-PLAYGROUNDS.md)) and tap ▶ Run.
2. Screenshot the TheatreSync screen with the iPad speaker as output.
3. Connect the XM4 to the iPad, tap **Refresh**, screenshot again.

**Expected**
* It builds without errors. (If not, screenshot or copy the error list.)
* `Protocol self-test: pass`.
* Speaker: `route=speaker`. XM4: `type=BluetoothA2DPOutput route=bluetooth_a2dp`, with a higher output latency.

---

## HW-03 — Movie night, one person (Android + Chromecast + WH-1000XM4)

**Objective:** picture on the projector, sound in the headphones, in sync.

**You need:** an MP4 movie file on the phone (any home video recorded on the phone works too), the projector setup switched on.

**Steps**
1. Install the newest APK from the PR, connect the XM4 to the phone, open TheatreSync.
2. Choose movie → choose your Chromecast → **Start movie**.
3. Watch for one minute. Use **Sound is early/late** until voices match lips.
4. Pause, then play. Skip forward 10 s.
5. Lock the phone for 2 minutes, then unlock.

**Expected**
* Picture on the screen within ~10 s; room speakers silent; sound in the headphones.
* After adjusting, lips and voices match and **stay** matched (including after pause/skip/lock).

**Report:** Pass/Fail per step, the final **Sound delay** value, the **Sync: … ms** number at the bottom, and a screenshot.

---

## HW-04 — Friend joins on iPhone (or you on the iPad)

**Objective:** a second pair of headphones in sync with the same movie.

**Steps**
1. Do HW-03 steps 1–2.
2. On the iPhone: install TheatreSync with Swift Playgrounds ([guide](IOS-SWIFT-PLAYGROUNDS.md)), connect its headphones, open it.
3. Allow **Local Network** when asked. Tap the movie under **Movies on your Wi-Fi**.
4. Both people compare: does the sound match the picture, and each other?

**Expected:** the Android phone shows **Friends listening: 1**; both hear the movie in sync with the screen.

**Report:** Pass/Fail, any error text, the iPhone's **Sync: … ms** and **Sound delay** values.

---

## HW-05 — "Now casting" card (YouTube from a phone, tab from a laptop)

**Objective:** TheatreSync shows what's on the TV, with a thumbnail, whoever started it.

**Steps**
1. On any phone, cast a YouTube video to the Chromecast with the YouTube app.
2. Open TheatreSync on the Android phone. Within a few seconds a **Now casting** card should appear at the top, showing the thumbnail and the title.
3. Tap the pause button on the card. The TV should pause. Tap it again to resume.
4. Tap the card. The **Now casting** page opens. Try −10s / +10s, then tap ← (or swipe back).
5. On the laptop, in Chrome: ⋮ → **Cast…** → choose the Chromecast → **Cast tab**. The card should change to **Screen mirroring**.

**Expected:** the card appears and disappears on its own; the thumbnail and title match the video; pause/skip control the TV.

**Report:** Pass/Fail per step and a screenshot of the card.

---

## HW-06 — YouTube from TheatreSync

1. Open TheatreSync → **YouTube**.
2. In the YouTube app, open a video → **Share** → **Copy link**. Back in TheatreSync tap **Paste**, then **Cast to [your TV]**.
3. Also try: YouTube app → **Share** → **Cast with TheatreSync**.

**Expected:** the video starts on the projector within ~10 s; the **Now casting** card shows its thumbnail; **Stop casting** returns the TV to its idle screen.
**If it says YouTube didn't accept it:** tap **Open this video in YouTube** and use YouTube's cast icon. Tell Claude, because this means YouTube changed its remote-control service.

## HW-07 — Screen mirroring

1. TheatreSync → **Mirror screen** → **Start screen mirroring**.
2. Pick your Chromecast in the list Android shows → **Start now**.

**Expected:** your phone's screen appears on the projector. **Report** which screen opened (Android Cast settings or the Google Home app).

## HW-08 — Crash regression (the 0.3.1 crash)

1. Connect to your Chromecast in TheatreSync.
2. **Choose movie** → pick an MP4 in Files → **Start movie**. Let it play for 2 minutes.
3. Lock the phone for 1 minute, unlock, and press **Stop movie**.

**Expected:** no crash. If TheatreSync ever closes, reopen it: a **Share crash report** card appears. Share it to Claude.

## HW-09 — Picture reaches the projector (0.4.1)

1. Wi-Fi on, connected to the same network as the Chromecast.
2. Choose movie → connect Media Room TV → **Start movie**. Wait 30 seconds.

**Expected:** the picture appears within ~10 s. **If not:** after 20 s TheatreSync shows a message. Open **Headphones → ⓘ** and send a screenshot of "Last movie sent to TV": it shows the address given to the TV and whether the TV ever asked for the movie.

## HW-10 — Movie quality, mute and TV volume (0.4.6)

1. Choose the same movie that showed no picture. Under it, note the line (e.g. "4K · H.265 …") and the coloured message.
2. Pick **1080p** (or leave the suggested one) → **Start movie**. A "Preparing for your TV… %" bar appears; wait for it.
3. Turn on **Mute room speakers**. A line under it says whether the TV accepted it.
4. Start a YouTube video, open **Now casting**, move the **TV volume** slider and press **Mute**.

**Expected:** the picture appears on the projector; the room speakers go quiet (or the app tells you they couldn't);
the YouTube volume changes. Send one screenshot of step 1 and one of step 3, and say roughly how long step 2 took.

## HW-11 — Headphone sound, fast conversion, remote (0.4.7)

1. Connect the MOMENTUM 4. Choose Red Eye → it should say "5.1 sound" and offer to fix the sound. Tap **Start movie**,
   switch to another app while it prepares if you like. Note how long "Fixing the sound" takes.
2. When the picture appears, you should hear the movie in the headphones within ~2 seconds.
3. On the Headphones page, switch between Phone speaker and MOMENTUM 4: sound should come back within ~2 seconds each time.
4. Drag the progress bar to another point: picture and headphone sound should both jump there.
5. YouTube: try Next, Previous and Captions; then ☰ → Chromecast remote → volume − / +.

**Send:** how long step 1 took, whether step 2 and 3 worked (and how many tries), and a screenshot of anything that didn't.

## HW-12 — The Frame, remote, headphone settings (0.4.8)

1. YouTube → the list should include **Samsung The Frame** ("Smart TV · YouTube only"). Cast a video to it.
2. ☰ → Chromecast remote while YouTube plays on the Media Room TV: try −10 s / +30 s and pause/play.
3. Start a movie, press ← : you should see the home screen with a "Playing on …" card; tap it to return.
4. In the movie player, scroll down → **Headphone options** → pick **Bass** and **Voices**: the headphone sound should change.

**Send:** whether each step worked, and a screenshot of anything that didn't.

## HW-13 — Instant surround movies, new headphones, notification remote (0.4.9)

1. Choose Red Eye (leave "Room speakers too" off) → Start: the picture should appear within a few seconds, sound in the headphones.
2. During the movie, connect another pair of headphones: TheatreSync asks "Use …?" → Use: sound moves to them within ~2 s.
3. Pull down the notification shade while casting: tap **Remote** → the app opens on the remote.

**Send:** whether each worked, and a screenshot of anything that didn't.

## HW-14 — TapIn guests, picture in picture (0.5.0)

1. Start a movie. On the movie page, scroll to **TapIn**. A friend connects their headphones to *their* phone (iPhone or Android, no app), unlocks it and holds it to the back of yours. A link opens: tap it, then **Start listening**. (If nothing happens, they scan the QR code with the camera.)
2. Check the friend hears the movie in step with the TV; use "Sound is early/late" on their page if needed.
3. Tap ⧉ (top right of the movie page) or **Pop out** in the notification: a small window shows what's playing; its play/pause works.

**Send:** which phones the tap worked on, how in-step it sounded, and a screenshot of anything that didn't work.
4. Settings → Experimental → turn on **Signature Intro**, start a movie: the Quantari logo shows on the TV, then the
   movie; headphone sound should start with the film, not during the logo.
