package com.theatresync.core

/**
 * TapIn listening page: a guest opens it in Safari or Chrome (from an NFC tap or QR code) and
 * hears the movie's sound in their own headphones, in sync with the TV. No app needed.
 *
 * Sync: the same NTP-style clock as the apps (GET /clock), the TV position from GET /state,
 * then the <audio> element is nudged with playbackRate (small drift) or a jump (large drift).
 * Browsers need a tap before they may play sound, hence the big button.
 */
object ListenPage {
    val HTML: String = """
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0B0B0F">
<title>TheatreSync · TapIn</title>
<style>
:root{--bg:#0B0B0F;--card:#14141B;--gold:#E8B84A;--gold2:#FFE7A3;--text:#F3EEE3;--muted:#A9A39A;--ok:#3CCB8C;--border:#2A2620}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;padding:24px 16px env(safe-area-inset-bottom)}
.eyebrow{color:var(--gold);letter-spacing:.18em;font-size:12px;font-weight:700;text-transform:uppercase}
h1{font-size:34px;margin:6px 0 4px;background:linear-gradient(90deg,var(--gold),var(--gold2));-webkit-background-clip:text;background-clip:text;color:transparent}
.card{background:var(--card);border:1px solid var(--border);border-radius:20px;padding:18px;margin:16px 0}
.title{font-size:20px;font-weight:700;margin:0 0 6px}
.muted{color:var(--muted);font-size:14px}
button{font:inherit;font-weight:700;border-radius:999px;border:1px solid var(--border);background:transparent;color:var(--gold2);min-height:48px;padding:0 18px}
.big{width:100%;min-height:64px;font-size:18px;color:#000;border:0;background:linear-gradient(90deg,var(--gold),var(--gold2))}
.row{display:flex;gap:10px}.row button{flex:1}
.pill{display:inline-block;border-radius:999px;padding:6px 12px;font-size:14px;font-weight:700;border:1px solid var(--border);color:var(--gold)}
.pill.ok{color:var(--ok);border-color:#1E5A40}
input{font:inherit;width:100%;min-height:48px;border-radius:14px;border:1px solid var(--border);background:var(--bg);color:var(--text);padding:0 14px;margin:6px 0 12px}
.big.stop{background:transparent;color:var(--gold2);border:1px solid var(--border)}
</style></head><body>
<div class="eyebrow">TheatreSync · TapIn</div>
<h1>Listen along</h1>
<div class="muted">The movie's sound, in your own headphones, in step with the big screen.</div>

<div class="card">
  <div class="title" id="movie">Connecting…</div>
  <div class="muted">Connect your headphones to this phone first.</div>
  <label class="muted" for="name">Your name (the host sees it)</label>
  <input id="name" maxlength="40" autocomplete="nickname">
  <label class="muted" for="dev">Your headphones</label>
  <input id="dev" maxlength="40" placeholder="e.g. AirPods, WH-1000XM4">
  <p><span class="pill" id="status">Waiting for the movie</span></p>
  <div class="muted" id="sync">&nbsp;</div>
  <button class="big" id="start">▶&nbsp; Start listening</button>
</div>

<div class="card">
  <div class="title">Do voices match lips?</div>
  <div class="muted">If not, tap a button a few times until they do.</div>
  <p class="row"><button id="early">Sound is early</button><button id="late">Sound is late</button></p>
  <div class="muted" id="delay">Sound delay: 0 ms</div>
</div>
<div class="muted" style="text-align:center">Keep this page open while you watch.</div>

<audio id="a" src="/audio" preload="auto" playsinline></audio>
<script>
(function(){
  var a=document.getElementById('a'), st=document.getElementById('status'), mv=document.getElementById('movie');
  var id='web-'+Math.random().toString(36).slice(2,10);
  // ---- tuning (same rules as the TheatreSync apps)
  var SETTLE=1000, SEEK_GAP=5000, SEEK_AT=350, NUDGE_AT=25;
  var samples=[], offset=null, seq=0, state=null, started=false;
  var lead=200, justStarted=false, calibrated=false, settleUntil=0, lastSeek=0;
  var lastTime=-1, lastAdvance=0, ctx=null;
  var delay=parseInt(localStorage.getItem('tsDelay')||'0',10);
  window.tsStats={seeks:0, reloads:0, drift:null, state:'idle', lead:lead};
  function showDelay(){document.getElementById('delay').textContent='Sound delay: '+delay+' ms';}
  showDelay();
  function now(){return performance.now();}
  function setStatus(t,ok){st.textContent=t; st.className=ok?'pill ok':'pill';}
  // Clock: NTP-style; keep the best (lowest round trip) of the last 12 samples.
  function ping(){
    var t0=now(), s=seq++;
    fetch('/clock?seq='+s+'&t0='+Math.round(t0)+'&id='+id,{cache:'no-store'}).then(function(r){return r.json();}).then(function(m){
      var t3=now(), p=m.payload, rtt=(t3-t0)-(p.t2-p.t1);
      var off=((p.t1-t0)+(p.t2-t3))/2;
      // The host's clock jumped (its app restarted): old samples are useless, start over.
      if(offset!==null && Math.abs(off-offset)>rtt+1000) samples=[];
      samples.push({rtt:rtt, off:off}); if(samples.length>12) samples.shift();
      var best=samples.reduce(function(b,x){return x.rtt<b.rtt?x:b;});
      offset=best.off;
    }).catch(function(){});
  }
  function poll(){
    fetch('/state',{cache:'no-store'}).then(function(r){return r.json();}).then(function(m){
      state=m.payload; if(state.mediaTitle) mv.textContent=state.mediaTitle;
    }).catch(function(){ setStatus('Can\'t reach the TheatreSync phone',false); });
  }
  function target(){
    if(!state||offset===null) return null;
    var pos=state.mediaPositionMs;
    if(state.state==='playing') pos+=((now()+offset)-state.hostTimeMs)*(state.playbackRate||1);
    return pos-delay;
  }
  // How late this phone's speakers/headphones play what the page plays (where the browser knows).
  function outputLatency(){ return ctx ? ((ctx.outputLatency||0)+(ctx.baseLatency||0))*1000 : 0; }
  function seekTo(t){
    a.currentTime=Math.max(0,(t+lead)/1000);
    lastSeek=now(); settleUntil=now()+SETTLE; justStarted=true; window.tsStats.seeks++;
  }
  var unsupported=false, failed=0, lastReload=0, playPending=false;
  function reload(){
    var at=a.currentTime; window.tsStats.reloads++; lastReload=now();
    a.src='/audio?r='+Date.now(); a.load(); a.currentTime=at; calibrated=false; justStarted=false;
  }
  // The sound file failed (checked on every step, so an error before the tap is caught too).
  function checkError(){
    if(!a.error) return false;
    // Code 4 twice: this browser can't play the movie's sound format (e.g. Dolby or DTS). Retrying won't help.
    if(a.error.code===4&&failed>=1){ unsupported=true; window.tsStats.state='unsupported';
      setStatus('This movie\'s sound format can\'t play in a browser',false); return true; }
    if(now()-lastReload>3000){ failed++; reload(); }
    setStatus('Reconnecting the sound…',false); return true;
  }
  function startPlaying(t){
    seekTo(t); playPending=true;
    var pr=a.play();
    if(!pr){ playPending=false; return; }
    pr.then(function(){ playPending=false; failed=0; }).catch(function(e){
      playPending=false;
      if(e&&e.name==='NotAllowedError'){ // the browser wants another tap before it plays sound
        stop(); document.getElementById('start').textContent='▶\u00a0 Tap to start listening';
        setStatus('Tap the button to start the sound',false);
      }
    });
  }
  function step(){
    if(!started||unsupported) return;
    if(checkError()) return;
    var t=target(); if(t===null) return;
    var playing=state.state==='playing';
    if(!playing){
      if(!a.paused) a.pause(); a.playbackRate=1; calibrated=false;
      window.tsStats.state=state.state; window.tsStats.drift=null;
      setStatus(state.state==='paused'?'Paused':state.state==='buffering'?'The TV is loading…':'Waiting for the movie',false); return;
    }
    if(a.paused){ if(!playPending) startPlaying(t); return; }
    // Loading after a jump (or a slow network): wait; judging drift now would cause jump after jump.
    if(a.seeking||a.readyState<3){ settleUntil=now()+SETTLE; window.tsStats.state='buffering'; setStatus('Getting the sound…',false); return; }
    // Stalled (time not moving although "playing"): wait, then restart the stream.
    if(a.currentTime!==lastTime){ lastTime=a.currentTime; lastAdvance=now(); }
    else if(now()-lastAdvance>6000){ lastAdvance=now(); reload(); return; }
    if(now()<settleUntil) return;
    var drift=a.currentTime*1000-outputLatency()-t;
    window.tsStats.drift=Math.round(drift); window.tsStats.state='playing'; window.tsStats.outLat=Math.round(outputLatency());
    if(justStarted){
      justStarted=false;
      lead=Math.min(1500,Math.max(0,lead-drift*0.75)); window.tsStats.lead=Math.round(lead);
      if(!calibrated && Math.abs(drift)>120){ calibrated=true; a.playbackRate=1; seekTo(t); return; }
      calibrated=true;
    }
    if(Math.abs(drift)>SEEK_AT){
      if(now()-lastSeek>SEEK_GAP||Math.abs(drift)>3000){ a.playbackRate=1; seekTo(t); return; }
      a.playbackRate=drift>0?0.94:1.06;
    } else if(Math.abs(drift)>NUDGE_AT){
      a.playbackRate=Math.min(1.05,Math.max(0.95,1-drift/1500));
    } else a.playbackRate=1;
    if(hostMuted) setStatus('The host turned your sound off',false);
    else setStatus(Math.abs(drift)<=60?'In step':'Getting in step…',Math.abs(drift)<=60);
    syncEl.textContent='Sync: '+(drift>=0?'+':'')+Math.round(drift)+' ms on '+(myDevice()||'this phone');
  }
  a.addEventListener('error',function(){ if(started) step(); });

  // ---- This phone, as the host sees it: reports about once a second; the answer carries the host's controls.
  var nameEl=document.getElementById('name'), btn=document.getElementById('start'), syncEl=document.getElementById('sync');
  var hostMuted=false, removedByHost=false;
  function guessName(){ var u=navigator.userAgent;
    var dev=/iPhone/.test(u)?'iPhone':/iPad/.test(u)?'iPad':/Android/.test(u)?'Android phone':'Computer';
    var br=/EdgA?\//.test(u)?'Edge':/SamsungBrowser/.test(u)?'Samsung Internet':/Firefox|FxiOS/.test(u)?'Firefox':/CriOS|Chrome/.test(u)?'Chrome':/Safari/.test(u)?'Safari':'Browser';
    return dev+' · '+br; }
  try{ nameEl.value=localStorage.getItem('tsName')||''; }catch(e){}
  nameEl.placeholder=guessName();
  nameEl.onchange=function(){ try{ localStorage.setItem('tsName',nameEl.value.trim()); }catch(e){} report(); };
  function myName(){ return nameEl.value.trim()||guessName(); }
  // The headphones on this phone. Browsers only name them where they already allow it (no prompt is
  // shown: asking for the microphone just to read a name can lower Bluetooth sound quality on iPhones),
  // otherwise the guest types it once.
  var devEl=document.getElementById('dev'), detected='';
  try{ devEl.value=localStorage.getItem('tsDev')||''; }catch(e){}
  devEl.onchange=function(){ try{ localStorage.setItem('tsDev',devEl.value.trim()); }catch(e){} report(); };
  var BT=/airpods|beats|buds|bluetooth|headphone|headset|momentum|wh-|wf-|jabra|bose|sony|sennheiser|jbl|pixel buds|nothing ear/i;
  function detectDevice(){
    if(!(navigator.mediaDevices&&navigator.mediaDevices.enumerateDevices)) return;
    navigator.mediaDevices.enumerateDevices().then(function(list){
      var named=list.filter(function(d){ return d.label&&(d.kind==='audiooutput'||d.kind==='audioinput'); });
      var hit=named.filter(function(d){ return d.kind==='audiooutput'&&BT.test(d.label); })[0]||named.filter(function(d){ return BT.test(d.label); })[0];
      detected=hit?hit.label.replace(/\s*\(.*\)$/,'').trim().slice(0,40):'';
      devEl.placeholder=detected||'e.g. AirPods, WH-1000XM4';
    }).catch(function(){});
  }
  detectDevice();
  function myDevice(){ return devEl.value.trim()||detected; }
  function report(){
    if(!started) return;
    var d=window.tsStats.drift;
    fetch('/guest?id='+id+'&name='+encodeURIComponent(myName())+'&dev='+encodeURIComponent(myDevice())+'&status='+window.tsStats.state+(d===null?'':'&drift='+d)+'&hidden='+(document.hidden?1:0),{cache:'no-store'})
      .then(function(r){return r.json();}).then(function(c){
        if(!started) return;
        if(c.removed){ stop(false); removedByHost=true; setStatus('The host stopped the sound for this phone',false); btn.textContent='▶\u00a0 Listen again'; return; }
        hostMuted=c.muted; a.muted=c.muted;
        try{ a.volume=Math.max(0,Math.min(1,c.volume/100)); }catch(e){}
      }).catch(function(){});
  }
  function leave(){ var u='/leave?id='+id; if(!(navigator.sendBeacon&&navigator.sendBeacon(u))) fetch(u,{method:'POST',keepalive:true}).catch(function(){}); }
  function start(){
    // A fresh id each time: the host ignores late reports from a page that already left or was removed.
    id='web-'+Math.random().toString(36).slice(2,10); removedByHost=false;
    started=true; btn.textContent='■\u00a0 Stop listening'; btn.className='big stop';
    if(!ctx){ try{ ctx=new (window.AudioContext||window.webkitAudioContext)(); }catch(e){ ctx=null; } }
    var pr=a.play(); if(pr) pr.then(function(){ a.pause(); }).catch(function(){}); // unlock sound with this tap
    window.tsStats.state='connecting'; report(); step();
  }
  function stop(tell){
    if(tell!==false) leave();
    started=false; a.pause(); a.playbackRate=1; calibrated=false; window.tsStats.state='stopped'; window.tsStats.drift=null;
    btn.textContent='▶\u00a0 Start listening'; btn.className='big'; btn.disabled=false; syncEl.innerHTML='&nbsp;';
    setStatus('Not listening',false);
  }
  btn.onclick=function(){ if(started) stop(); else start(); };
  // Closing the page or leaving the site: the host's list drops this phone at once.
  window.addEventListener('pagehide',function(){ if(started) leave(); });
  document.addEventListener('visibilitychange',function(){ report(); if(!document.hidden){ ping(); poll(); setTimeout(step,300); } });
  // Headphones switched on this phone: the delay changes, so measure again and allow a correcting jump now.
  if(navigator.mediaDevices&&navigator.mediaDevices.addEventListener)
    navigator.mediaDevices.addEventListener('devicechange',function(){ detectDevice(); if(!started) return; calibrated=false; lastSeek=0; settleUntil=now()+SETTLE; window.tsStats.switches=(window.tsStats.switches||0)+1; });
  setInterval(report,1000);
  document.getElementById('early').onclick=function(){delay+=20;localStorage.setItem('tsDelay',delay);showDelay();};
  document.getElementById('late').onclick=function(){delay-=20;localStorage.setItem('tsDelay',delay);showDelay();};
  for(var i=0;i<8;i++) setTimeout(ping,i*120);
  setInterval(ping,3000); setInterval(poll,1000); setInterval(step,250); poll();
})();
</script>
</body></html>
""".trimIndent()
}
