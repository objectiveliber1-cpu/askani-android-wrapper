package org.objectiveliberty.askani

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
                val flags = res.data?.flags ?: 0
                val takeFlags = flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                // Persist permission so it survives app restarts
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

        // Bridge exposed as BOTH AniAndroid and Android (compat)
        val bridge = AniBridge(this)
        webView.addJavascriptInterface(bridge, "AniAndroid")
        webView.addJavascriptInterface(bridge, "Android")

        // Load HF Space
        webView.loadUrl("https://objectiveliberty-ani.hf.space")

        // Best-effort: inject saved vault URI for UI diagnostics
        webView.postDelayed({
            val uri = prefs.getString(KEY_VAULT_URI, null)
            if (!uri.isNullOrBlank()) {
                webView.evaluateJavascript("window.__aniVaultAndroidUri = ${jsQuote(uri)};", null)
            }
        }, 600)
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

        // ---------- Clipboard (for Copy button) ----------

        @JavascriptInterface
        fun copyToClipboard(text: String?): String {
            val t = (text ?: "").trim()
            if (t.isBlank()) return "copy-failed"

            return try {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AnI", t))

                activity.runOnUiThread {
                    // tiny toast so user knows it worked; remove if you hate it
                    Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                }
                "copied"
            } catch (_: Exception) {
                "copy-failed"
            }
        }

        // ---------- JS API ----------

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

        /**
         * Returns JSON array of project folder KEYS (lowercase-safe names),
         * e.g. ["general","test-1",...]
         */
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

        /**
         * Returns JSON array of session objects for the given project key.
         */
        @JavascriptInterface
        fun listSessions(projectSafe: String?): String {
            val proj = (projectSafe ?: "general").ifBlank { "general" }
            val projectsDir = resolveProjectsDir(createIfMissing = false) ?: return "[]"
            val projDir = projectsDir.findFile(proj)
            if (projDir == null || !projDir.isDirectory) return "[]"

            val sessionsDir = projDir.findFile("Sessions")
            if (sessionsDir == null || !sessionsDir.isDirectory) return "[]"

            val files = sessionsDir.listFiles()
                .filter { it.isFile }
                .mapNotNull { it.name }
                .filter { it.lowercase().endsWith(".md") }
                .sortedDescending()

            val out = JSONArray()
            for (name in files) {
                val obj = JSONObject()
                obj.put("name", name)
                obj.put("label", name)
                out.put(obj)
            }
            return out.toString()
        }

        /**
         * Returns the markdown content of the selected session file.
         */
        @JavascriptInterface
        fun readSession(projectSafe: String?, filename: String?): String {
            val proj = (projectSafe ?: "general").ifBlank { "general" }
            val fn = (filename ?: "").trim()
            if (fn.isBlank()) return ""

            val projectsDir = resolveProjectsDir(createIfMissing = false) ?: return ""
            val projDir = projectsDir.findFile(proj)
            if (projDir == null || !projDir.isDirectory) return ""

            val sessionsDir = projDir.findFile("Sessions")
            if (sessionsDir == null || !sessionsDir.isDirectory) return ""

            val file = sessionsDir.findFile(fn)
            if (file == null || !file.isFile) return ""

            return readTextFile(file.uri)
        }

        // Preferred name (ui.py). Kept "bankToDownloads" compat below.
        @JavascriptInterface
        fun bankToVault(md: String?, html: String?, baseName: String?, projectSafe: String?): String {
            return bankInternal(md, html, baseName, projectSafe)
        }

        @JavascriptInterface
        fun bankToDownloads(md: String?, html: String?, baseName: String?, projectSafe: String?): String {
            return bankInternal(md, html, baseName, projectSafe)
        }

        // ---------- Internal ----------

        private fun bankInternal(md: String?, html: String?, baseName: String?, projectSafe: String?): String {
            val proj = (projectSafe ?: "General").ifBlank { "General" }
            val base = (baseName ?: "ani-session").ifBlank { "ani-session" }
            val mdText = md ?: ""
            val htmlText = html ?: ""

            val projectsDir = resolveProjectsDir(createIfMissing = true)
                ?: return "Vault not set. Tap Grant Vault Access."

            val projDir = getOrCreateDir(projectsDir, proj) ?: return "Vault write failed."
            val sessionsDir = getOrCreateDir(projDir, "Sessions") ?: return "Vault write failed."
            val exportsDir = getOrCreateDir(projDir, "Exports") ?: return "Vault write failed."

            val ok1 = writeTextFile(sessionsDir, "$base.md", mdText, "text/markdown")
            val ok2 = writeTextFile(exportsDir, "$base.html", htmlText, "text/html")

            return if (ok1 && ok2) "Saved ✓ (Projects/$proj/Sessions/$base.md)" else "Vault write failed."
        }

        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val uriStr = activity.prefs.getString(activity.KEY_VAULT_URI, null) ?: return null
            val treeUri = Uri.parse(uriStr)

            val root = DocumentFile.fromTreeUri(activity, treeUri) ?: return null
            val rootName = (root.name ?: "").lowercase()

            // User might select:
            //  - AnI folder
            //  - Projects folder
            //  - parent folder containing AnI
            val projectsDir: DocumentFile? = when (rootName) {
                "projects" -> root
                "ani" -> findOrCreateDir(root, "Projects", createIfMissing)
                else -> {
                    val ani = findOrCreateDir(root, "AnI", createIfMissing) ?: return null
                    findOrCreateDir(ani, "Projects", createIfMissing)
                }
            }

            return projectsDir
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

        private fun writeTextFile(dir: DocumentFile, filename: String, text: String, mime: String): Boolean {
            return try {
                val existing = dir.findFile(filename)
                val file = existing ?: dir.createFile(mime, filename)
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

        private fun readTextFile(uri: Uri): String {
            return try {
                activity.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return ""
                    BufferedReader(InputStreamReader(input, Charset.forName("UTF-8"))).use { br ->
                        val sb = StringBuilder()
                        while (true) {
                            val line = br.readLine() ?: break
                            sb.append(line).append("\n")
                        }
                        sb.toString()
                    }
                }
            } catch (_: Exception) {
                ""
            }
        }
    }
}
