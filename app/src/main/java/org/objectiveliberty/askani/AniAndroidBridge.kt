package org.objectiveliberty.askani

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val startUrl = "https://objectiveliberty-ani.hf.space/"

    private val prefs by lazy { getSharedPreferences("ani_vault", Context.MODE_PRIVATE) }

    // Persisted SAF tree URI (string)
    private fun getVaultTreeUri(): Uri? {
        val s = prefs.getString("vault_tree_uri", null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    private fun setVaultTreeUri(uri: Uri?) {
        if (uri == null) {
            prefs.edit().remove("vault_tree_uri").apply()
        } else {
            prefs.edit().putString("vault_tree_uri", uri.toString()).apply()
        }
    }

    // Folder picker (SAF)
    private val openTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            // User cancelled
            notifyWeb("window.aniOnVaultAccessResult && window.aniOnVaultAccessResult(false);")
            return@registerForActivityResult
        }

        try {
            // Persist permission
            val flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)

            setVaultTreeUri(uri)
            notifyWeb("window.aniOnVaultAccessResult && window.aniOnVaultAccessResult(true);")
        } catch (_: Exception) {
            notifyWeb("window.aniOnVaultAccessResult && window.aniOnVaultAccessResult(false);")
        }
    }

    private fun notifyWeb(js: String) {
        try {
            if (this::webView.isInitialized) {
                webView.post { webView.evaluateJavascript(js, null) }
            }
        } catch (_: Exception) { /* no-op */ }
    }

    /**
     * Resolve the "Projects" directory under the granted tree.
     *
     * We accept any of these user picks:
     *  - Downloads/
     *  - Downloads/AnI/
     *  - Downloads/AnI/Projects/
     *  - Any folder that already IS "Projects"
     */
    private fun resolveProjectsDirFromTree(tree: DocumentFile): DocumentFile? {
        fun findChildDir(parent: DocumentFile, name: String): DocumentFile? {
            return parent.listFiles().firstOrNull { it.isDirectory && it.name == name }
        }

        // If user picked Projects directly:
        if (tree.name == "Projects") return tree

        // If user picked AnI directly:
        if (tree.name == "AnI") {
            val p = findChildDir(tree, "Projects")
            return p ?: tree.createDirectory("Projects")
        }

        // Otherwise: look for AnI/Projects under the selected root
        val ani = findChildDir(tree, "AnI") ?: tree.createDirectory("AnI") ?: return null
        val projects = findChildDir(ani, "Projects") ?: ani.createDirectory("Projects")
        return projects
    }

    private fun ensureProjectFolders(projectsDir: DocumentFile, proj: String): Pair<DocumentFile?, DocumentFile?> {
        fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
            val existing = parent.listFiles().firstOrNull { it.isDirectory && it.name == name }
            return existing ?: parent.createDirectory(name)
        }

        val projDir = findOrCreateDir(projectsDir, proj) ?: return Pair(null, null)
        val sessionsDir = findOrCreateDir(projDir, "Sessions") ?: return Pair(null, null)
        val exportsDir = findOrCreateDir(projDir, "Exports") ?: return Pair(null, null)
        return Pair(sessionsDir, exportsDir)
    }

    private fun writeTextDocFile(dir: DocumentFile, filename: String, mime: String, text: String): Boolean {
        // Delete existing same-name file to avoid duplicates
        dir.listFiles().firstOrNull { it.isFile && it.name == filename }?.delete()

        val file = dir.createFile(mime, filename) ?: return false
        var out: OutputStream? = null
        return try {
            out = contentResolver.openOutputStream(file.uri, "w")
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

    /**
     * Fallback writer (MediaStore Downloads). Useful before SAF permission is granted.
     */
    private fun writeToDownloads(relativeDir: String, displayName: String, mime: String, text: String): Boolean {
        val resolver = contentResolver

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

    inner class AniBridge {

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

        // JS: open SAF picker
        @JavascriptInterface
        fun requestVaultAccess(): String {
            return try {
                openTreeLauncher.launch(null)
                "Opening folder picker…"
            } catch (_: Exception) {
                "Could not open folder picker."
            }
        }

        @JavascriptInterface
        fun clearVaultAccess(): String {
            setVaultTreeUri(null)
            return "Vault access cleared."
        }

        @JavascriptInterface
        fun vaultStatus(): String {
            val uri = getVaultTreeUri()
            return if (uri == null) {
                "Vault: not granted"
            } else {
                "Vault: granted ✓"
            }
        }

        /**
         * List projects reliably using SAF tree (if granted).
         * Returns JSON array string: ["General", "proj1", ...]
         */
        @JavascriptInterface
        fun listProjects(): String {
            return try {
                val out = JSONArray()
                out.put("General")

                val treeUri = getVaultTreeUri() ?: return out.toString()
                val tree = DocumentFile.fromTreeUri(this@MainActivity, treeUri) ?: return out.toString()
                val projectsDir = resolveProjectsDirFromTree(tree) ?: return out.toString()

                val names = projectsDir.listFiles()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .mapNotNull { it.name }
                    .distinct()
                    .sorted()

                for (n in names) {
                    if (n != "General") out.put(n)
                }
                out.toString()
            } catch (_: Exception) {
                JSONArray().put("General").toString()
            }
        }

        /**
         * Banking entrypoints (compatible with your JS):
         * - window.Android.bankSession(...)
         * - window.AniAndroid.bankToDownloads(...)
         *
         * If SAF is granted: writes into the SAF tree:
         *   <picked>/AnI/Projects/<proj>/Sessions/<base>.md
         *   <picked>/AnI/Projects/<proj>/Exports/<base>.html
         *
         * Else: falls back to MediaStore Downloads/AnI/Projects/...
         */
        @JavascriptInterface
        fun bankSession(mdText: String?, htmlText: String?, baseName: String?, projectSafe: String?): String {
            return bankInternal(mdText, htmlText, baseName, projectSafe)
        }

        @JavascriptInterface
        fun bankToDownloads(mdText: String?, htmlText: String?, baseName: String?, projectSafe: String?): String {
            return bankInternal(mdText, htmlText, baseName, projectSafe)
        }

        private fun bankInternal(mdText: String?, htmlText: String?, baseName: String?, projectSafe: String?): String {
            val proj = safeName(projectSafe, "General")
            val base = safeName(baseName, "ani-session")

            // Preferred: SAF tree
            val treeUri = getVaultTreeUri()
            if (treeUri != null) {
                try {
                    val tree = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                        ?: return "Vault: granted but unavailable. Re-grant access."

                    val projectsDir = resolveProjectsDirFromTree(tree)
                        ?: return "Vault: could not create Projects folder. Re-grant access."

                    val (sessionsDir, exportsDir) = ensureProjectFolders(projectsDir, proj)
                    if (sessionsDir == null || exportsDir == null) {
                        return "Vault: could not create project folders. Re-grant access."
                    }

                    val okMd = writeTextDocFile(
                        dir = sessionsDir,
                        filename = "$base.md",
                        mime = "text/markdown",
                        text = mdText ?: ""
                    )

                    val okHtml = writeTextDocFile(
                        dir = exportsDir,
                        filename = "$base.html",
                        mime = "text/html",
                        text = htmlText ?: ""
                    )

                    return if (okMd && okHtml) {
                        "Saved ✓ (Vault/AnI/Projects/$proj/Sessions/$base.md)"
                    } else {
                        "Vault: save failed. Re-grant access or use Export."
                    }
                } catch (_: Exception) {
                    return "Vault: save failed. Re-grant access or use Export."
                }
            }

            // Fallback: MediaStore Downloads (works on most devices, but listing is unreliable on CalyxOS)
            return try {
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
                    "Vault: save failed. Grant Vault Access in options."
                }
            } catch (_: Exception) {
                "Vault: save failed. Grant Vault Access in options."
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

        // Expose the SAME bridge under both names to match JS
        val bridge = AniBridge()
        webView.addJavascriptInterface(bridge, "Android")     // window.Android.*
        webView.addJavascriptInterface(bridge, "AniAndroid")  // window.AniAndroid.*

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
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
