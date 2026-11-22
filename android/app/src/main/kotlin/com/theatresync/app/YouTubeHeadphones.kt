package com.theatresync.app

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * YouTube sound in the headphones. The TV's YouTube app plays the picture (and its sound comes from
 * the TV, so the phone never has it); this plays the same video on the phone in YouTube's official
 * embedded player — shown on screen, as YouTube requires — with its sound going to the headphones,
 * and keeps it in step with the TV: one calibrating jump after each start, then a jump only when it
 * drifts more than 0.25 s (at most every 5 s), allowing for the headphones' own delay where Android
 * reports it.
 */
object YouTubeHeadphones {
    /** Where the TV's video is now (seconds), whether it's playing, and which video. */
    data class Target(val videoId: String, val positionSec: Double, val playing: Boolean)

    /** What the phone player reports back. */
    data class Report(val driftMs: Long?, val problem: String?)

    // Pages are given the Quantari site as their address: YouTube's embedded player needs to know which
    // site it is on. Nothing is loaded from there.
    private const val ORIGIN = "https://quantari-solutions.vercel.app"

    internal fun page(videoId: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}#p{width:100%;height:100%}</style></head>
<body><div id="p"></div>
<script>
var player=null, ready=false, vid='$videoId', lead=0.15, calibrated=false, justSeeked=false, lastSeek=0, settleUntil=0, ctx=null;
try{ ctx=new (window.AudioContext||window.webkitAudioContext)(); }catch(e){}
function now(){ return performance.now(); }
function outLat(){ return ctx ? ((ctx.outputLatency||0)+(ctx.baseLatency||0)) : 0; }
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{videoId:vid,playerVars:{playsinline:1,controls:0,disablekb:1,rel:0,fs:0,iv_load_policy:3,origin:'$ORIGIN'},
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
""".trimIndent()

    internal fun js(t: Target) =
        String.format(Locale.US, "window.tsTarget&&tsTarget(%.3f,%b,'%s')", t.positionSec, t.playing, t.videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
}

/**
 * The one phone player for YouTube sound in the headphones, created once while it's switched on and kept
 * in step with [target] (polled 4×/s). The app shows it wherever it fits ([YouTubeHeadphonesView]): inside
 * the Now casting card, as a small framed player on other pages, or as the picture-in-picture window.
 * Moving it between them never reloads it, so the sound doesn't restart.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun rememberYouTubeHeadphonesWeb(key: String, videoId: String, target: () -> YouTubeHeadphones.Target?, onReport: (YouTubeHeadphones.Report) -> Unit): WebView {
    val context = LocalContext.current
    val report = rememberUpdatedState(onReport)
    val currentTarget = rememberUpdatedState(target)
    val web = remember(key) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // the owner switched this on: sound may start by itself
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            // Transparent until YouTube draws: the video's thumbnail shows underneath, never a black square.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(object {
                @JavascriptInterface fun report(drift: String, problem: String) {
                    post { report.value(YouTubeHeadphones.Report(drift.toLongOrNull(), problem.ifBlank { null })) }
                }
            }, "TS")
            loadDataWithBaseURL("https://quantari-solutions.vercel.app/", YouTubeHeadphones.page(videoId), "text/html", "utf-8", null)
        }
    }
    DisposableEffect(web) { onDispose { (web.parent as? ViewGroup)?.removeView(web); web.stopLoading(); web.destroy() } }
    LaunchedEffect(web) {
        while (true) {
            currentTarget.value()?.let { web.evaluateJavascript(YouTubeHeadphones.js(it), null) }
            delay(250)
        }
    }
    return web
}

/**
 * Shows the shared player (16:9) with the video's thumbnail underneath while it loads. Not clipped or
 * shadowed: Android can draw a WebView's video as a separate layer that turns black when the view is
 * clipped or transformed.
 */
@Composable
fun YouTubeHeadphonesView(web: WebView, videoId: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    // Compact: a small "sound link" tile. YouTube requires its player to stay visible and at least
    // 200 × 200 pixels, so it's never smaller than that on any phone.
    val size = if (compact) {
        val minDp = with(androidx.compose.ui.platform.LocalDensity.current) { 200.toDp() }
        Modifier.height(maxOf(90.dp, minDp)).aspectRatio(16f / 9f)
    } else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    Box(modifier.then(size).background(Color.Black)) {
        RemoteImage("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", null, Modifier.fillMaxSize()) {}
        AndroidView(
            factory = { (web.parent as? ViewGroup)?.removeView(web); web },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
