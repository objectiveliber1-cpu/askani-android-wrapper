package org.objectiveliberty.askani

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
                val takeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()

                notifyVaultReady(true)
            } catch (e: Exception) {
                Log.e("AnI", "Failed to persist vault URI permission", e)
                notifyVaultReady(false)
            }
        } else {
            notifyVaultReady(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Best-effort: inject stored vault uri and status into JS after page load
                val uriStr = prefs.getString(KEY_VAULT_URI, null)
                if (!uriStr.isNullOrBlank()) {
                    // Also best-effort re-take permission (some ROMs are picky)
                    try {
                        val uri = Uri.parse(uriStr)
                        val takeFlags =
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (_: Exception) {
                        // ignore
                    }

                    webView.evaluateJavascript(
                        "window.__aniVaultAndroidUri=${jsQuote(uriStr)};" +
                                "if(window.aniOnVaultReady){window.aniOnVaultReady(true);} ",
                        null
                    )
                }
            }
        }
        webView.webChromeClient = WebChromeClient()

        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = true

        // Bridge exposed as AniAndroid
        val bridge = AniBridge(this)
        webView.addJavascriptInterface(bridge, "AniAndroid")

        // Load your HF space
        webView.loadUrl("https://objectiveliberty-ani.hf.space")
    }

    private fun notifyVaultReady(ok: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.aniOnVaultReady){window.aniOnVaultReady(${if (ok) "true" else "false"});} ",
                null
            )
        }
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

        // --------------------------
        // Public JS API
        // --------------------------

        @JavascriptInterface
        fun pickVaultFolder(): String {
            activity.runOnUiThread { activity.launchVaultPicker() }
            return "Select your AnI vault folder…"
        }

        @JavascriptInterface
        fun vaultStatus(): String {
            return if (getVaultUri().isNullOrBlank()) "Vault not set" else "Vault set ✓"
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
                ?: return "Vault not set. Use Bank options → Grant Vault Access."

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

        // --------------------------
        // Internals
        // --------------------------

        private fun getVaultUri(): String? {
            return activity.prefs.getString(activity.KEY_VAULT_URI, null)
        }

        private fun getVaultRoot(): DocumentFile? {
            val uriStr = getVaultUri() ?: return null
            return try {
                val uri = Uri.parse(uriStr)
                DocumentFile.fromTreeUri(activity, uri)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Robust resolution rules:
         * - If selected folder contains a "Projects" directory, treat selected folder as AnI root.
         * - If selected folder IS "Projects", treat it as Projects dir.
         * - Else if selected folder contains "AnI", treat selected as container -> selected/AnI/Projects.
         * - Else create selected/AnI/Projects (when createIfMissing=true).
         */
        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val root = getVaultRoot() ?: return null

            // Case 1: user picked Projects folder directly
            val rootName = (root.name ?: "").trim().lowercase()
            if (rootName == "projects") return root

            // Case 2: user picked AnI folder (or any folder that already contains Projects)
            val existingProjects = root.findFile("Projects")
            if (existingProjects != null && existingProjects.isDirectory) return existingProjects

            // Case 3: user picked a container that contains AnI
            val existingAni = root.findFile("AnI")
            if (existingAni != null && existingAni.isDirectory) {
                val p = existingAni.findFile("Projects")
                if (p != null && p.isDirectory) return p
                return if (createIfMissing) getOrCreateDir(existingAni, "Projects") else null
            }

            // Case 4: create AnI/Projects under selected root
            if (!createIfMissing) return null
            val aniDir = getOrCreateDir(root, "AnI") ?: return null
            return getOrCreateDir(aniDir, "Projects")
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
