/*
 * Copyright (C) 2026 NeuroTube | A-EDev
 *
 * This file is part of NeuroTube (https://github.com/NETO-DEV66/NeuroTube).
 *
 * NeuroTube is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package com.neurotube.app.account

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.neurotube.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in WebView for YouTube.
 *
 * Google's own sign-in page is loaded directly, with YouTube as the `continue` target. That is the
 * only entry point Google completes inside a WebView: loading `youtube.com` and tapping its own
 * "Sign in" button instead hands the account chooser to a popup window, which a WebView with
 * multiple windows off silently drops, so the flow never finishes.
 *
 * Navigation is deliberately unrestricted. Google bounces the user through `accounts.google.com`,
 * `accounts.youtube.com` and `consent.youtube.com` before returning a YouTube page; an allow-list
 * that rejects any one of those hops strands the user mid-flow with no error.
 *
 * Once a YouTube page carries a session, the cookies and the page's own visitor identity are lifted
 * out and handed to [YouTubeLoginBroker]. The jar is wiped on every exit path — success, cancel, or
 * failure — so the credential exists only in the encrypted store the repository owns.
 *
 * Capture is a bounded poll rather than a single test. Google sets the session cookies across a
 * redirect chain, so the jar is not necessarily complete on the page whose `onPageFinished` fired,
 * and the page's visitor identity is answered asynchronously by JavaScript. Testing once and waiting
 * a fixed moment reported a failure for a session that simply had not landed yet, which is why a
 * second or third attempt succeeded where the first did not.
 */
class YouTubeLoginActivity : ComponentActivity() {
    private var completed = false
    private var cleanupStarted = false
    private lateinit var loginWebView: WebView

    /** Read back from the page's own JS, which answers after [WebViewClient.onPageFinished] returns. */
    @Volatile
    private var capturedVisitorData: String? = null

    @Volatile
    private var capturedDataSyncId: String? = null

