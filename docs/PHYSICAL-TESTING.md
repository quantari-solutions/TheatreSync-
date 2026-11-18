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
