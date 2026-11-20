const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1920, height: 1080 }, recordVideo: { dir: "vid", size: { width: 1920, height: 1080 } } });
  const p = await ctx.newPage();
  await p.goto('https://quantari-solutions.vercel.app/', { waitUntil: 'commit' });
  await p.waitForTimeout(7000);
  await ctx.close(); await b.close();
})();
