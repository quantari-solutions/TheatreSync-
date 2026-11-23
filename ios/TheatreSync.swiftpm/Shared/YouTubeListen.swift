import Foundation

/// TheatreSync's YouTube listening pages (mirror of YouTubeTapIn.kt and YouTubeHeadphones.kt).
/// "TV sound in your headphones": YouTube's official embedded player on this phone, kept in step with the
/// TV. TapIn for YouTube: the same for a guest's phone, from a web page this phone serves (QR code).
enum YouTubeListen {
    /// The Quantari site given as the page's address: YouTube's embedded player needs to know which site it's on.
    static let origin = "https://quantari-solutions.vercel.app"

    static func clean(_ id: String) -> String { String(id.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }.prefix(32)) }

    /// /yt/state for TapIn guests.
    static func stateJson(videoId: String?, position: Double, playing: Bool) -> String {
        guard let v = videoId else { return "{\"videoId\":null}" }
        return "{\"videoId\":\"\(clean(v))\",\"position\":\(String(format: "%.3f", position)),\"playing\":\(playing)}"
    }

    /// The JavaScript call that steers the in-app player to the TV's position.
    static func steer(videoId: String, position: Double, playing: Bool) -> String {
        "window.tsTarget&&tsTarget(\(String(format: "%.3f", position)),\(playing),'\(clean(videoId))')"
    }

    /// The in-app player page ("TV sound in your headphones"). Reports back through webkit message "TS".
    static func headphonesPage(videoId: String) -> String {
        let safe = clean(videoId)
        return #"""
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}#p{width:100%;height:100%}</style></head>
<body><div id="p"></div>
<script>
var TS={report:function(a,b){try{webkit.messageHandlers.TS.postMessage([a,b]);}catch(e){}}};
var player=null, ready=false, vid='\#(safe)', lead=0.15, calibrated=false, justSeeked=false, lastSeek=0, settleUntil=0, ctx=null;
try{ ctx=new (window.AudioContext||window.webkitAudioContext)(); }catch(e){}
function now(){ return performance.now(); }
function outLat(){ return ctx ? ((ctx.outputLatency||0)+(ctx.baseLatency||0)) : 0; }
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{videoId:vid,playerVars:{playsinline:1,controls:0,disablekb:1,rel:0,fs:0,iv_load_policy:3,origin:'\#(origin)'},
    events:{onReady:function(){ ready=true; TS.report('', ''); },
            onError:function(e){ TS.report('', 'error '+e.data); }}});
}
function seek(t){ player.seekTo(Math.max(0,t+lead),true); lastSeek=now(); settleUntil=now()+1000; justSeeked=true; }
window.tsTarget=function(t,playing,v){
  if(!ready) return;
  if(v!==vid){ vid=v; calibrated=false; player.loadVideoById(v,Math.max(0,t)); settleUntil=now()+1500; return; }
  var s=player.getPlayerState();
  if(!playing){ if(s===1||s===3) player.pauseVideo(); calibrated=false; TS.report('', ''); return; }
  if(s!==1&&s!==3){ seek(t); player.playVideo(); return; }
  if(s===3){ settleUntil=now()+1000; return; }
  if(now()<settleUntil) return;
  var drift=player.getCurrentTime()-outLat()-t;
  if(justSeeked){ justSeeked=false; lead=Math.min(2,Math.max(0,lead-drift*0.75));
    if(!calibrated&&Math.abs(drift)>0.12){ calibrated=true; seek(t); return; } calibrated=true; }
  if(Math.abs(drift)>0.25&&(now()-lastSeek>5000||Math.abs(drift)>3)){ seek(t); return; }
  TS.report(String(Math.round(drift*1000)), '');
};
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
"""#
    }

    /// The TapIn for YouTube page guests open (/yt).
    static let tapInPage = #"""
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TheatreSync · TV sound</title>
<style>
html,body{margin:0;background:#050508;color:#F5F6FA;font-family:-apple-system,system-ui,Roboto,sans-serif}
.wrap{max-width:520px;margin:0 auto;padding:24px 18px}
h1{font-size:30px;margin:6px 0 4px;background:linear-gradient(90deg,#E8B84A,#FFE7A3,#E8B84A);-webkit-background-clip:text;color:transparent}
.eye{color:#E8B84A;letter-spacing:.3em;font-size:12px;font-weight:700}
p{color:#C5CAD6;line-height:1.45}
button{width:100%;padding:16px;border:0;border-radius:28px;font-size:18px;font-weight:700;background:linear-gradient(90deg,#F0C44C,#FFE9A8,#D4A017);color:#000;margin:14px 0}
#box{border:1px solid #2A2410;border-radius:16px;overflow:hidden;width:220px;aspect-ratio:16/9;margin:10px 0;display:none}
#p{width:100%;height:100%}
#st{color:#E8B84A;font-weight:600}
</style></head><body><div class="wrap">
<div class="eye">QUANTARI · THEATRESYNC</div>
<h1>TV sound in your headphones</h1>
<p>Connect your headphones to this phone, then tap Start. You'll hear what's playing on the TV, in step with the picture.</p>
<button id="go">Start listening</button>
<div id="box"><div id="p"></div></div>
<div id="st">Ready.</div>
<p style="font-size:13px;color:#8A8FA0">Keep this page open (the small player is YouTube's: it has to stay on screen to give you the sound). Adverts can differ for a moment; it catches up afterwards.</p>
</div>
<script>
var player=null,ready=false,vid=null,lead=0.15,calibrated=false,justSeeked=false,lastSeek=0,settleUntil=0,state=null,stateAt=0;
function now(){return performance.now();}
function st(t){document.getElementById('st').textContent=t;}
async function poll(){
  try{ var r=await fetch('/yt/state',{cache:'no-store'}); state=await r.json(); stateAt=now(); }catch(e){ st('Lost the host phone… retrying'); }
  setTimeout(poll,500);
}
function target(){ if(!state||!state.videoId) return null; var t=state.position+(state.playing?(now()-stateAt)/1000:0); return {t:t,playing:state.playing,v:state.videoId}; }
function seek(t){ player.seekTo(Math.max(0,t+lead),true); lastSeek=now(); settleUntil=now()+1000; justSeeked=true; }
function tick(){
  var g=target();
  if(!ready||!g){ if(!g) st('Nothing on YouTube on the TV right now.'); return; }
  if(g.v!==vid){ vid=g.v; calibrated=false; player.loadVideoById(g.v,Math.max(0,g.t)); settleUntil=now()+1500; return; }
  var s=player.getPlayerState();
  if(!g.playing){ if(s===1||s===3) player.pauseVideo(); calibrated=false; st('Paused on the TV'); return; }
  if(s!==1&&s!==3){ seek(g.t); player.playVideo(); return; }
  if(s===3||now()<settleUntil) return;
  var drift=player.getCurrentTime()-g.t;
  if(justSeeked){ justSeeked=false; lead=Math.min(2,Math.max(0,lead-drift*0.75)); if(!calibrated&&Math.abs(drift)>0.12){calibrated=true;seek(g.t);return;} calibrated=true; }
  if(Math.abs(drift)>0.25&&(now()-lastSeek>5000||Math.abs(drift)>3)){ seek(g.t); return; }
  st('In step with the TV · '+(drift>=0?'+':'')+Math.round(drift*1000)+' ms');
}
window.onYouTubeIframeAPIReady=function(){
  var g=target();
  vid=g?g.v:null;
  player=new YT.Player('p',{videoId:vid||'',playerVars:{playsinline:1,controls:0,disablekb:1,rel:0,fs:0,iv_load_policy:3},
    events:{onReady:function(){ready=true; if(g){seek(g.t); if(g.playing) player.playVideo();} setInterval(tick,250);},
            onError:function(e){st('YouTube won\'t play this video outside its own apps (error '+e.data+').');}}});
};
document.getElementById('go').onclick=function(){
  this.style.display='none'; document.getElementById('box').style.display='block'; st('Starting…');
  var s=document.createElement('script'); s.src='https://www.youtube.com/iframe_api'; document.body.appendChild(s);
};
poll();
</script></body></html>
"""#
}
