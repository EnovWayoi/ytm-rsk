package dev.enov.ytm

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AccountActivity : Activity() {
    private var browser: WebView? = null
    private lateinit var status: TextView
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 10, 14)); fitsSystemWindows = true }
        status = TextView(this).apply { text = "Sign in on Google's page. Your password is entered only on Google. If Google refuses this embedded browser, account features cannot be enabled on this device."; setTextColor(Color.WHITE); setPadding(24, 20, 24, 16) }
        layout.addView(status)
        val finish = Button(this).apply { text = "Finish sign-in"; setOnClickListener { capture() } }
        layout.addView(finish)
        val web = WebView(this)
        browser = web
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = MusicRepository.WEB_UA; allowFileAccess = false; allowContentAccess = false; setSupportMultipleWindows(false) }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https" || !allowedHost(uri.host.orEmpty())
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (Uri.parse(url).host == "music.youtube.com") capture(quiet = true)
                if (url.contains("deniedsigninrejected") || url.contains("disallowed_useragent")) status.text = "Google refused sign-in in this embedded browser. You can continue using the app as a guest; no account was saved."
            }
        }
        layout.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F")
    }
    private fun allowedHost(host: String) = listOf("google.com", "youtube.com", "googleusercontent.com", "gstatic.com").any { host == it || host.endsWith(".$it") }
    private fun capture(quiet: Boolean = false) {
        val manager = CookieManager.getInstance()
        val raw = listOf("https://music.youtube.com", "https://www.youtube.com", "https://youtube.com").flatMap { manager.getCookie(it).orEmpty().split(';') }.map(String::trim).filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (!raw.containsKey("SAPISID") && !raw.containsKey("__Secure-3PAPISID")) { if (!quiet) Toast.makeText(this, "Finish signing in on Google's page first", Toast.LENGTH_LONG).show(); return }
        try {
            SecureSession(this).cookie = raw.entries.joinToString("; ") { "${it.key}=${it.value}" }
            manager.removeAllCookies(null); manager.flush()
            setResult(RESULT_OK); finish()
        } catch (_: Exception) { status.text = "The Google session could not be stored securely. Please try again." }
    }
    override fun onDestroy() { browser?.apply { stopLoading(); destroy() }; browser = null; super.onDestroy() }
}
