package com.tblz.tvlauncher.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.tblz.tvlauncher.databinding.DialogTvingLoginBinding
import com.tblz.tvlauncher.engine.ConfigStore

class TvingLoginDialog(
    context: Context,
    private val initialProvider: String? = null,
    private val onLoginSuccess: () -> Unit
) : Dialog(context) {

    companion object {
        private const val TAG = "TvingLoginDialog"
        private const val URL_TVING = "https://www.tving.com/account/login?returnUrl=https://www.tving.com/&home_type=NAV&trigger=login"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        /**
         * A still-valid session needs no renewal: accept it once tving.com has shown a non-login
         * page for this long without issuing a new accessToken.
         */
        private const val SESSION_SETTLE_MS = 4_000L
    }

    private lateinit var binding: DialogTvingLoginBinding
    private var isLoginCaptured = false
    private var accessTokenAtOpen = ""
    private var pageLoaded = false
    private var settledSince = 0L
    private var pendingProvider: String? = null
    private var providerClickAttempts = 0

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            checkCookies()
            if (!isLoginCaptured) {
                pollHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogTvingLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.94).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.94).toInt()
        )

        binding.btnClose.setOnClickListener { dismiss() }

        binding.btnMethodTving.setOnClickListener { openLogin() }
        binding.btnMethodCj.setOnClickListener { openLogin("CJ ONE") }
        binding.btnMethodNaver.setOnClickListener { openLogin("네이버") }
        binding.btnMethodKakao.setOnClickListener { openLogin("카카오") }
        binding.btnMethodApple.setOnClickListener { openLogin("Apple") }

        isLoginCaptured = false

        setupWebView()

        // Always let tving.com load before taking its cookies. A session that merely exists can
        // hold an expired accessToken; capturing it right away (as before) re-imported the dead
        // token, so this window said "로그인 성공" while playback kept saying "토큰 만료". The
        // loaded site renews accessToken itself. Cookies are still cleared only by explicit logout.
        accessTokenAtOpen = cookieValue(tvingCookies(), "accessToken")
        openLogin(initialProvider)
        pollHandler.postDelayed(pollRunnable, 1000)
    }

    private fun openLogin(provider: String? = null) {
        pendingProvider = provider
        providerClickAttempts = 0
        binding.webView.loadUrl(URL_TVING)
    }

    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = USER_AGENT

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                checkCookies()
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url.orEmpty()
                pageLoaded = true
                checkCookies()
                clickPendingProvider(view, currentUrl)
            }
        }

        // Support popup windows (e.g. carrier PASS identity verification, SNS popup)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val popupWebView = WebView(context).apply {
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    this.settings.setSupportMultipleWindows(true)
                    this.settings.javaScriptCanOpenWindowsAutomatically = true
                    this.settings.userAgentString = USER_AGENT
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }

                val popupDialog = Dialog(context).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setContentView(popupWebView, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    window?.setLayout(
                        (context.resources.displayMetrics.widthPixels * 0.88).toInt(),
                        (context.resources.displayMetrics.heightPixels * 0.88).toInt()
                    )
                }

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        popupDialog.dismiss()
                    }
                }

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, url: String?) {
                        checkCookies()
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                popupDialog.show()
                return true
            }
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
    }

    private fun tvingCookies(): String {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val c1 = cookieManager.getCookie("https://www.tving.com/") ?: ""
        val c2 = cookieManager.getCookie("https://user.tving.com/") ?: ""
        val c3 = cookieManager.getCookie("https://api.tving.com/") ?: ""
        return listOf(c1, c2, c3).filter { it.isNotEmpty() }.joinToString("; ")
    }

    private fun cookieValue(cookies: String, name: String): String =
        Regex("(?:^|;\\s*)${Regex.escape(name)}=([^;]+)").find(cookies)?.groupValues?.get(1)?.trim().orEmpty()

    private fun checkCookies(): Boolean {
        if (isLoginCaptured) return true

        val combined = tvingCookies()

        // Accept the session only when the site has vouched for it: a new accessToken was issued
        // (fresh login or renewal), or tving.com itself is showing a non-login page -- i.e. the
        // session is valid as is. Third-party SNS login pages do not count.
        val now = SystemClock.elapsedRealtime()
        val url = binding.webView.url.orEmpty()
        val onTvingContent = pageLoaded && url.startsWith("https://www.tving.com") && !url.contains("/account/login")
        settledSince = if (!onTvingContent) 0L else if (settledSince == 0L) now else settledSince
        val accessToken = cookieValue(combined, "accessToken")
        val renewed = accessToken.isNotEmpty() && accessToken != accessTokenAtOpen
        val settled = settledSince != 0L && now - settledSince >= SESSION_SETTLE_MS
        if (!renewed && !settled) return false

        if (ConfigStore.importTvingCookies(combined)) {
            isLoginCaptured = true
            pollHandler.removeCallbacksAndMessages(null)
            Log.d(TAG, "TVING session captured successfully.")
            Toast.makeText(context, "TVING 로그인 성공! 재생 인증이 연동되었습니다.", Toast.LENGTH_SHORT).show()
            onLoginSuccess()
            dismiss()
            return true
        }
        return false
    }

    private fun clickPendingProvider(view: WebView?, url: String) {
        val provider = pendingProvider ?: return
        if (view == null || !url.contains("/account/login")) return
        if (providerClickAttempts++ >= 6) {
            pendingProvider = null
            Toast.makeText(context, "$provider 항목을 찾지 못했습니다. 화면에서 직접 선택해 주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val script = """
            (() => {
              const target = '$provider';
              const item = [...document.querySelectorAll('button,a,span,div')]
                .find(element => (element.textContent || '').trim() === target || (element.textContent || '').includes(target));
              if (!item) return false;
              item.click();
              return true;
            })();
        """.trimIndent()

        view.evaluateJavascript(script) { clicked ->
            if (clicked == "true") {
                pendingProvider = null
            } else {
                view.postDelayed({ clickPendingProvider(view, url) }, 700)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        pollHandler.removeCallbacksAndMessages(null)
    }
}
