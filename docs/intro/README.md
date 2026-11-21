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

## No borders, anywhere

The logo artwork's gold flames ran solid right up to the picture's edges, which showed as straight lines on a
dark screen. `card_wordmark_soft.png` (and the app's `quantari_wordmark.webp`) is the same artwork with the
flame tips dissolving to fully transparent over their last ~160 px (letters untouched; every edge pixel has
alpha 0). It sits on pure black (#000) on the phone and the TV, and every glow is a soft round gradient, so
the logo appears to float in the dark with nothing around it. No straight-line masks are used.

## TV screensaver (connected, nothing playing)

`screensaver.html?seed=N` is the Signature Intro as one 15 s scene with its own random timing, glow swells,
one or two light sweeps across the logo, a slight drift and 8–17 sparks. It rises out of pure black and
dissolves back into pure black, so scenes join invisibly. The app ships 12 scenes (`res/raw/ss01.ts` …
`ss12.ts`, 1080p25 H.264 in MPEG-TS) and streams them endlessly (`EndlessHls` in sync-core): a fresh shuffle
every round, never the same scene twice in a row, timestamps re-stamped so the TV sees one continuous programme.

Re-make: `node render-scenes.js frames 1 12 25 15`, then for each scene
`ffmpeg -framerate 25 -i frames/sceneNN/%04d.png -vf format=yuv420p -c:v libx264 -profile:v high -level 4.1 -preset slower -crf 20 -tune animation -x264-params aq-mode=3:keyint=375:min-keyint=375:scenecut=0 -bf 2 -an -f mpegts ssNN.ts`.
