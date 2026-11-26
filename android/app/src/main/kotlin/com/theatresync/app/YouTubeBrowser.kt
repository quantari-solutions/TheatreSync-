package com.theatresync.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.theatresync.core.YouTubeLinks

/**
 * "Find a video": YouTube's mobile website inside TheatreSync, so there's no need to open the YouTube app
 * to get a link. Search and tap a video as usual; a "Cast to TV" button appears for it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubeBrowserScreen(tvName: String?, onPick: (String) -> Unit, onBack: () -> Unit) {
    var url by remember { mutableStateOf("https://m.youtube.com/") }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { val w = web; if (w != null && w.canGoBack()) w.goBack() else onBack() }
    Column(Modifier.fillMaxSize().background(Q.Background).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.padding(horizontal = 16.dp)) { TopBar(stringResource(R.string.yt_find_title), onBack) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true // don't play sound here: the TV plays it
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { url = u }
                            // YouTube's site changes page without loading a new one: follow it here.
                            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) { url = u }
                        }
                        loadUrl("https://m.youtube.com/")
                        web = this
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("yt_browser"),
            )
            if (YouTubeLinks.videoId(url) != null) {
                Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    GoldButton(
                        if (tvName != null) stringResource(R.string.yt_find_cast_to, tvName) else stringResource(R.string.yt_find_cast),
                        { web?.evaluateJavascript("document.querySelectorAll('video').forEach(v=>v.pause())", null); onPick(url) },
                        Modifier.fillMaxWidth().testTag("yt_find_cast"),
                    )
                }
            }
        }
    }
}
