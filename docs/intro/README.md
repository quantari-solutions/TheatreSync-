# Signature Intro and opening

**Phone (opening when the app starts fresh):** drawn by the app itself in
[`OpeningAnimation.kt`](../../android/app/src/main/kotlin/com/theatresync/app/OpeningAnimation.kt), not a video,
so text is rendered at the phone's own resolution and laid out for a tall screen. The logo is
`res/drawable-nodpi/quantari_wordmark.webp`: `card_wordmark.png` rebuilt at 2× (1904×930) by `upscale_wordmark.py` (letters re-drawn crisp, swooshes enlarged smoothly; run it, then downsample the 3× result to 1904×930), so the logo stays sharp on any phone or tablet. Screenshot tests: `opening1`, `opening_largeFont`, `opening_tablet`.

**TV (intro and screensaver):** `android/app/src/main/res/raw/signature_card_live.webp`, an **animated picture that
loops forever** (1280×720, 15 fps, 16 s, ~3 MB), rendered from `loop.html`: the words stay still while the glow
breathes and drifts, a shine sweeps the Quantari swooshes, a light runs along the line and particles wander. Every
motion repeats exactly every 16 s, so the loop has no visible join. The Chromecast shows pictures full-screen with no
player bar, "LIVE" or time. Its first frame is the full card, so a TV that doesn't animate pictures shows the still card.

`loop.html` without `?render` runs live with a real clock: it's ready to be the page of our own Cast receiver (needs a
one-time Google Cast developer registration), which would make the animation independent of how the TV treats pictures.

History: 0.5.8 video (progress bar and time), 0.5.9 animated WebP starting on black (nothing visible), 0.6.0 live HLS
(nothing), 0.6.1–0.6.5 MP4 marked live and HLS screensaver (bar with "LIVE", fades, replays), 0.6.6–0.6.8 still PNG.

Re-make: render 240 frames of `loop.html?render` (Playwright: `window.setTime(i / 15)` then a 1920×1080 screenshot),
scale to 1280×720 and save with Pillow: `save_all=True, duration=67, loop=0, quality=70, kmin=1, kmax=1, allow_mixed=False`.

Re-make from `card.html` (needs `card_wordmark_soft.png` next to it):

```
node render.js frames card.html 6600      # every frame as a lossless PNG at exact times (Playwright)
ffmpeg -framerate 30 -i frames/%04d.png -f lavfi -i color=black:s=1920x1080:r=30:d=2 -filter_complex "[0:v]format=yuv420p,setsar=1[a];[1:v]format=yuv420p,setsar=1[b];[a][b]concat=n=2:v=1:a=0[v]" -map "[v]" -an -c:v libx264 -profile:v high -level 4.1 -preset slower -crf 18 -tune animation -movflags +faststart signature_intro.mp4
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
