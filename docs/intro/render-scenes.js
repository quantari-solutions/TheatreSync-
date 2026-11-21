// Renders screensaver scenes as lossless PNG frames: node render-scenes.js <outDir> <firstSeed> <count> [fps]
const { chromium } = require('playwright');
const path = require('path'), fs = require('fs');
(async () => {
  const [out, first, count, fpsArg] = process.argv.slice(2); const FPS = +(fpsArg || 25);
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  for (let s = +first; s < +first + +count; s++) {
    const dir = path.join(out, 'scene' + String(s).padStart(2, '0')); fs.mkdirSync(dir, { recursive: true });
    await p.goto('file://' + path.join(__dirname, 'screensaver.html') + '?seed=' + s);
    await p.evaluate(() => Promise.all([document.fonts.ready, ...[...document.images].map(i => i.decode())]));
    for (let i = 0; i < 10 * FPS; i++) {
      await p.evaluate(t => renderAt(t), i / FPS);
      await p.screenshot({ path: path.join(dir, String(i).padStart(4, '0') + '.png') });
    }
  }
  await b.close();
})();
