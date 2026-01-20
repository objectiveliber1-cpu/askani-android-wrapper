package org.objectiveliberty.askani

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Your HF Space
    private val startUrl = "https://objectiveliberty-ani.hf.space/"

    /**
     * JS bridge exposed to the page as `window.AniAndroid`.
     * Used by your Gradio VAULT_JS to save "Bank it" exports to:
     *   Downloads/AnI/Projects/<Project>/Sessions/*.md
     *   Downloads/AnI/Projects/<Project>/Exports/*.html
     */
    private inner class AniBridge(private val ctx: Context) {

        @JavascriptInterface
        fun bankToDownloads(
            mdText: String?,
            htmlText: String?,
            baseName: String?,
            projectSafe: String?
        ): String {
            return try {
                val proj = (projectSafe ?: "General").ifBlank { "General" }
                val base = (baseName ?: "ani-session").ifBlank { "ani-session" }

                val relBase = "AnI/Projects/$proj"
                val sessionsRel = "$relBase/Sessions/"
                val exportsRel = "$relBase/Exports/"

                val mdOk = writeTextToDownloads(
                    relativePathUnderDownloads = sessionsRel,
                    fileName = "$base.md",
                    mimeType = "text/markdown",
                    text = mdText ?: ""
                )

                val htmlOk = writeTextToDownloads(
                    relativePathUnderDownloads = exportsRel,
                    fileName = "$base.html",
                    mimeType = "text/html",
                    text = htmlText ?: ""
                )

                if (mdOk && htmlOk) {
                    "Saved ✓ Downloads/$relBase/"
                } else if (mdOk || htmlOk) {
                    "Saved partially ✓ (one file failed). Try again."
                } else {
                    "Save failed. Use Advanced → Export / Print."
                }
            } catch (e: Exception) {
                "Save failed: ${e.javaClass.simpleName}. Use Advanced → Export / Print."
            }
        }

        private fun writeTextToDownloads(
            relativePathUnderDownloads: String,
            fileName: String,
            mimeType: String,
            text: String
        ): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (scoped storage): use MediaStore Downloads
                val resolver = ctx.contentResolver

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    // This is relative to the Downloads collection root
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + relativePathUnderDownloads
                    )
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false

                resolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                        w.write(text)
                    }
                } ?: return false

                true
            } else {
                // Android 9 and below: best-effort legacy write
                // NOTE: May require WRITE_EXTERNAL_STORAGE permission on older devices.
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, relativePathUnderDownloads)
                if (!targetDir.exists()) targetDir.mkdirs()

                val outFile = File(targetDir, fileName)
                FileOutputStream(outFile).use { fos ->
                    fos.write(text.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }

                // Make it visible in file managers
                MediaScannerConnection.scanFile(
                    ctx,
                    arrayOf(outFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )

                true
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

        // Prevent Android WebView dark-mode overrides (avoids white-on-white / inversion issues)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(ws, WebSettingsCompat.FORCE_DARK_OFF)
        }

        // Cookies for HF space + any signed download URLs
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // ✅ JS bridge for Bank It -> Downloads/AnI/...
        webView.addJavascriptInterface(AniBridge(this), "AniAndroid")

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Keep everything inside the app (including HF file links)
                return false
            }
        }

        // Make downloads work (Advanced -> Export links / files, etc.)
        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
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
            }
        )

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
