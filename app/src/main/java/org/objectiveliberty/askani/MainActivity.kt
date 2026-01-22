package org.objectiveliberty.askani

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import org.json.JSONArray
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val startUrl = "https://objectiveliberty-ani.hf.space/"

    class AniBridge(private val context: Context) {

        private fun safeName(s: String?, fallback: String): String {
            val trimmed = (s ?: "").trim()
            if (trimmed.isEmpty()) return fallback
            return trimmed.lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .ifEmpty { fallback }
                .take(80)
        }

        private fun writeToDownloads(relativeDir: String, displayName: String, mime: String, text: String): Boolean {
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            }

            val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, values) ?: return false

            var out: OutputStream? = null
            return try {
                out = resolver.openOutputStream(itemUri)
                if (out == null) return false
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } catch (_: Exception) {
                false
            } finally {
                try { out?.close() } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun bankSession(mdText: String?, htmlText: String?, baseName: String?, projectSafe: String?): String {
            return bankInternal(mdText, htmlText, baseName, projectSafe)
        }

        @JavascriptInterface
        fun bankToDownloads(mdText: String?, htmlText: String?, baseName: String?, projectSafe: String?): String {
            return bankInternal(mdText, htmlText, baseName, projectSafe)
        }

        private fun bankInternal(mdText: String?, htmlText: String?, baseName: String?, projectSafe: String?): String {
            return try {
                val proj = safeName(projectSafe, "General")
                val base = safeName(baseName, "ani-session")

                // Relative to Downloads/
                val sessionsRel = "Download/AnI/Projects/$proj/Sessions"
                val exportsRel  = "Download/AnI/Projects/$proj/Exports"

                val okMd = writeToDownloads(
                    relativeDir = sessionsRel,
                    displayName = "$base.md",
                    mime = "text/markdown",
                    text = mdText ?: ""
                )

                val okHtml = writeToDownloads(
                    relativeDir = exportsRel,
                    displayName = "$base.html",
                    mime = "text/html",
                    text = htmlText ?: ""
                )

                if (okMd && okHtml) {
                    "Saved ✓ (Downloads/AnI/Projects/$proj/Sessions/$base.md)"
                } else {
                    "Vault: Android save failed (couldn't write to Downloads). Try Advanced → Export / Print."
                }
            } catch (e: Exception) {
                "Vault: Android save failed (${e.javaClass.simpleName}). Use Advanced → Export / Print."
            }
        }

        /**
         * Return JSON array of project folder names detected in Downloads/AnI/Projects/...
         *
         * We infer project names by scanning Download entries where RELATIVE_PATH contains:
         *   Download/AnI/Projects/<Project>/
         */
        @JavascriptInterface
        fun listProjects(): String {
            return try {
                val resolver = context.contentResolver
                val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                val projection = arrayOf(
                    MediaStore.Downloads.RELATIVE_PATH
                )

                val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
                val args = arrayOf("Download/AnI/Projects/%")

                val projects = linkedSetOf<String>()

                resolver.query(uri, projection, selection, args, null).use { cursor: Cursor? ->
                    if (cursor != null) {
                        val idx = cursor.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
                        while (cursor.moveToNext()) {
                            val rel = cursor.getString(idx) ?: continue
                            // rel like: "Download/AnI/Projects/<proj>/Sessions/"
                            val marker = "Download/AnI/Projects/"
                            val start = rel.indexOf(marker)
                            if (start >= 0) {
                                val rest = rel.substring(start + marker.length)
                                val slash = rest.indexOf('/')
                                if (slash > 0) {
                                    val proj = rest.substring(0, slash).trim()
                                    if (proj.isNotEmpty()) projects.add(proj)
                                }
                            }
                        }
                    }
                }

                // Ensure General is present first
                val out = JSONArray()
                if (!projects.contains("General")) out.put("General")
                projects.forEach { out.put(it) }
                out.toString()
            } catch (_: Exception) {
                "[]"
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

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(ws, WebSettingsCompat.FORCE_DARK_OFF)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()

        val bridge = AniBridge(this)
        webView.addJavascriptInterface(bridge, "Android")
        webView.addJavascriptInterface(bridge, "AniAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

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
