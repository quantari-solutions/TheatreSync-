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
</style></head><body>
<div class="eyebrow">TheatreSync · TapIn</div>
<h1>Listen along</h1>
<div class="muted">The movie's sound, in your own headphones, in step with the big screen.</div>

<div class="card">
  <div class="title" id="movie">Connecting…</div>
  <div class="muted">Connect your headphones to this phone first.</div>
  <p><span class="pill" id="status">Waiting for the movie</span></p>
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
  var offset=null, best=1e9, seq=0, state=null, started=false;
  var delay=parseInt(localStorage.getItem('tsDelay')||'0',10);
  function showDelay(){document.getElementById('delay').textContent='Sound delay: '+delay+' ms';}
  showDelay();
  function now(){return performance.now();}
  function ping(){
    var t0=Math.round(now()), s=seq++;
    fetch('/clock?seq='+s+'&t0='+t0+'&id='+id,{cache:'no-store'}).then(function(r){return r.json();}).then(function(m){
      var t3=now(), p=m.payload, rtt=(t3-t0)-(p.t2-p.t1);
      if(rtt<best){best=rtt; offset=((p.t1-t0)+(p.t2-t3))/2;}
    }).catch(function(){});
  }
  function poll(){
    fetch('/state',{cache:'no-store'}).then(function(r){return r.json();}).then(function(m){
      state=m.payload; state.at=now();
      if(state.mediaTitle) mv.textContent=state.mediaTitle;
    }).catch(function(){ st.textContent='Can\'t reach the TheatreSync phone'; st.className='pill'; });
  }
  function target(){
    if(!state||offset===null) return null;
    var hostNow=now()+offset;
    var pos=state.mediaPositionMs;
    if(state.state==='playing') pos+= (hostNow-state.hostTimeMs)*(state.playbackRate||1);
    return pos-delay;
  }
  function step(){
    if(!started) return;
    var t=target();
    if(t===null) return;
    if(!state||state.state!=='playing'){ if(!a.paused) a.pause(); st.textContent=state&&state.state==='paused'?'Paused':'Waiting for the movie'; st.className='pill'; return; }
    if(a.paused){ a.currentTime=Math.max(0,(t+250)/1000); a.play().catch(function(){}); return; }
    var drift=a.currentTime*1000-t;
    if(Math.abs(drift)>600){ a.currentTime=Math.max(0,(t+150)/1000); a.playbackRate=1; }
    else if(Math.abs(drift)>40){ a.playbackRate=Math.min(1.05,Math.max(0.95,1-drift/2000)); }
    else a.playbackRate=1;
    st.textContent=Math.abs(drift)<=60?'In step':'Getting in step…'; st.className=Math.abs(drift)<=60?'pill ok':'pill';
  }
  document.getElementById('start').onclick=function(){
    started=true; this.textContent='Listening'; this.disabled=true;
    a.play().then(function(){a.pause();}).catch(function(){}); // unlock audio with this tap
    step();
  };
  document.getElementById('early').onclick=function(){delay+=20;localStorage.setItem('tsDelay',delay);showDelay();};
  document.getElementById('late').onclick=function(){delay-=20;localStorage.setItem('tsDelay',delay);showDelay();};
  for(var i=0;i<8;i++) setTimeout(ping,i*150);
  setInterval(ping,5000); setInterval(poll,1000); setInterval(step,250); poll();
})();
</script>
</body></html>
""".trimIndent()
}
