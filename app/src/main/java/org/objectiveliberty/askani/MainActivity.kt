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
import org.json.JSONObject
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

                // Some devices/flows do not return expected flags reliably.
                // Persistable permission requires READ/WRITE flags.
                val requested = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val takeFlags = (flags and requested).let { if (it == 0) requested else it }

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

        // Inject saved vault URI for UI diagnostics
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
         * Returns a JSON array string of project folder names.
         * Always valid JSON, even when vault is missing/unreadable.
         */
        @JavascriptInterface
        fun listProjects(): String {
            val projectsDir = resolveProjectsDir(createIfMissing = false) ?: return "[]"
            val out = JSONArray()

            val children = projectsDir.listFiles()
                .filter { it.isDirectory }
                .mapNotNull { it.name }
                .filter { it.isNotBlank() }
                .sortedBy { it.lowercase() }

            for (name in children) out.put(name)
            return out.toString()
        }

        /**
         * Debug helper: returns JSON object about vault resolution and scan results.
         * Call from JS to see *exactly* what Android thinks the vault path is.
         */
        @JavascriptInterface
        fun debugVault(): String {
            val obj = JSONObject()
            val uriStr = activity.prefs.getString(activity.KEY_VAULT_URI, null)

            obj.put("savedUri", uriStr ?: JSONObject.NULL)

            val root = uriStr?.let {
                try { DocumentFile.fromTreeUri(activity, Uri.parse(it)) } catch (_: Exception) { null }
            }

            obj.put("rootName", root?.name ?: JSONObject.NULL)
            obj.put("rootExists", root != null && root.exists())
            obj.put("rootIsDir", root?.isDirectory ?: false)

            val projectsDir = try { resolveProjectsDir(createIfMissing = false) } catch (_: Exception) { null }
            obj.put("projectsDirName", projectsDir?.name ?: JSONObject.NULL)
            obj.put("projectsDirExists", projectsDir != null && projectsDir.exists())

            val projects = JSONArray()
            if (projectsDir != null) {
                val children = projectsDir.listFiles()
                    .filter { it.isDirectory }
                    .mapNotNull { it.name }
                    .sortedBy { it.lowercase() }
                for (name in children) projects.put(name)
            }
            obj.put("projects", projects)
            obj.put("projectsCount", projects.length())

            return obj.toString()
        }

        // New name (preferred by ui.py). Kept "bankToDownloads" compat below.
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

        /**
         * Stable vault resolution:
         * - Prefer existing case-insensitive matches for "AnI" and "Projects"
         * - Only create folders when createIfMissing=true
         *
         * Supports selecting:
         *  - Projects folder directly
         *  - AnI folder directly
         *  - A parent that contains AnI (any case)
         *  - A parent that contains Projects (any case)
         */
        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val uriStr = activity.prefs.getString(activity.KEY_VAULT_URI, null) ?: return null
            val treeUri = Uri.parse(uriStr)

            val root = DocumentFile.fromTreeUri(activity, treeUri) ?: return null
            if (!root.exists() || !root.isDirectory) return null

            val rootNameLc = (root.name ?: "").lowercase()

            // If user picked Projects directly
            if (rootNameLc == "projects") return root

            // Helper: find child dir by name case-insensitive
            fun findChildDirCaseInsensitive(parent: DocumentFile, wantedLc: String): DocumentFile? {
                return parent.listFiles()
                    .firstOrNull { it.isDirectory && (it.name ?: "").lowercase() == wantedLc }
            }

            // Helper: find or create exact-name directory, but accept existing case-insensitive match
            fun findOrCreateDirSmart(parent: DocumentFile, nameExact: String, wantedLc: String): DocumentFile? {
                val existingCi = findChildDirCaseInsensitive(parent, wantedLc)
                if (existingCi != null) return existingCi
                if (!createIfMissing) return null
                return try { parent.createDirectory(nameExact) } catch (_: Exception) { null }
            }

            // If user picked AnI directly
            val aniDir: DocumentFile? = if (rootNameLc == "ani") {
                root
            } else {
                // Prefer existing "AnI" child; else create when allowed
                findOrCreateDirSmart(root, "AnI", "ani")
            } ?: return null

            // Under AnI, prefer existing Projects; else create when allowed
            val projectsDir = findOrCreateDirSmart(aniDir, "Projects", "projects")
            if (projectsDir != null) return projectsDir

            // Additional fallback: user picked a parent that contains Projects directly (without AnI)
            val projectsDirect = findChildDirCaseInsensitive(root, "projects")
            if (projectsDirect != null) return projectsDirect

            return null
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
    }
}
