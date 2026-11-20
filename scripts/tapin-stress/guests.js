// Host controls and join/leave for TapIn guests, against server.py.
const { chromium } = require('playwright');
const B = 'http://127.0.0.1:8766';
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const ctx = await b.newContext();
  const host = await ctx.newPage(); await host.goto(B + '/truth');
  const api = (u) => host.evaluate(x => fetch(x).then(r => r.json().catch(() => null)), B + u);
  const guests = async () => Object.values(await api('/guests'));
  async function waitFor(what, fn, ms) { const t = Date.now(); while (Date.now() - t < ms) { if (await fn()) return Date.now() - t; await host.waitForTimeout(50); } return -1; }
  let ok = true;
  const check = (name, ms, limit) => { const pass = ms >= 0 && ms <= limit; ok &= pass; console.log(`${pass ? 'PASS' : 'FAIL'} ${name.padEnd(44)} ${ms < 0 ? 'never' : ms + ' ms'}`); };

  const p = await ctx.newPage(); p.on('pageerror', e => console.log('PAGE ERROR', e.message));
  await p.goto(B + '/listen'); await p.fill('#name', 'Priya'); await p.fill('#dev', 'AirPods Pro'); await p.waitForTimeout(1000);
  await waitFor('previous test pages expire', async () => (await guests()).length === 0, 6000);
  check('not listed before tapping Start', (await guests()).length === 0 ? 0 : -1, 0);
  await p.click('#start');
  check('appears on host after Start', await waitFor('join', async () => (await guests()).some(g => g.name === 'Priya'), 3000), 1200);
  check('sound playing', await waitFor('play', () => p.evaluate(() => !document.getElementById('a').paused), 5000), 3000);
  await p.waitForTimeout(6000);
  const g0 = (await guests())[0];
  check(`host sees sync (${g0.drift} ms)`, Math.abs(+g0.drift) <= 80 ? 0 : -1, 0);
  const own = await p.textContent('#sync'); check(`page shows own sync + headphones ("${own}")`, /Sync: [+-]\d+ ms on AirPods Pro/.test(own) ? 0 : -1, 0);
  check(`host sees their headphones (${g0.dev})`, g0.dev === 'AirPods Pro' ? 0 : -1, 0);
  await api('/ctl?cmd=mute&on=1'); check('host turns sound off → muted', await waitFor('mute', () => p.evaluate(() => document.getElementById('a').muted), 3000), 1500);
  await api('/ctl?cmd=mute&on=0'); check('host turns sound on → unmuted', await waitFor('unmute', () => p.evaluate(() => !document.getElementById('a').muted), 3000), 1500);
  await api('/ctl?cmd=volume&v=40'); check('host volume 40% → page volume', await waitFor('vol', () => p.evaluate(() => Math.abs(document.getElementById('a').volume - 0.4) < 0.01), 3000), 1500);
  await p.evaluate(() => navigator.mediaDevices && navigator.mediaDevices.dispatchEvent(new Event('devicechange')));
  await p.waitForTimeout(4000);
  const d1 = await p.evaluate(() => window.tsStats.drift); check(`headphones switched → still in step (${d1} ms)`, Math.abs(d1) <= 80 ? 0 : -1, 0);
  await p.click('#start');
  check('Stop → gone from host', await waitFor('stop', async () => (await guests()).length === 0, 3000), 300);
  check('Stop → sound stops', await p.evaluate(() => document.getElementById('a').paused) ? 0 : -1, 0);
  await p.click('#start');
  check('Start again → back on host', await waitFor('rejoin', async () => (await guests()).length === 1, 3000), 1200);
  check('Start again → sound', await waitFor('play2', () => p.evaluate(() => !document.getElementById('a').paused), 5000), 3000);
  await p.close({ runBeforeUnload: true });
  check('page closed → gone from host', await waitFor('close', async () => (await guests()).length === 0, 3000), 500);

  const q = await ctx.newPage(); await q.goto(B + '/listen'); await q.waitForTimeout(800); await q.click('#start');
  await waitFor('join2', async () => (await guests()).length === 1, 3000); await q.waitForTimeout(3000);
  await api('/ctl?cmd=remove');
  check('host removes → guest sound stops', await waitFor('removed', () => q.evaluate(() => document.getElementById('a').paused && /stopped the sound/.test(document.getElementById('status').textContent)), 3000), 1500);
  await q.waitForTimeout(1500);
  check('removed guest stays off the list', (await guests()).length === 0 ? 0 : -1, 0);
  await q.screenshot({ path: __dirname + '/build/removed.png' });
  console.log(ok ? 'ALL PASS' : 'SOME FAILED');
  await b.close();
})();
