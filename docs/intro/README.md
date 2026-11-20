# Signature Intro (Settings → Experimental)

`android/app/src/main/res/raw/signature_intro.mp4` (1920×1080, H.264 + silent stereo AAC, 8.4 s, ~0.9 MB)
plays on the TV before each movie when the switch is on. The phone queues it ahead of the movie
(one Cast queue) and keeps the headphones silent until the film itself is on screen.

How it was made (so it can be re-made if the brand changes):

1. `rec.js` records the loading animation of quantari-solutions.vercel.app at 1080p with Playwright.
2. `card.html` (+ `card_wordmark.png`, the site's wordmark) is the TheatreSync title card;
   `reccard.js` records it.
3. ffmpeg: crystal segment from 1.8 s (crop 780×595 at 570,195 to leave out the loading bar),
   scaled to 900 px high on #050507, fade in/out; then the title card; silent AAC; `+faststart`.
