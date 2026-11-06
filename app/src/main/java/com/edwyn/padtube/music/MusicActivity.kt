package com.edwyn.padtube.music

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edwyn.padtube.AdBlocker
import com.edwyn.padtube.BuildConfig
import com.edwyn.padtube.ExternalLinkPolicy
import com.edwyn.padtube.LinkAction
import com.edwyn.padtube.PlaybackService
import com.edwyn.padtube.R
import com.edwyn.padtube.RendererRecovery
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MusicActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var composeView: ComposeView
    private var webView: WebView? = null
    private var browserViewModel: BrowserViewModel? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var mediaPlaying = false
    private var webViewGeneration by mutableIntStateOf(0)
    private var lastUrl: String? = null
    private var recoveryUrl: String? = null
    private var lastRecoveryAt: Long? = null
    private var inForeground = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val pauseWebViewWhenIdle = Runnable { webView?.onPause() }
    private val stopServiceWhenIdle = Runnable { MusicPlaybackService.stop(applicationContext) }

    private val playbackHandler = PlaybackCommandHandler { command ->
        runOnUiThread { executePlaybackCommand(command) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        PlaybackBridge.attach(playbackHandler) { positionMs ->
            runOnUiThread { executeSeek(positionMs) }
        }

        root = FrameLayout(this)
        composeView = ComposeView(this).apply {
            setContent {
                val vm: BrowserViewModel = viewModel()
                browserViewModel = vm
                val uiState by vm.uiState.collectAsState()

                PadMusicTheme {
                    PadMusicScreen(
                        uiState = uiState,
                        webViewGeneration = webViewGeneration,
                        webViewFactory = { createWebView(savedInstanceState) },
                        onBack = { webView?.goBack() },
                        onForward = { webView?.goForward() },
                        onHome = {
                            vm.onRetry()
                            webView?.loadUrl(MUSIC_HOME)
                        },
                        onRefresh = {
                            vm.onRetry()
                            webView?.reload()
                        },
                        onVideo = { executePlaybackCommand(PlaybackCommand.VIDEO) },
                        onOpenInChrome = { openAccountMode(webView?.url ?: MUSIC_HOME) },
                        onRetry = {
                            vm.onRetry()
                            webView?.reload()
                        },
                        onDismissMessage = { vm.showMessage(null) }
                    )
                }
            }
        }
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    webView?.canGoBack() == true -> webView?.goBack()
                    // finish() would stop the music; keep it playing in the background.
                    mediaPlaying -> moveTaskToBack(true)
                    else -> finish()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(savedInstanceState: Bundle?): WebView {
        webView?.let { return it }

        val instance = object : WebView(this) {
            // Locking the screen hides the window and Android Chromium then
            // force-suspends media ("background media was paused to save
            // power"); page scripts cannot resume it. Keep reporting the window
            // as visible so music continues with the screen off.
            override fun onWindowVisibilityChanged(visibility: Int) {
                if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
            }
        }
        return instance.apply {
            webView = this
            // Compose's AndroidView gives children WRAP_CONTENT params, which
            // makes Chromium force a zero layout height: CSS 100vh becomes 0
            // and YouTube Music's player page collapses.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            keepScreenOn = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                builtInZoomControls = false
                displayZoomControls = false
                // Phones (smallest width < 600dp, e.g. Galaxy S25 Ultra ~412dp):
                // lay the page out at the real screen width. YouTube Music's
                // desktop site is responsive and switches to its narrow,
                // phone-style layout; the ~1000px overview viewport used on
                // tablets would shrink everything to an unreadable size.
                val phone = resources.configuration.smallestScreenWidthDp < 600
                loadWithOverviewMode = !phone
                useWideViewPort = !phone
                userAgentString = desktopUserAgentFor(WebSettings.getDefaultUserAgent(this@MusicActivity))
            }

            // WebView adds X-Requested-With (the app package) to requests; Google
            // uses it to reject embedded sign-in. An empty allow list sends it to
            // no origin, which lets the user sign in inside PadMusic.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                WebSettingsCompat.setUserAgentMetadata(
                    settings,
                    desktopUserAgentMetadataFor(WebSettings.getDefaultUserAgent(this@MusicActivity))
                )
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(instance, true)
            }
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

            // Playback state from the page. Unlike addJavascriptInterface, a
            // WebMessageListener is only exposed to music.youtube.com, not to
            // ad or third-party iframes.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(
                    this,
                    MusicCleaner.BRIDGE_NAME,
                    MUSIC_ORIGINS
                ) { _, message, _, _, _ ->
                    onMediaState(message.data ?: return@addWebMessageListener)
                }
            }
            // Inject before YouTube Music's own scripts so the player-response
            // ad pruning and hidden ad CSS apply on first load, not after
            // onPageFinished.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, MusicCleaner.script, MUSIC_ORIGINS)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    val action = ExternalLinkPolicy.actionFor(
                        scheme = uri.scheme,
                        allowedHost = NavigationPolicy.isAllowed(uri.scheme, uri.host),
                        isMainFrame = request.isForMainFrame,
                        hasGesture = request.hasGesture(),
                        isRedirect = request.isRedirect
                    )
                    when (action) {
                        LinkAction.STAY_IN_APP -> return false
                        LinkAction.OPEN_EXTERNALLY -> openExternal(uri.toString())
                        LinkAction.BLOCK -> browserViewModel?.showMessage("已阻止打开此链接")
                    }
                    return true
                }

                // By default a dead renderer takes the whole app down, stopping
                // any playback; replace just the WebView instead.
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    recoverFromRendererGone(view)
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return if (AdBlocker.shouldBlock(request.url.toString())) {
                        WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    } else {
                        super.shouldInterceptRequest(view, request)
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    lastUrl = url ?: lastUrl
                    browserViewModel?.onNavigationStarted(url)
                    updateBrowserState(view, 0)
                }

                // YouTube Music navigates with history.pushState, which skips
                // onPageStarted; track the URL so recovery returns to this page.
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    lastUrl = url ?: lastUrl
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (NavigationPolicy.isMusicHost(url)) {
                        view.evaluateJavascript(MusicCleaner.script, null)
                    }
                    updateBrowserState(view, 100)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        browserViewModel?.onLoadError(request.url.toString())
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    updateBrowserState(view, newProgress)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    updateBrowserState(view, progress)
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    enterFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }

                // YouTube Music asks "Leave site?" while music plays. In the app,
                // Home / Refresh / Back are deliberate, so leave without asking.
                override fun onJsBeforeUnload(
                    view: WebView,
                    url: String?,
                    message: String?,
                    result: JsResult
                ): Boolean {
                    result.confirm()
                    return true
                }
            }

            val pendingRecovery = recoveryUrl
            recoveryUrl = null
            when {
                pendingRecovery != null -> loadUrl(pendingRecovery)
                savedInstanceState == null || restoreState(savedInstanceState) == null ->
                    loadUrl(MUSIC_HOME)
            }
        }
    }

    /**
     * The dead WebView accepts no further calls: drop it and let Compose build a
     * new one. Compose is paused while the activity is stopped, so a renderer
     * killed for memory in the background reloads only when the user returns.
     */
    private fun recoverFromRendererGone(deadView: WebView) {
        val now = SystemClock.elapsedRealtime()
        recoveryUrl = RendererRecovery.urlAfterCrash(lastUrl, MUSIC_HOME, lastRecoveryAt, now)
        lastRecoveryAt = now
        exitFullscreen(notifyPage = false)
        webView = null
        (deadView.parent as? ViewGroup)?.removeView(deadView)
        deadView.destroy()
        mediaPlaying = false
        updateIdleTimer()
        // Show as paused (and release the wake lock) until the new page reports in.
        MusicPlaybackService.update(applicationContext, "", "", "", false, 0, 0)
        webViewGeneration++
        browserViewModel?.showMessage("页面进程已退出，已重新加载")
    }

    /** Handles a JSON media-state message posted by [MusicCleaner] (main thread). */
    private fun onMediaState(json: String) {
        val state = runCatching { JSONObject(json) }.getOrNull() ?: return
        val title = state.optString("title").replace(WHITESPACE, " ").trim().take(180)
        val artist = state.optString("artist").replace(WHITESPACE, " ").trim().take(180)
        val artwork = state.optString("artwork").trim()
        val playing = state.optBoolean("playing")
        val wasPlaying = mediaPlaying
        mediaPlaying = playing
        // The two launcher icons share one speaker: only one plays at a time.
        if (playing && !wasPlaying) PlaybackService.commandHandler?.invoke(false)
        if (playing != wasPlaying) updateIdleTimer()
        val positionSeconds = state.optDouble("position", 0.0).takeIf { it.isFinite() } ?: 0.0
        val durationSeconds = state.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0
        MusicPlaybackService.update(
            applicationContext,
            title,
            artist,
            artwork,
            playing,
            (positionSeconds * 1000).toLong(),
            (durationSeconds * 1000).toLong()
        )
    }

    /**
     * Idle in the background: pause the WebView to save battery, but not at once,
     * since the page reports "not playing" briefly between tracks. Later, release
     * the notification too.
     */
    private fun updateIdleTimer() {
        idleHandler.removeCallbacks(pauseWebViewWhenIdle)
        idleHandler.removeCallbacks(stopServiceWhenIdle)
        if (!mediaPlaying && !inForeground) {
            idleHandler.postDelayed(pauseWebViewWhenIdle, IDLE_PAUSE_WEBVIEW_MS)
            idleHandler.postDelayed(stopServiceWhenIdle, IDLE_STOP_MS)
        }
    }

    private fun executePlaybackCommand(command: PlaybackCommand) {
        // The WebView may be paused after idling in the background.
        if (command != PlaybackCommand.PAUSE) webView?.onResume()
        val jsCommand = when (command) {
            PlaybackCommand.PLAY -> "play"
            PlaybackCommand.PAUSE -> "pause"
            PlaybackCommand.NEXT -> "next"
            PlaybackCommand.PREVIOUS -> "previous"
            PlaybackCommand.VIDEO -> "video"
        }
        webView?.evaluateJavascript(
            "window.__padMusicCommand && window.__padMusicCommand('$jsCommand');",
            null
        )
    }

    private fun executeSeek(positionMs: Long) {
        webView?.evaluateJavascript(
            "window.__padMusicCommand && window.__padMusicCommand('seek', $positionMs);",
            null
        )
    }

    private fun updateBrowserState(view: WebView, progress: Int) {
        browserViewModel?.onPageState(
            title = view.title,
            progress = progress,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward()
        )
    }

    private fun openExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            browserViewModel?.showMessage("没有找到可打开此链接的应用")
        } catch (_: SecurityException) {
            browserViewModel?.showMessage("没有找到可打开此链接的应用")
        }
    }

    /** Backup: open the page in a Chrome Custom Tab with Chrome's own login. */
    private fun openAccountMode(url: String) {
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.rgb(28, 12, 18))
            .setNavigationBarColor(Color.rgb(9, 9, 11))
            .build()
        val customTab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(colors)
            .build()

        try {
            customTab.intent.setPackage(CHROME_PACKAGE)
            customTab.launchUrl(this, url.toUri())
        } catch (_: ActivityNotFoundException) {
            try {
                customTab.intent.setPackage(null)
                customTab.launchUrl(this, url.toUri())
            } catch (_: ActivityNotFoundException) {
                openExternal(url)
            }
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        view.setBackgroundColor(Color.BLACK)
        composeView.visibility = View.GONE
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen(notifyPage: Boolean = true) {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        composeView.visibility = View.VISIBLE
        if (notifyPage) customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, root).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        // Persist the session now: the process may be killed while in the background.
        CookieManager.getInstance().flush()
        // Do not call WebView.onPause() here: audio must continue with the screen
        // off. updateIdleTimer pauses it only if nothing is playing.
        inForeground = false
        updateIdleTimer()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        inForeground = true
        updateIdleTimer()
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(pauseWebViewWhenIdle)
        idleHandler.removeCallbacks(stopServiceWhenIdle)
        PlaybackBridge.detach(playbackHandler)
        MusicPlaybackService.stop(applicationContext)
        customView?.let(root::removeView)
        customView = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val MUSIC_HOME = "https://music.youtube.com/"
        private val MUSIC_ORIGINS = setOf("https://music.youtube.com")
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val FALLBACK_CHROME_MAJOR = "150"
        private const val IDLE_PAUSE_WEBVIEW_MS = 30 * 1000L
        private const val IDLE_STOP_MS = 10 * 60 * 1000L
        private val WHITESPACE = Regex("\\s+")

        // Claim the same Chrome major version the installed WebView runs, so
        // YouTube Music and Google sign-in never see a stale browser version.
        internal fun desktopUserAgentFor(webViewUserAgent: String): String {
            val major = Regex("""Chrome/(\d+)""").find(webViewUserAgent)?.groupValues?.get(1)
                ?: FALLBACK_CHROME_MAJOR
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
        }

        // Client hints (navigator.userAgentData / Sec-CH-UA) matching the UA
        // above. Left alone, WebView reports brand "Android WebView" on
        // platform "Android", which contradicts the Windows UA and makes Google
        // reject sign-in as "This browser or app may not be secure".
        internal fun desktopUserAgentMetadataFor(webViewUserAgent: String): UserAgentMetadata {
            val fullVersion = Regex("""Chrome/([\d.]+)""").find(webViewUserAgent)?.groupValues?.get(1)
                ?: "$FALLBACK_CHROME_MAJOR.0.0.0"
            val major = fullVersion.substringBefore('.')
            fun brand(name: String, version: String, full: String) =
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand(name)
                    .setMajorVersion(version)
                    .setFullVersion(full)
                    .build()
            return UserAgentMetadata.Builder()
                .setBrandVersionList(
                    listOf(
                        brand("Google Chrome", major, fullVersion),
                        brand("Chromium", major, fullVersion),
                        brand("Not_A Brand", "24", "24.0.0.0")
                    )
                )
                .setFullVersion(fullVersion)
                .setPlatform("Windows")
                .setPlatformVersion("15.0.0")
                .setArchitecture("x86")
                .setBitness(64)
                .setModel("")
                .setMobile(false)
                .build()
        }
    }
}

