package com.edwyn.padtube

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edwyn.padtube.music.PlaybackBridge
import com.edwyn.padtube.music.PlaybackCommand
import java.io.ByteArrayInputStream
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var composeView: ComposeView
    private var webView: WebView? = null
    private var browserViewModel: BrowserViewModel? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var mobileUserAgent: String = ""
    private var desktopUserAgent: String = ""
    private var pipMode by mutableStateOf(false)
    private var mediaPlaying = false
    private var playbackServiceStarted = false
    private var webViewGeneration by mutableIntStateOf(0)
    private var lastUrl: String? = null
    private var recoveryUrl: String? = null
    private var lastRecoveryAt: Long? = null
    private var lastPageTapAt = 0L
    private var inForeground = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val stopWhenIdle = Runnable {
        // A paused video in the PiP window is still in use; closing the window
        // restarts this timer.
        if (isInPipMode()) return@Runnable
        webView?.onPause()
        PlaybackService.stop(this)
        playbackServiceStarted = false
    }
    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        root = FrameLayout(this)
        composeView = ComposeView(this).apply {
            setContent {
                val vm: BrowserViewModel = viewModel()
                browserViewModel = vm
                val uiState by vm.uiState.collectAsState()

                LaunchedEffect(Unit) {
                    vm.setAutoLogin(preferences.getBoolean(PREFERENCE_AUTO_LOGIN, false))
                    vm.setDesktopMode(desktopModePreference())
                }

                PadTubeTheme {
                    PadTubeScreen(
                        uiState = uiState,
                        showToolbar = !pipMode,
                        webViewGeneration = webViewGeneration,
                        webViewFactory = { createWebView(savedInstanceState) },
                        onBack = { webView?.goBack() },
                        onForward = { webView?.goForward() },
                        onHome = {
                            vm.onRetry()
                            webView?.loadUrl(YOUTUBE_HOME)
                        },
                        onRefresh = {
                            vm.onRetry()
                            webView?.reload()
                        },
                        onToggleDesktop = { toggleDesktopMode() },
                        onLoginMode = { webView?.loadUrl(SIGN_IN_URL) },
                        onLoginChrome = { openAuthenticatedMode(YOUTUBE_HOME) },
                        onOpenExternal = { openExternal(webView?.url ?: YOUTUBE_HOME) },
                        onEnterPip = { enterPipIfPossible() },
                        onToggleAutoLogin = { setAutoLoginEnabled(!uiState.autoLogin) },
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

        if (preferences.getBoolean(PREFERENCE_AUTO_LOGIN, false)) {
            root.post {
                if (!isFinishing && !isDestroyed) {
                    openAuthenticatedMode(YOUTUBE_HOME)
                }
            }
        }

        PlaybackService.commandHandler = ::handleMediaCommand

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    webView?.canGoBack() == true -> webView?.goBack()
                    // finish() would stop playback; keep it running in the background.
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
            // Locking the screen or leaving the app hides the window, and
            // Android Chromium then force-pauses video ("background media was
            // paused to save power"). Keep reporting the window as visible so a
            // playing video continues with the screen locked; when nothing is
            // playing, onPause() still pauses the WebView to save battery.
            override fun onWindowVisibilityChanged(visibility: Int) {
                if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
            }
        }
        return instance.apply {
            webView = this
            // Compose's AndroidView gives children WRAP_CONTENT params, which
            // makes Chromium force a zero layout height: CSS 100vh becomes 0
            // and YouTube's player collapses to its 240px minimum.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            // Keep the screen awake only while actually watching (fullscreen);
            // browsing feeds should let the display time out to save battery.
            keepScreenOn = false

            mobileUserAgent = WebSettings.getDefaultUserAgent(this@MainActivity)
            desktopUserAgent = desktopUserAgentFor(mobileUserAgent)
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
            }
            applySiteMode(settings, desktopModePreference())

            // Suppress the X-Requested-With header (app package name) that WebView
            // adds automatically — Google uses it to detect and block embedded
            // sign-in. Empty allow list == header sent to no origin.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }

            // Inject the cleaner before YouTube's own scripts run so feed ads
            // are hidden on first paint instead of after onPageFinished, which
            // the heavy YouTube home page reaches several seconds late.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, YouTubeCleaner.script, YOUTUBE_ORIGINS)
            }

            // Playback state for the lock-screen controls. A WebMessageListener
            // is only exposed to YouTube origins, unlike addJavascriptInterface
            // which every frame (including ad iframes) could call.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) {
                WebViewCompat.addWebMessageListener(
                    this,
                    MediaBridge.LISTENER_NAME,
                    YOUTUBE_ORIGINS
                ) { _, message, _, _, _ ->
                    onMediaState(message.data ?: return@addWebMessageListener)
                }
                WebViewCompat.addDocumentStartJavaScript(this, MediaBridge.reportScript, YOUTUBE_ORIGINS)
                WebViewCompat.addWebMessageListener(
                    this,
                    NativeTap.LISTENER_NAME,
                    YOUTUBE_ORIGINS
                ) { _, message, _, _, _ ->
                    tapPage(message.data ?: return@addWebMessageListener)
                }
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(instance, true)
            }
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    // In-WebView sign-in: accounts.google.com is an allowed host,
                    // so let the sign-in flow load here instead of punting to Chrome.
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

                // YouTube navigates with history.pushState, which fires neither
                // onPageStarted nor onPageFinished; keep back/forward in sync.
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    lastUrl = url ?: lastUrl
                    updateBrowserState(view, view.progress)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (NavigationPolicy.isYouTubeHost(url?.toUri()?.host)) {
                        view.evaluateJavascript(YouTubeCleaner.script, null)
                    }
                    updateBrowserState(view, 100)
                    val loggedIn = CookieManager.getInstance()
                        .getCookie("https://www.youtube.com")
                        ?.contains("LOGIN_INFO") == true
                    browserViewModel?.setLoggedIn(loggedIn)
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

                override fun onShowCustomView(
                    view: View,
                    callback: CustomViewCallback
                ) {
                    enterFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }

            val pendingRecovery = recoveryUrl
            recoveryUrl = null
            when {
                pendingRecovery != null -> loadUrl(pendingRecovery)
                savedInstanceState == null || restoreState(savedInstanceState) == null ->
                    loadUrl(YOUTUBE_HOME)
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
        recoveryUrl = RendererRecovery.urlAfterCrash(lastUrl, YOUTUBE_HOME, lastRecoveryAt, now)
        lastRecoveryAt = now
        exitFullscreen(notifyPage = false)
        webView = null
        (deadView.parent as? ViewGroup)?.removeView(deadView)
        deadView.destroy()
        mediaPlaying = false
        updateIdleTimer()
        if (playbackServiceStarted) {
            runCatching { PlaybackService.update(this, false, null) }
        }
        webViewGeneration++
        browserViewModel?.showMessage("页面进程已退出，已重新加载")
    }

    private fun updateBrowserState(view: WebView, progress: Int) {
        browserViewModel?.onPageState(
            title = view.title,
            progress = progress,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward()
        )
    }

    private fun onMediaState(json: String) {
        val state = runCatching { JSONObject(json) }.getOrNull() ?: return
        val playing = state.optBoolean("playing")
        val wasPlaying = mediaPlaying
        mediaPlaying = playing
        // The two launcher icons share one speaker: only one plays at a time.
        if (playing && !wasPlaying) PlaybackBridge.dispatch(PlaybackCommand.PAUSE)
        if (playing != wasPlaying) updateIdleTimer()
        // Start the service on first playback only; afterwards keep it updated
        // so paused videos can be resumed from the lock screen.
        if (!playing && !playbackServiceStarted) return
        runCatching {
            PlaybackService.update(this, playing, state.optString("title"))
            playbackServiceStarted = true
        }
    }

    /** Left paused in the background for a while: release the notification and WebView. */
    private fun updateIdleTimer() {
        idleHandler.removeCallbacks(stopWhenIdle)
        if (!mediaPlaying && !inForeground) idleHandler.postDelayed(stopWhenIdle, IDLE_STOP_MS)
    }

    /** A real touch on the page, for the ad skip button that ignores script clicks. */
    private fun tapPage(message: String) {
        val fraction = NativeTap.parse(message) ?: return
        // In fullscreen the player is drawn in the custom view, not the WebView.
        val view = customView ?: webView ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastPageTapAt < NativeTap.MIN_INTERVAL_MS) return
        lastPageTapAt = now
        val (x, y) = NativeTap.pointFor(fraction, view.width, view.height)
        dispatchTouch(view, now, MotionEvent.ACTION_DOWN, x, y)
        view.postDelayed({ dispatchTouch(view, now, MotionEvent.ACTION_UP, x, y) }, TAP_DURATION_MS)
    }

    private fun dispatchTouch(view: View, downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun handleMediaCommand(play: Boolean) {
        val view = webView ?: return
        // The WebView may have been paused when the app went to background
        // with nothing playing; resume it before asking the page to play.
        if (play) view.onResume()
        view.evaluateJavascript(MediaBridge.commandScript(play), null)
    }

    private fun toggleDesktopMode() {
        val view = webView ?: return
        val enabled = browserViewModel?.uiState?.value?.desktopMode != true
        preferences.edit { putBoolean(PREFERENCE_DESKTOP_MODE, enabled) }
        browserViewModel?.setDesktopMode(enabled)
        applySiteMode(view.settings, enabled)
        view.reload()
    }

    private fun applySiteMode(settings: WebSettings, desktop: Boolean) {
        settings.userAgentString = if (desktop) desktopUserAgent else mobileUserAgent
        settings.useWideViewPort = desktop
        settings.loadWithOverviewMode = desktop
    }

    // Phones (smallest width < 600dp, e.g. Galaxy S25 Ultra ~412dp) default to
    // the mobile site; the desktop site is laid out for >= 1000 CSS px and
    // renders tiny on a phone. Tablets keep the desktop site.
    private val isPhone: Boolean
        get() = resources.configuration.smallestScreenWidthDp < 600

    private fun desktopModePreference(): Boolean =
        preferences.getBoolean(PREFERENCE_DESKTOP_MODE, !isPhone)

    private fun setAutoLoginEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(PREFERENCE_AUTO_LOGIN, enabled) }
        browserViewModel?.setAutoLogin(enabled)
        browserViewModel?.showMessage(
            if (enabled) "已开启自动登录，下次启动将进入登录模式"
            else "已关闭自动登录"
        )
    }

    private fun openAuthenticatedMode(url: String) {
        try {
            val customTab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setToolbarColor(Color.rgb(16, 16, 20))
                .setNavigationBarColor(Color.rgb(11, 11, 14))
                .build()

            CustomTabsClient.getPackageName(this, null)?.let(customTab.intent::setPackage)
            customTab.launchUrl(this, url.toUri())
        } catch (_: ActivityNotFoundException) {
            openExternal(url)
        }
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

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter Picture-in-Picture when the user leaves while a video is
        // playing fullscreen — the most reliable signal that they are watching.
        if (customView != null) enterPipIfPossible()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Hide only the toolbar so PiP shows the page. The WebView lives inside
        // composeView, so hiding composeView itself would leave PiP black when
        // it was entered without a fullscreen video.
        pipMode = isInPictureInPictureMode
        // Without a fullscreen video, pin the player over the page (CSS in
        // YouTubeCleaner) so the small window shows the video, not the feed.
        val pinPlayer = isInPictureInPictureMode && customView == null
        webView?.evaluateJavascript(
            "document.documentElement.classList.toggle('padtube-pip', $pinPlayer);" +
                "window.dispatchEvent(new Event('resize'));",
            null
        )
        // Closing the PiP window stops the activity without resuming it; pause
        // playback so audio does not keep running with no visible window.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            webView?.evaluateJavascript(PAUSE_MEDIA_SCRIPT, null)
        }
        updateIdleTimer()
    }

    private fun enterFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback
    ) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback
        webView?.keepScreenOn = true
        view.setBackgroundColor(Color.BLACK)
        composeView.visibility = View.GONE
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Tablet: let the user's rotation preference decide instead of forcing
        // landscape, which flips back and forth on a hand-held tablet.
        // Phone: like the YouTube app, turn landscape videos sideways even with
        // rotation lock on, but keep vertical videos (Shorts) upright.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        if (isPhone) {
            webView?.evaluateJavascript(VIDEO_IS_PORTRAIT_SCRIPT) { portrait ->
                if (customView === view && portrait != "true") {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
        applyFullscreenWindowState()
        view.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            bringToFront()
            post {
                requestLayout()
                applyFullscreenWindowState()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyFullscreenWindowState() {
        if (customView == null) return

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen(notifyPage: Boolean = true) {
        val view = customView ?: return
        webView?.keepScreenOn = false
        root.removeView(view)
        customView = null
        composeView.visibility = View.VISIBLE
        if (notifyPage) customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        restoreWindowState()
    }

    @Suppress("DEPRECATION")
    private fun restoreWindowState() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && customView != null) {
            applyFullscreenWindowState()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (customView != null) {
            customView?.post {
                customView?.requestLayout()
                applyFullscreenWindowState()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        // Do not pause the WebView while in Picture-in-Picture or while a video
        // plays: it must keep playing in the floating window / screen locked.
        if (!isInPipMode() && !mediaPlaying) {
            webView?.onPause()
        }
        inForeground = false
        updateIdleTimer()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        inForeground = true
        updateIdleTimer()
    }

    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun onDestroy() {
        idleHandler.removeCallbacks(stopWhenIdle)
        PlaybackService.commandHandler = null
        PlaybackService.stop(this)
        customView?.let(root::removeView)
        customView = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val YOUTUBE_HOME = "https://www.youtube.com/"
        private val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com")
        private const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
        private const val PREFERENCES_NAME = "padtube_preferences"
        private const val PREFERENCE_AUTO_LOGIN = "auto_login"
        private const val PREFERENCE_DESKTOP_MODE = "desktop_mode"
        private const val VIDEO_IS_PORTRAIT_SCRIPT =
            "(() => { const v = document.querySelector('video');" +
                " return !!v && v.videoHeight > v.videoWidth; })()"
        private const val PAUSE_MEDIA_SCRIPT =
            "document.querySelectorAll('video,audio').forEach(m => m.pause());"
        private const val FALLBACK_CHROME_MAJOR = "150"
        private const val IDLE_STOP_MS = 10 * 60 * 1000L
        private const val TAP_DURATION_MS = 60L

        // Claim the same Chrome major version the installed WebView really runs,
        // so YouTube never serves a "browser not supported" page for a fake,
        // stale or future version string.
        internal fun desktopUserAgentFor(webViewUserAgent: String): String {
            val major = Regex("""Chrome/(\d+)""").find(webViewUserAgent)?.groupValues?.get(1)
                ?: FALLBACK_CHROME_MAJOR
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
        }
    }
}

@Composable
private fun PadTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = ComposeColor(0xFFFF3347),
            background = ComposeColor(0xFF0B0B0E),
            surface = ComposeColor(0xFF101014),
            onBackground = ComposeColor(0xFFF5F5F6),
            onSurface = ComposeColor(0xFFF5F5F6)
        ),
        content = content
    )
}

@Composable
private fun PadTubeScreen(
    uiState: BrowserUiState,
    showToolbar: Boolean,
    webViewGeneration: Int,
    webViewFactory: () -> WebView,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onToggleDesktop: () -> Unit,
    onLoginMode: () -> Unit,
    onLoginChrome: () -> Unit,
    onOpenExternal: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleAutoLogin: () -> Unit,
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
        if (showToolbar) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column {
                PadTubeToolbar(
                    uiState = uiState,
                    onBack = onBack,
                    onForward = onForward,
                    onHome = onHome,
                    onRefresh = onRefresh,
                    onToggleDesktop = onToggleDesktop,
                    onLoginMode = onLoginMode,
                    onLoginChrome = onLoginChrome,
                    onOpenExternal = onOpenExternal,
                    onEnterPip = onEnterPip,
                    onToggleAutoLogin = onToggleAutoLogin
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
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            key(webViewGeneration) {
                AndroidView(
                    factory = { webViewFactory() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // First-load placeholder so the user never stares at a black WebView.
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
private fun PadTubeToolbar(
    uiState: BrowserUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onToggleDesktop: () -> Unit,
    onLoginMode: () -> Unit,
    onLoginChrome: () -> Unit,
    onOpenExternal: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleAutoLogin: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp
        val expanded = maxWidth >= 900.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = ComposeColor.White, fontSize = 16.sp)
            }
            if (!compact) {
                Text(
                    text = "PadTube",
                    modifier = Modifier.padding(start = 6.dp, end = 2.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1
                )
            }

            // Primary navigation lives on the bar only when there is room.
            if (!compact) {
                ToolbarIcon(R.drawable.ic_arrow_back, "后退", uiState.canGoBack, onBack)
                if (expanded) {
                    ToolbarIcon(R.drawable.ic_arrow_forward, "前进", uiState.canGoForward, onForward)
                }
                ToolbarIcon(R.drawable.ic_home, "首页", true, onHome)
                ToolbarIcon(R.drawable.ic_refresh, "刷新", true, onRefresh)
            }

            Text(
                text = uiState.title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = ComposeColor(0xFFB9B9C2),
                fontSize = 13.sp
            )

            ToolbarIcon(R.drawable.ic_picture_in_picture, "画中画", true, onEnterPip)
            if (!compact) {
                ToolbarIcon(
                    if (uiState.desktopMode) R.drawable.ic_computer else R.drawable.ic_smartphone,
                    if (uiState.desktopMode) "切换到手机版" else "切换到电脑版",
                    true,
                    onToggleDesktop
                )
            }

            if (!uiState.loggedIn) {
                TextButton(
                    onClick = onLoginMode,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = ComposeColor.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 4.dp
                    )
                ) {
                    Text("登录", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }

            OverflowMenu(
                compact = compact,
                expanded = expanded,
                desktopMode = uiState.desktopMode,
                autoLogin = uiState.autoLogin,
                onBack = onBack,
                onForward = onForward,
                onHome = onHome,
                onRefresh = onRefresh,
                onToggleDesktop = onToggleDesktop,
                onOpenExternal = onOpenExternal,
                onLoginChrome = onLoginChrome,
                onToggleAutoLogin = onToggleAutoLogin
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    compact: Boolean,
    expanded: Boolean,
    desktopMode: Boolean,
    autoLogin: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onToggleDesktop: () -> Unit,
    onOpenExternal: () -> Unit,
    onLoginChrome: () -> Unit,
    onToggleAutoLogin: () -> Unit
) {
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
            if (compact) {
                DropdownMenuItem(text = { Text("后退") }, onClick = { open = false; onBack() })
                DropdownMenuItem(text = { Text("前进") }, onClick = { open = false; onForward() })
                DropdownMenuItem(text = { Text("首页") }, onClick = { open = false; onHome() })
                DropdownMenuItem(text = { Text("刷新") }, onClick = { open = false; onRefresh() })
                DropdownMenuItem(
                    text = { Text(if (desktopMode) "切换到手机版" else "切换到电脑版") },
                    onClick = { open = false; onToggleDesktop() }
                )
            } else if (!expanded) {
                DropdownMenuItem(text = { Text("前进") }, onClick = { open = false; onForward() })
            }
            DropdownMenuItem(
                text = { Text("用 Chrome 登录（备用）") },
                onClick = { open = false; onLoginChrome() }
            )
            DropdownMenuItem(
                text = { Text("在浏览器打开") },
                onClick = { open = false; onOpenExternal() }
            )
            DropdownMenuItem(
                text = { Text(if (autoLogin) "自动登录：开" else "自动登录：关") },
                onClick = { open = false; onToggleAutoLogin() }
            )
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
            Text("▶", color = ComposeColor.White, fontSize = 26.sp)
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 32.dp, vertical = 10.dp
            )
        ) {
            Text("重试", fontWeight = FontWeight.SemiBold)
        }
    }
}
