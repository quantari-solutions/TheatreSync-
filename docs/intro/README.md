# Signature Intro and opening

**Phone (opening when the app starts fresh):** drawn by the app itself in
[`OpeningAnimation.kt`](../../android/app/src/main/kotlin/com/theatresync/app/OpeningAnimation.kt), not a video,
so text is rendered at the phone's own resolution and laid out for a tall screen. The logo is
`res/drawable-nodpi/quantari_wordmark.webp` (lossless copy of `card_wordmark.png`). Screenshot test: `opening1`.

**TV (Settings → Experimental → Signature intro):** `android/app/src/main/res/raw/signature_intro_pic.webp`, an
**animated picture** (1600×900, 30 fps, 6.6 s, plays once and ends on black, ~3 MB). The Chromecast shows pictures
full-screen with no player controls, progress bar or time, so the intro looks like a title card rather than a video
(the owner saw "0:04" and a progress bar with the earlier video version). The phone waits 6.6 s, then starts the
movie / YouTube / mirroring.

Re-make from `card.html` (needs `card_wordmark_soft.png` next to it):

```
node render.js frames card.html 6600      # every frame as a lossless PNG at exact times (Playwright)
python3 -c "from PIL import Image;import glob;f=[Image.open(x).convert('RGB').resize((1600,900),Image.LANCZOS) for x in sorted(glob.glob('frames/*.png'))];f[0].save('signature_intro_pic.webp',save_all=True,append_images=f[1:],duration=33,loop=1,quality=88,method=4,kmin=1,kmax=1,minimize_size=False,allow_mixed=False)"
```

Every frame is stored whole (kmin=kmax=1): ffmpeg's animated WebP encoder stores only changed rectangles,
which left visible blocks around the glow.

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
