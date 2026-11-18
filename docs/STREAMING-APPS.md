# YouTube, Netflix, Chrome tab casting and other streaming

> **Decision (2026-11-18):** TheatreSync handles **your own movie files** (several headphones,
> near-perfect sync). YouTube and Chrome tab casting stay as below for now. A "YouTube mode"
> that follows the TV via YouTube's unofficial TV-remote link (as used by
> [iSponsorBlockTV](https://github.com/dmunozv04/iSponsorBlockTV)) could be built later. It can
> support several headphones, but sync would be *close*, not perfect.

**Chrome "Cast tab" from a laptop** works the same way: Chrome sends picture *and* sound to
the Chromecast, so the sound has to be taken from the AV receiver (options below).

**No app can do this part.** Streaming apps block other apps from recording their sound,
and when you cast them, the Chromecast fetches the movie straight from the internet. So
the sound never passes through your phone where TheatreSync could pick it up.

The sound *does* reach your **AV receiver** over HDMI. From there it can be sent to
Bluetooth headphones with hardware:

| Option | Two headphones? | Lip sync | Notes |
|---|---|---|---|
| **A. Receiver's own Bluetooth transmit** | Usually one | Depends on receiver | Some receivers can send to Bluetooth headphones. Needs your receiver model to check. |
| **B. Bluetooth transmitter plugged into the receiver** (optical or headphone output) | Yes, if it says "dual link" / "2 headphones" | Sound arrives ~0.2 s late with the WH-1000XM4, which has no low-latency mode; most receivers can't delay the picture to compensate | ~£30–60, no app needed |
| **C. Upgrade to a Google TV Streamer** | No, one at a time ([Google](https://support.google.com/chromecast/answer/14632555)) | Good: Google TV corrects Bluetooth delay for its apps | Pair the headphones in Google TV settings |

**What I need from you:** the exact **AV receiver** and **Chromecast** model numbers
(on a label on the back or underneath). Then I can tell you which option works with
your equipment.

For **movies you own as files**, TheatreSync does all of this itself. See [MOVIE-NIGHT.md](MOVIE-NIGHT.md).