@Composable
private fun PadMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = ComposeColor(0xFFFF1744),
            background = ComposeColor(0xFF09090B),
            surface = ComposeColor(0xFF101014),
            onBackground = ComposeColor(0xFFF5F5F6),
            onSurface = ComposeColor(0xFFF5F5F6)
        ),
        content = content
    )
}

@Composable
private fun PadMusicScreen(
    uiState: MusicUiState,
    webViewGeneration: Int,
    webViewFactory: () -> WebView,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onVideo: () -> Unit,
    onOpenInChrome: () -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message) {
        val text = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onDismissMessage()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column {
                PadMusicToolbar(
                    onBack = onBack,
                    onForward = onForward,
                    onHome = onHome,
                    onRefresh = onRefresh,
                    onVideo = onVideo,
                    canGoBack = uiState.canGoBack,
                    canGoForward = uiState.canGoForward,
                    onOpenInChrome = onOpenInChrome
                )

                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        progress = { uiState.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = ComposeColor.Transparent
                    )
                } else {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            key(webViewGeneration) {
                AndroidView(factory = { webViewFactory() }, modifier = Modifier.fillMaxSize())
            }

            if (!uiState.firstLoadComplete && !uiState.hasError) {
                LoadingPlaceholder()
            }
            if (uiState.hasError) {
                ErrorState(onRetry = onRetry)
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) { data ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    containerColor = ComposeColor(0xEE2A2A30),
                    contentColor = ComposeColor.White
                ) { Text(data.visuals.message) }
            }
        }
    }
}

