package org.objectiveliberty.askani

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val startUrl = "https://objectiveliberty-ani.hf.space/"

    /**
     * JS bridge object exposed to the page as `window.AniAndroid`.
     *
     * Your Gradio JS should call:
     *   window.AniAndroid.bankToDownloads(md, html, baseName, projectSafe)
     *
     * This writes:
     *   Downloads/AnI/Projects/<Project>/Sessions/<base>.md
     *   Downloads/AnI/Projects/<Project>/Exports/<base>.html
     */
    class AniBridge(private val context: Context) {

        private fun safeName(s: String, fallback: String): String {
            val trimmed = s.trim()
            if (trimmed.isEmpty()) return fallback
            // allow letters/numbers/dot/_/- only, replace the rest with '-'
            return trimmed.lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .ifEmpty { fallback }
                .take(80)
        }

        @JavascriptInterface
        fun bankToDownloads(mdText: String, htmlText: String, baseName: String, projectSafe: String): String {
            return try {
                val proj = safeName(projectSafe, "General")
                val base = safeName(baseName, "ani-session")

                // Public downloads dir
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

                val root = File(downloads, "AnI/Projects/$proj")
                val sessionsDir = File(root, "Sessions")
                val exportsDir = File(root, "Exports")

                if (!sessionsDir.exists()) sessionsDir.mkdirs()
                if (!exportsDir.exists()) exportsDir.mkdirs()

                val mdFile = File(sessionsDir, "$base.md")
                val htmlFile = File(exportsDir, "$base.html")

                mdFile.writeText(mdText ?: "", Charsets.UTF_8)
                htmlFile.writeText(htmlText ?: "", Charsets.UTF_8)

                "Saved ✓  (Downloads/AnI/Projects/$proj/Sessions/$base.md)"
            } catch (e: Exception) {
                "Vault: Android save failed (${e.javaClass.simpleName}). Use Advanced → Export / Print."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Prevent ugly/inverted WebView "force dark" behavior
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(ws, WebSettingsCompat.FORCE_DARK_OFF)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()

        // IMPORTANT: Bridge name must match your Gradio JS detection
        // JS expects window.AniAndroid.bankToDownloads(...)
        webView.addJavascriptInterface(AniBridge(this), "AniAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Keep all links inside the app
                return false
            }
        }

        // Make downloads work (Advanced -> Export links / generated files)
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val uri = Uri.parse(url)
                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)

                val request = DownloadManager.Request(uri)
                request.setMimeType(mimetype)
                request.addRequestHeader("User-Agent", userAgent)

                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null) request.addRequestHeader("Cookie", cookies)

                request.setTitle(filename)
                request.setDescription("Downloading…")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            } catch (_: Exception) {
                // no-op
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
