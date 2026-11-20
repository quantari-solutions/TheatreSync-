# Signature Intro (Settings → Experimental)

`android/app/src/main/res/raw/signature_intro.mp4` (1920×1080, H.264 + silent stereo AAC, 4.3 s, ~150 KB):
the Quantari Solutions wordmark from quantari-solutions.vercel.app with the site's gold sheen, on the app's
dark theme with a soft gold glow. It plays on the TV before each movie when the switch is on; the phone
queues it ahead of the movie and keeps the headphones silent until the film itself is on screen.

Re-make: `node reclogo.js` (records `logo.html` with Playwright, needs `card_wordmark.png` next to it), then
`ffmpeg -ss 0.1 -t 4.3 -i <recording>.webm -f lavfi -t 4.3 -i anullsrc=channel_layout=stereo:sample_rate=48000
-vf fps=30,format=yuv420p -c:v libx264 -profile:v high -crf 20 -c:a aac -b:a 64k -movflags +faststart -shortest signature_intro.mp4`.
