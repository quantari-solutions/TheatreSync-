const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1920, height: 1080 }, recordVideo: { dir: 'vid2', size: { width: 1920, height: 1080 } } });
  const p = await ctx.newPage();
  await p.goto('file://' + process.cwd() + '/card.html');
  await p.waitForTimeout(4600);
  await ctx.close(); await b.close();
})();
