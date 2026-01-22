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
            // SAF providers sometimes return 0 flags; don't trust them.
            // Always attempt to persist BOTH read + write.
            persistVaultPermission(uri)

            prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()

            runOnUiThread {
                webView.evaluateJavascript(
                    "window.__aniVaultAndroidUri = ${jsQuote(uri.toString())};" +
                            "if (window.aniOnVaultReady) window.aniOnVaultReady(true);",
                    null
                )
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

        // Bridge exposed as BOTH AniAndroid and Android (keeps backward compatibility with your JS)
        val bridge = AniBridge(this)
        webView.addJavascriptInterface(bridge, "AniAndroid")
        webView.addJavascriptInterface(bridge, "Android")

        // Load your hosted UI
        webView.loadUrl("https://objectiveliberty-ani.hf.space")

        // Best-effort: re-take persisted permission on startup (some providers are picky)
        val saved = prefs.getString(KEY_VAULT_URI, null)
        if (!saved.isNullOrBlank()) {
            try {
                persistVaultPermission(Uri.parse(saved))
            } catch (_: Exception) {
                // ignore
            }
        }

        // Inject cached vault URI into page when ready (best-effort)
        webView.postDelayed({
            val uriStr = prefs.getString(KEY_VAULT_URI, null)
            if (!uriStr.isNullOrBlank()) {
                webView.evaluateJavascript(
                    "window.__aniVaultAndroidUri = ${jsQuote(uriStr)};",
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

    private fun persistVaultPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers throw even when it "works enough" for the current session.
            // We'll still store the URI and attempt to use it.
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun jsQuote(s: String): String {
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

        /**
         * Returns JSON array string: ["Project1","Project2",...]
         */
        @JavascriptInterface
        fun listProjects(): String {
            val projectsDir = resolveProjectsDir(createIfMissing = false) ?: return "[]"

            val out = JSONArray()
            val children = try {
                projectsDir.listFiles()
                    .filter { it.isDirectory }
                    .mapNotNull { it.name }
                    .sortedBy { it.lowercase() }
            } catch (_: Exception) {
                emptyList()
            }

            for (name in children) out.put(name)
            return out.toString()
        }

        /**
         * Saves into:
         *   <vault>/AnI/Projects/<proj>/Sessions/<base>.md
         *   <vault>/AnI/Projects/<proj>/Exports/<base>.html
         *
         * BUT if user picked:
         *   - "AnI" folder directly => treat that as the AnI root
         *   - "Projects" folder directly => treat that as Projects root
         */
        @JavascriptInterface
        fun bankToDownloads(md: String?, html: String?, baseName: String?, projectSafe: String?): String {
            val proj = (projectSafe ?: "General").ifBlank { "General" }
            val base = (baseName ?: "ani-session").ifBlank { "ani-session" }
            val mdText = md ?: ""
            val htmlText = html ?: ""

            val projectsDir = resolveProjectsDir(createIfMissing = true)
                ?: return "Vault not set. Use Bank options → Select Vault."

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

        /**
         * Optional: call from JS if you want a quick permission/path sanity check.
         */
        @JavascriptInterface
        fun vaultDebug(): String {
            val j = JSONObject()
            j.put("savedUri", activity.prefs.getString(activity.KEY_VAULT_URI, null) ?: JSONObject.NULL)

            val persisted = try {
                activity.contentResolver.persistedUriPermissions.map {
                    JSONObject()
                        .put("uri", it.uri.toString())
                        .put("read", it.isReadPermission)
                        .put("write", it.isWritePermission)
                }
            } catch (_: Exception) {
                emptyList()
            }
            j.put("persisted", JSONArray(persisted))

            val root = getVaultRoot()
            j.put("rootName", root?.name ?: JSONObject.NULL)
            j.put("rootUri", root?.uri?.toString() ?: JSONObject.NULL)

            val projectsDir = resolveProjectsDir(createIfMissing = false)
            j.put("projectsDirName", projectsDir?.name ?: JSONObject.NULL)
            j.put("projectsDirUri", projectsDir?.uri?.toString() ?: JSONObject.NULL)

            val kids = try {
                projectsDir?.listFiles()?.mapNotNull { it.name } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            j.put("projectsChildren", JSONArray(kids))

            return j.toString()
        }

        // ---------- Internal helpers ----------

        private fun getVaultRoot(): DocumentFile? {
            val uriStr = activity.prefs.getString(activity.KEY_VAULT_URI, null) ?: return null
            return try {
                val uri = Uri.parse(uriStr)
                DocumentFile.fromTreeUri(activity, uri)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Handles vault selection being:
         *  - AnI folder itself
         *  - Projects folder itself
         *  - A parent folder where we should use/create AnI/Projects
         */
        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val root = getVaultRoot() ?: return null
            val rootName = (root.name ?: "").trim().lowercase()

            // If user picked Projects directly, use it as projectsDir
            if (rootName == "projects") {
                return root
            }

            // Determine the AnI directory
            val aniDir: DocumentFile? = if (rootName == "ani") {
                root
            } else {
                findOrCreateDir(root, "AnI", createIfMissing)
            } ?: return null

            // Projects directory is AnI/Projects
            return findOrCreateDir(aniDir, "Projects", createIfMissing)
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
