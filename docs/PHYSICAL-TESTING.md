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

Later tests (not yet runnable): AudioPlaybackCapture from a video app, AudioTrack
delayed output, Bluetooth latency measurement, interruptions (calls/notifications),
Chromecast discovery and sessions, projector/AV-receiver compatibility.
