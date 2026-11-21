# Signature Intro and opening

**Phone (opening when the app starts fresh):** drawn by the app itself in
[`OpeningAnimation.kt`](../../android/app/src/main/kotlin/com/theatresync/app/OpeningAnimation.kt), not a video,
so text is rendered at the phone's own resolution and laid out for a tall screen. The logo is
`res/drawable-nodpi/quantari_wordmark.webp` (lossless copy of `card_wordmark.png`). Screenshot test: `opening1`.

**TV (Settings → Experimental → Signature intro):** `android/app/src/main/res/raw/signature_intro.mp4`
(1920×1080 H.264 High@4.1, 30 fps, 4.3 s, silent stereo AAC, ~0.5 MB). It plays before each movie, YouTube
and mirroring; the headphones stay silent until the film itself is on screen.

Re-make the TV clip from `card.html` (needs `card_wordmark.png` next to it):

```
node render.js frames      # every frame as a lossless PNG at exact times (Playwright)
ffmpeg -framerate 30 -i frames/%04d.png -f lavfi -t 4.3 -i anullsrc=channel_layout=stereo:sample_rate=48000 \
  -vf "fade=t=in:st=0:d=0.3,format=yuv420p" -c:v libx264 -profile:v high -level 4.1 -preset slower -crf 12 \
  -tune animation -x264-params aq-mode=3 -c:a aac -b:a 64k -movflags +faststart -shortest signature_intro.mp4
```

(Earlier versions recorded the page as a video first, which lost detail before encoding.)