@Composable
private fun PadMusicToolbar(
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onVideo: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onOpenInChrome: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val medium = maxWidth >= 620.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(11.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("♫", color = ComposeColor.White, fontSize = 20.sp)
            }
            if (medium) {
                Text(
                    "PadMusic 音乐",
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1
                )
            }

            Spacer(Modifier.weight(1f))

            if (medium) {
                ToolbarIcon(R.drawable.ic_home, "首页", true, onHome)
                ToolbarIcon(R.drawable.ic_refresh, "刷新", true, onRefresh)
            }
            ToolbarIcon(R.drawable.ic_video, "切换播放画面", true, onVideo)

            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "更多",
                        tint = ComposeColor(0xFFECECF0)
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("后退") },
                        enabled = canGoBack,
                        onClick = { open = false; onBack() }
                    )
                    DropdownMenuItem(
                        text = { Text("前进") },
                        enabled = canGoForward,
                        onClick = { open = false; onForward() }
                    )
                    if (!medium) {
                        DropdownMenuItem(text = { Text("首页") }, onClick = { open = false; onHome() })
                        DropdownMenuItem(text = { Text("刷新") }, onClick = { open = false; onRefresh() })
                    }
                    DropdownMenuItem(
                        text = { Text("用 Chrome 打开（备用）") },
                        onClick = { open = false; onOpenInChrome() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    iconRes: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = if (enabled) ComposeColor(0xFFECECF0) else ComposeColor(0xFF5F5F68)
        )
    }
}

@Composable
private fun LoadingPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("♫", color = ComposeColor.White, fontSize = 26.sp)
        }
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.5.dp
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("😕", fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "页面加载失败",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "请检查网络连接后重试",
            fontSize = 14.sp,
            color = ComposeColor(0xFF9D9DA8)
        )
        Spacer(Modifier.height(24.dp))
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = ComposeColor.White
            ),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 10.dp)
        ) {
            Text("重试", fontWeight = FontWeight.SemiBold)
        }
    }
}
