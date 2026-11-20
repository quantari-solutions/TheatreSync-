const { chromium } = require('playwright');
const B='http://127.0.0.1:8766';
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const p = await b.newPage();
  p.on('pageerror', e => console.log('PAGE ERROR', e.message));
  const ctl = (q) => p.evaluate(u => fetch(u).then(r=>r.text()), B+'/ctl?'+q);
  await p.goto(B+'/listen'); await p.waitForTimeout(1200);
  await p.click('#start');
  // True drift: what the page plays vs the simulated TV, measured from outside the page's own estimate.
  const sample = () => p.evaluate(async () => {
    const t0=performance.now(); const tr=await (await fetch('/truth',{cache:'no-store'})).json(); const t1=performance.now();
    const a=document.getElementById('a');
    return { d: tr.playing ? Math.round(a.currentTime*1000 - (window.tsStats.outLat||0) - (tr.pos + (t1-t0)/2)) : null, paused:a.paused, tvPlaying:tr.playing, st:document.getElementById('status').textContent, ...window.tsStats };
  });
  async function phase(name, secs, setup) {
    if (setup) await setup();
    const s0 = await p.evaluate(()=>({...window.tsStats}));
    const ds=[]; let last;
    for (let i=0;i<secs*2;i++){ await p.waitForTimeout(500); last=await sample(); if(last.d!==null) ds.push(last.d); }
    const tail=ds.slice(-6).map(Math.abs);
    const ok = name.includes('pause') ? last.paused : (tail.length>0 && Math.max(...tail)<=80);
    console.log(`${ok?'PASS':'FAIL'} ${name.padEnd(34)} seeks+${last.seeks-s0.seeks} reloads+${last.reloads-s0.reloads} last6=${JSON.stringify(ds.slice(-6))} status="${last.st}" lead=${last.lead} outLat=${last.outLat}`);
    return ok;
  }
  let all=true;
  all&=await phase('start + lock on', 10);
  all&=await phase('steady 20 s', 20);
  all&=await phase('TV pauses', 4, ()=>ctl('cmd=pause'));
  all&=await phase('TV resumes', 8, ()=>ctl('cmd=play'));
  all&=await phase('TV jumps forward 2 min', 10, ()=>ctl('cmd=seek&ms=150000'));
  all&=await phase('TV jumps back 1 min', 10, ()=>ctl('cmd=seek&ms=60000'));
  all&=await phase('small TV jump (+0.8 s)', 10, async()=>{ const t=await p.evaluate(u=>fetch(u).then(r=>r.json()),B+'/truth'); await ctl('cmd=seek&ms='+(t.pos+800)); });
  all&=await phase('host clock 1 h off', 10, ()=>ctl('cmd=offset&ms=3600000'));
  all&=await phase('slow Wi-Fi (600 kbps) + jump', 20, async()=>{ await ctl('cmd=slow&kbps=600'); await ctl('cmd=seek&ms=200000'); });
  all&=await phase('connection drops', 15, async()=>{ await ctl('cmd=fast'); await ctl('cmd=drop'); });
  all&=await phase('page hidden 5 s then back', 10, async()=>{
    await p.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true}); document.dispatchEvent(new Event('visibilitychange'));});
    await p.waitForTimeout(5000);
    await p.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>false}); document.dispatchEvent(new Event('visibilitychange'));});
  });
  all&=await phase('steady 20 s (end)', 20);
  console.log(all?'ALL PASS':'SOME FAILED');
  await b.close();
})();
