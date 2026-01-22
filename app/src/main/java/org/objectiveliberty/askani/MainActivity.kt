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

    private val pickVaultTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) {
            try {
                // Persist permission for future launches
                val flags = res.data?.flags ?: 0
                val takeFlags = flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()

                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.__aniVaultAndroidUri = ${jsQuote(uri.toString())};" +
                                "if (window.aniOnVaultReady) window.aniOnVaultReady(true);",
                        null
                    )
                }
            } catch (_: Exception) {
                runOnUiThread {
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

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = true

        val bridge = AniBridge(this)
        webView.addJavascriptInterface(bridge, "AniAndroid")
        webView.addJavascriptInterface(bridge, "Android")

        webView.loadUrl("https://objectiveliberty-ani.hf.space")

        // Best-effort: expose saved uri early
        webView.postDelayed({
            val uri = prefs.getString(KEY_VAULT_URI, null)
            if (uri != null) {
                webView.evaluateJavascript(
                    "window.__aniVaultAndroidUri = ${jsQuote(uri)};",
                    null
                )
            }
        }, 800)
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
        val esc = s.replace("\\", "\\\\").replace("'", "\\'")
        return "'$esc'"
    }

    class AniBridge(private val activity: MainActivity) {

        // --- Public JS API ---

        @JavascriptInterface
        fun pickVaultFolder(): String {
            activity.runOnUiThread { activity.launchVaultPicker() }
            return "Select your AnI vault folder…"
        }

        // Alias so ui.js can call requestVaultAccess() too
        @JavascriptInterface
        fun requestVaultAccess(): String {
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

        @JavascriptInterface
        fun bankToDownloads(md: String?, html: String?, baseName: String?, projectSafe: String?): String {
            val proj = (projectSafe ?: "General").ifBlank { "General" }
            val base = (baseName ?: "ani-session").ifBlank { "ani-session" }
            val mdText = md ?: ""
            val htmlText = html ?: ""

            val projectsDir = resolveProjectsDir(createIfMissing = true)
                ?: return "Vault not set. Use ⚙ → Grant Vault Access."

            val projDir = getOrCreateDir(projectsDir, proj) ?: return "Vault write failed."
            val sessionsDir = getOrCreateDir(projDir, "Sessions") ?: return "Vault write failed."
            val exportsDir = getOrCreateDir(projDir, "Exports") ?: return "Vault write failed."

            val ok1 = writeTextFile(sessionsDir, "$base.md", mdText)
            val ok2 = writeTextFile(exportsDir, "$base.html", htmlText)

            return if (ok1 && ok2) "Saved ✓ (Projects/$proj/Sessions/$base.md)" else "Vault write failed."
        }

        // --- Internal helpers ---

        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val root = getVaultRoot() ?: return null

            // If user picked AnI directly
            val rootName = (root.name ?: "").trim()
            val aniRoot: DocumentFile = when {
                rootName.equals("AnI", ignoreCase = true) -> root
                else -> findOrCreateDir(root, "AnI", createIfMissing) ?: return null
            }

            // If user picked Projects directly (rare, but possible in some pickers)
            val aniName = (aniRoot.name ?: "").trim()
            if (aniName.equals("Projects", ignoreCase = true)) return aniRoot

            val projects = findOrCreateDir(aniRoot, "Projects", createIfMissing) ?: return null
            return projects
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
            return try { parent.createDirectory(name) } catch (_: Exception) { null }
        }

        private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
            val existing = parent.findFile(name)
            if (existing != null && existing.isDirectory) return existing
            return try { parent.createDirectory(name) } catch (_: Exception) { null }
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