    /** Set once a poll has run, so overlapping page loads do not start a second one. */
    private var capturePolling = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        loginWebView =
            WebView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // Google refuses to complete sign-in inside a WebView that announces itself as one.
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            super.onPageFinished(view, url)
                            captureWhenAuthenticated(view, url)
                        }
                    }
            }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(loginWebView, true)
        }

        setContentView(loginWebView)

        // `removeAllCookies` is asynchronous, so a half-finished attempt must be gone *before* the
        // first page is requested. Loading immediately raced the wipe and could resume the previous
        // attempt's jar, which is another reason attempts behaved differently from one another.
        CookieManager.getInstance().removeAllCookies {
            if (completed || isFinishing || isDestroyed) return@removeAllCookies
            CookieManager.getInstance().flush()
            loginWebView.loadUrl(SIGN_IN_URL)
        }
    }

    override fun onDestroy() {
        if (!completed && isFinishing) {
            YouTubeLoginBroker.fail(getString(R.string.youtube_login_error_cancelled))
        }
        clearLoginSession()
        super.onDestroy()
    }

    /**
     * A session only exists once Google has sent the user on to a YouTube page with its cookies set.
     *
     * The jar is polled until it holds a session, because the cookies land across a redirect chain
     * and are not necessarily all present on the page whose `onPageFinished` fired. Once a session is
     * there it is given a short grace period for the page's visitor identity, which is answered by
     * JavaScript and is what InnerTube validates the request context against; if the page never
     * reports one the session is captured anyway rather than stranding the attempt.
     *
     * A page that never yields a session leaves the poll to expire; the user is still in the WebView
     * and can continue, and the next page load starts a fresh poll.
     */
    private fun captureWhenAuthenticated(
        webView: WebView,
        url: String,
    ) {
        if (completed || capturePolling || !SignInCapture.isYouTubeUrl(url)) return

        capturePolling = true
        readSessionIdentity(webView)
        lifecycleScope.launch {
            try {
                val deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS
                var sessionSeenAt = 0L
                var nextIdentityRead = System.currentTimeMillis() + IDENTITY_REREAD_INTERVAL_MS
                while (!completed && System.currentTimeMillis() < deadline) {
                    delay(CAPTURE_POLL_INTERVAL_MS)
                    if (completed || isFinishing || isDestroyed) return@launch
                    val now = System.currentTimeMillis()

                    val header = YouTubeCookieExtractor.toCookieHeader(youtubeCookieJar())
                    if (header == null || !YouTubeCookieExtractor.hasSession(header)) continue
                    if (sessionSeenAt == 0L) sessionSeenAt = now

                    // The identity is answered by JavaScript and InnerTube validates the request
                    // context against it, so a session that arrives before its identity waits a
                    // moment for it — but not forever, or a page that never reports one would strand
                    // the capture. A session without an identity is still worth completing.
                    if (capturedVisitorData == null && now - sessionSeenAt < IDENTITY_GRACE_MS) {
                        if (now >= nextIdentityRead) {
                            nextIdentityRead = now + IDENTITY_REREAD_INTERVAL_MS
                            readSessionIdentity(webView)
                        }
                        continue
                    }

                    completed = true
                    YouTubeLoginBroker.complete(
                        YouTubeLoginSession(
                            cookieHeader = header,
                            visitorData = capturedVisitorData,
                            dataSyncId = capturedDataSyncId,
                        ),
                    )
                    clearLoginSession(onComplete = ::finish)
                    return@launch
                }
            } finally {
                capturePolling = false
            }
        }
    }

    /** Both values live on the page's own `window.yt.config_`; either may legitimately be absent. */
    private fun readSessionIdentity(webView: WebView) {
        webView.evaluateJavascript(JS_READ_VISITOR_DATA) { raw -> capturedVisitorData = parseJsValue(raw) }
        webView.evaluateJavascript(JS_READ_DATA_SYNC_ID) { raw ->
            capturedDataSyncId = YouTubeCookieExtractor.normalizeDataSyncId(parseJsValue(raw))
        }
    }

    /** [WebView.evaluateJavascript] answers with a JSON literal, so a string arrives quoted. */
    private fun parseJsValue(raw: String?): String? =
        raw?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" }

    /** Every origin the session is set on, so a cookie one host drops is still picked up by another. */
    private fun youtubeCookieJar(): String =
        SignInCapture.YOUTUBE_ORIGINS
            .mapNotNull { origin -> CookieManager.getInstance().getCookie("https://$origin") }
            .joinToString("; ")

    private fun clearLoginSession(onComplete: (() -> Unit)? = null) {
        if (cleanupStarted) {
            onComplete?.invoke()
            return
        }
        cleanupStarted = true

        SignInCapture.GOOGLE_ORIGINS.forEach { WebStorage.getInstance().deleteOrigin("https://$it") }
        if (::loginWebView.isInitialized) {
            loginWebView.stopLoading()
            loginWebView.clearFormData()
            loginWebView.clearHistory()
        }
        clearGoogleCookies {
            if (::loginWebView.isInitialized) {
                loginWebView.removeAllViews()
                loginWebView.destroy()
            }
            YouTubeLoginBroker.released()
            onComplete?.invoke()
        }
    }

    /**
     * Drops the whole jar once the WebView is done with it.
     *
     * This used to expire cookies by name with `name=; Max-Age=0; Path=/; Secure`, which does not
     * remove a domain-scoped cookie such as `.youtube.com` — it writes a *host-only* cookie next to
     * it and leaves the session in place. The remaining session then leaked into the next attempt,
     * so a retry did not start from a clean jar. Nothing in this activity's jar is worth keeping:
     * the WebView is destroyed immediately afterwards and the real session lives in the encrypted
     * account store.
     */
    private fun clearGoogleCookies(onComplete: () -> Unit) {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies {
            manager.flush()
            onComplete()
        }
    }

    private companion object {
        const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

        /** How long a single page load is given to produce a session before the poll stands down. */
        const val CAPTURE_TIMEOUT_MS = 8_000L
        const val CAPTURE_POLL_INTERVAL_MS = 200L

        /** The visitor identity is answered by JS, so it is re-asked while it is still missing. */
        const val IDENTITY_REREAD_INTERVAL_MS = 1_000L

        /** How long a session waits for its visitor identity before being captured without one. */
        const val IDENTITY_GRACE_MS = 2_000L

        const val JS_READ_VISITOR_DATA =
            "(function(){try{return (window.yt&&window.yt.config_&&window.yt.config_.VISITOR_DATA)||null}" +
                "catch(e){return null}})()"
        const val JS_READ_DATA_SYNC_ID =
            "(function(){try{return (window.yt&&window.yt.config_&&window.yt.config_.DATASYNC_ID)||null}" +
                "catch(e){return null}})()"
    }
}
