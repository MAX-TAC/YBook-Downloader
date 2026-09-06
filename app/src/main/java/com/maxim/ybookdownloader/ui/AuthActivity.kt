package com.maxim.ybookdownloader.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.maxim.ybookdownloader.security.TokenStore

class AuthActivity : Activity() {
    private val clientId = "4483e97bab6e486a9822973109a14d05"
    private val callbackHost = "yx4483e97bab6e486a9822973109a14d05.oauth.yandex.ru"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (handleCallback(url)) return true
                return false
            }
            override fun onPageFinished(view: WebView, url: String) { handleCallback(url) }
        }
        setContentView(web)
        web.loadUrl("https://oauth.yandex.ru/authorize?response_type=token&client_id=$clientId")
    }

    private fun handleCallback(url: String): Boolean {
        if (!url.contains(callbackHost)) return false
        val fragment = url.substringAfter('#', "")
        val token = fragment.split('&').firstOrNull { it.startsWith("access_token=") }
            ?.substringAfter('=')
        if (!token.isNullOrBlank()) {
            TokenStore(this).saveToken(java.net.URLDecoder.decode(token, "UTF-8"))
            setResult(RESULT_OK)
            finish()
            return true
        }
        return false
    }
}
