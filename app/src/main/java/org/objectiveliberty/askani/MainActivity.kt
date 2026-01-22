package org.objectiveliberty.askani

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val prefs by lazy { getSharedPreferences("ani_prefs", Context.MODE_PRIVATE) }
    private val KEY_VAULT_URI = "vault_tree_uri"

    // Change if you want to point to a different URL later
    private val START_URL = "https://objectiveliberty-ani.hf.space"

    private val pickVaultTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) {
            val ok = try {
                // Persist permission for future launches
                val flags = res.data?.flags ?: 0
                val takeFlags = flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                contentResolver.takePersistableUriPermission(uri, takeFlags)
                prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
                true
            } catch (_: Exception) {
                false
            }

            // Notify web UI (best-effort)
            runOnUiThread {
                if (ok) {
                    webView.evaluateJavascript(
                        "window.__aniVaultAndroidUri = ${jsQuote(uri.toString())};" +
                            "if (window.aniOnVaultReady) window.aniOnVaultReady(true);",
                        null
                    )
                } else {
                    webView.evaluateJavascript(
                        "if (window.aniOnVaultReady) window.aniOnVaultReady(false);",
                        null
                    )
                }
            }
        } else {
            runOnUiThread {
                webView.evaluateJavascript(
                    "if (window.aniOnVaultReady) window.aniOnVaultReady(false);",
                    null
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Inject cached vault URI into page after load (best-effort)
                val uri = prefs.getString(KEY_VAULT_URI, null)
                if (!uri.isNullOrBlank()) {
                    webView.evaluateJavascript(
                        "window.__aniVaultAndroidUri = ${jsQuote(uri)};",
                        null
                    )
                }
            }
        }

        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = true

        // Expose bridge to JS as window.AniAndroid (and window.Android for backwards compat)
        val bridge = AniBridge(this)
        webView.addJavascriptInterface(bridge, "AniAndroid")
        webView.addJavascriptInterface(bridge, "Android")

        webView.loadUrl(START_URL)
    }

    private fun launchVaultPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        pickVaultTree.launch(intent)
    }

    private fun jsQuote(s: String): String {
        // minimal JS string quoting
        val esc = s.replace("\\", "\\\\").replace("'", "\\'")
        return "'$esc'"
    }

    class AniBridge(private val activity: MainActivity) {

        // ---------- Public JS API ----------

        @JavascriptInterface
        fun pickVaultFolder(): String {
            activity.runOnUiThread { activity.launchVaultPicker() }
            return "Select your AnI vault folder…"
        }

        @JavascriptInterface
        fun vaultStatus(): String {
            val uri = activity.prefs.getString(activity.KEY_VAULT_URI, null)
            return if (uri.isNullOrBlank()) "Vault not set" else "Vault set ✓"
        }

        @JavascriptInterface
        fun listProjects(): String {
            val projectsDir = resolveProjectsDir(createIfMissing = false) ?: return "[]"
            val out = JSONArray()

            val children = projectsDir.listFiles()
                .filter { it.isDirectory }
                .mapNotNull { it.name }
                .sortedBy { it.lowercase() }

            for (name in children) out.put(name)
            return out.toString()
        }

        // Keep name bankToDownloads to match your current ui.py VAULT_JS expectations.
        // It writes into the selected SAF vault tree (NOT "real Downloads").
        @JavascriptInterface
        fun bankToDownloads(md: String?, html: String?, baseName: String?, projectSafe: String?): String {
            val proj = (projectSafe ?: "General").ifBlank { "General" }
            val base = (baseName ?: "ani-session").ifBlank { "ani-session" }
            val mdText = md ?: ""
            val htmlText = html ?: ""

            val projectsDir = resolveProjectsDir(createIfMissing = true)
                ?: return "Vault not set. Use Grant Vault Access."

            val projDir = getOrCreateDir(projectsDir, proj) ?: return "Vault write failed."
            val sessionsDir = getOrCreateDir(projDir, "Sessions") ?: return "Vault write failed."
            val exportsDir = getOrCreateDir(projDir, "Exports") ?: return "Vault write failed."

            val ok1 = writeTextFile(sessionsDir, "$base.md", mdText)
            val ok2 = writeTextFile(exportsDir, "$base.html", htmlText)

            return if (ok1 && ok2) {
                "Saved ✓ (Projects/$proj/Sessions/$base.md)"
            } else {
                "Vault write failed."
            }
        }

        // ---------- Internal helpers ----------

        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val root = getVaultRoot() ?: return null
            val rootName = (root.name ?: "").trim().lowercase()

            // The user might pick:
            // 1) the AnI folder itself
            // 2) the Projects folder itself
            // 3) a parent folder (we should find/create AnI/Projects inside it)
            return when (rootName) {
                "ani" -> {
                    // root is already AnI
                    findOrCreateDir(root, "Projects", createIfMissing)
                }
                "projects" -> {
                    // root is already Projects
                    root
                }
                else -> {
                    // root is some parent folder; find/create AnI/Projects within it
                    val ani = findOrCreateDir(root, "AnI", createIfMissing) ?: return null
                    findOrCreateDir(ani, "Projects", createIfMissing)
                }
            }
        }

        private fun getVaultRoot(): DocumentFile? {
            val uriStr = activity.prefs.getString(activity.KEY_VAULT_URI, null) ?: return null
            return try {
                val uri = Uri.parse(uriStr)
                DocumentFile.fromTreeUri(activity, uri)
            } catch (_: Exception) {
                null
            }
        }

        private fun findOrCreateDir(parent: DocumentFile, name: String, create: Boolean): DocumentFile? {
            val existing = parent.findFile(name)
            if (existing != null && existing.isDirectory) return existing
            if (!create) return null
            return try {
                parent.createDirectory(name)
            } catch (_: Exception) {
                null
            }
        }

        private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
            val existing = parent.findFile(name)
            if (existing != null && existing.isDirectory) return existing
            return try {
                parent.createDirectory(name)
            } catch (_: Exception) {
                null
            }
        }

        private fun writeTextFile(dir: DocumentFile, filename: String, text: String): Boolean {
            return try {
                val existing = dir.findFile(filename)
                val file = existing ?: dir.createFile("text/plain", filename)
                if (file == null) return false
                activity.contentResolver.openOutputStream(file.uri, "wt").use { os ->
                    if (os == null) return false
                    os.write(text.toByteArray(Charset.forName("UTF-8")))
                    os.flush()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
