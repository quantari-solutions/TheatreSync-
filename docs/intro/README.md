# Signature Intro and opening

**Phone (opening when the app starts fresh):** drawn by the app itself in
[`OpeningAnimation.kt`](../../android/app/src/main/kotlin/com/theatresync/app/OpeningAnimation.kt), not a video,
so text is rendered at the phone's own resolution and laid out for a tall screen. The logo is
`res/drawable-nodpi/quantari_wordmark.webp` (lossless copy of `card_wordmark.png`). Screenshot test: `opening1`.

**TV (Settings → Experimental → Signature intro):** `android/app/src/main/res/raw/signature_intro.mp4`
(1920×1080 H.264 High@4.1, 30 fps, 6.6 s, silent stereo AAC, ~1.1 MB). It plays before each movie, YouTube
and mirroring; the headphones stay silent until the film itself is on screen.

Re-make the TV clip from `card.html` (needs `card_wordmark.png` next to it):

```
node render.js frames      # every frame as a lossless PNG at exact times (Playwright)
ffmpeg -framerate 30 -i frames/%04d.png -f lavfi -t 6.6 -i anullsrc=channel_layout=stereo:sample_rate=48000 \
  -vf "format=yuv420p" -c:v libx264 -profile:v high -level 4.1 -preset slower -crf 12 \
  -tune animation -x264-params aq-mode=3 -c:a aac -b:a 64k -movflags +faststart -shortest signature_intro.mp4
```

Timeline (same on phone and TV, slow on purpose so the glow leads): glow rises 0–1.8 s; logo 0.3–1.8 s;
gold sheen across the logo 1.4–3.3 s; "PRESENTED WITH" 2.3 s; title 2.7–3.9 s with its glow blooming
2.9–4.6 s; line 3.5–4.9 s; tagline 3.9 s; the whole glow swells once, peaking at 5.05 s; fade out 5.7–6.6 s.
Gentle sine-like easing throughout, no sudden starts or stops.

(Earlier versions recorded the page as a video first, which lost detail before encoding.)
