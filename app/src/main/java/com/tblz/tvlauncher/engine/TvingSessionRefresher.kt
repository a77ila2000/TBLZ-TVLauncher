package com.tblz.tvlauncher.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Renews TVING's short-lived `accessToken` the way tving.com itself does.
 *
 * The web session is a long-lived `_tving_token` + `refreshToken` pair from the original login,
 * plus an `accessToken` that the site's own JS re-issues from refreshToken whenever it finds the
 * current one expired. The launcher copied accessToken once and reused it until it died; after
 * that every play failed with "토큰 만료", and opening the login window only re-imported the same
 * dead cookie. Loading tving.com in an offscreen WebView lets the site renew it.
 */
object TvingSessionRefresher {
    private const val TAG = "TvingRefresh"
    private const val SITE_URL = "https://www.tving.com/"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 20_000L
    /** Once the page has loaded, how long to wait for the site's renewal before giving up. */
    private const val SETTLE_MS = 6_000L
    private const val POLL_MS = 500L
    /** A renewal that just failed is not repeated while the user zaps through channels. */
    private const val FAILED_COOLDOWN_MS = 60_000L

    private val mutex = Mutex()
    private var lastFailedAt = 0L

    /**
     * True when tving.com now holds an accessToken different from [staleAccessToken] -- renewed
     * here, or already by a concurrent call. Token values are never logged.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun refresh(context: Context, staleAccessToken: String): Boolean = mutex.withLock {
        withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance()
            if (accessToken(cookies).let { it.isNotEmpty() && it != staleAccessToken }) {
                return@withContext true
            }
            if (lastFailedAt != 0L && SystemClock.elapsedRealtime() - lastFailedAt < FAILED_COOLDOWN_MS) {
                return@withContext false
            }

            val started = SystemClock.elapsedRealtime()
            var pageLoadedAt = 0L
            val webView = WebView(context.applicationContext)
            try {
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.blockNetworkImage = true
                webView.settings.userAgentString = USER_AGENT
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(webView, true)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (pageLoadedAt == 0L) pageLoadedAt = SystemClock.elapsedRealtime()
                    }
                }
                webView.loadUrl(SITE_URL)

                while (SystemClock.elapsedRealtime() - started < TIMEOUT_MS) {
                    delay(POLL_MS)
                    val current = accessToken(cookies)
                    if (current.isNotEmpty() && current != staleAccessToken) {
                        cookies.flush()
                        lastFailedAt = 0L
                        Log.i(TAG, "accessToken renewed in ${SystemClock.elapsedRealtime() - started}ms")
                        return@withContext true
                    }
                    if (pageLoadedAt != 0L && SystemClock.elapsedRealtime() - pageLoadedAt > SETTLE_MS) break
                }
                lastFailedAt = SystemClock.elapsedRealtime()
                Log.w(TAG, "accessToken not renewed (pageLoaded=${pageLoadedAt != 0L})")
                false
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private fun accessToken(cookies: CookieManager): String =
        Regex("(?:^|;\\s*)accessToken=([^;]+)")
            .find(cookies.getCookie(SITE_URL).orEmpty())?.groupValues?.get(1)?.trim().orEmpty()
}
