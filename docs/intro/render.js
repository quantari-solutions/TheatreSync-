// Renders card.html frame by frame (lossless PNGs, exact timing) for a sharp TV intro.
// Usage: node render.js <outDir> [page.html] [ms]   then encode with ffmpeg (see README.md).
const { chromium } = require('playwright');
const path = require('path'), fs = require('fs');
(async () => {
  const out = process.argv[2] || 'frames'; fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await p.goto('file://' + path.join(__dirname, process.argv[3] || 'card.html'));
  await p.evaluate(() => Promise.all([document.fonts.ready, ...[...document.images].map(i => i.decode())]));
  await p.evaluate(() => document.getAnimations().forEach(a => a.pause()));
  const FPS = 30, MS = +(process.argv[4] || 6600);
  for (let i = 0; i < Math.round(MS * FPS / 1000); i++) {
    await p.evaluate(t => document.getAnimations().forEach(a => { a.currentTime = t; }), i * 1000 / FPS);
    await p.screenshot({ path: path.join(out, String(i).padStart(4, '0') + '.png') });
  }
  await b.close();
})();
