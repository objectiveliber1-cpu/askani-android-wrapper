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
import java.util.Locale

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

                // Notify the web UI
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.__aniVaultAndroidUri=${jsQuote(uri.toString())};" +
                                "if(window.aniOnVaultReady)window.aniOnVaultReady(true);",
                        null
                    )
                }
            } catch (_: Exception) {
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.aniOnVaultReady)window.aniOnVaultReady(false);",
                        null
                    )
                }
            }
        } else {
            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window.aniOnVaultReady)window.aniOnVaultReady(false);",
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

        // Your HF Space
        webView.loadUrl("https://objectiveliberty-ani.hf.space")

        // Inject cached vault URI (best effort) after the page starts up
        webView.postDelayed({
            val uri = prefs.getString(KEY_VAULT_URI, null)
            if (!uri.isNullOrBlank()) {
                webView.evaluateJavascript(
                    "window.__aniVaultAndroidUri=${jsQuote(uri)};",
                    null
                )
            }
        }, 900)
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
            val kids = projectsDir.listFiles()
                .filter { it.isDirectory }
                .mapNotNull { it.name }
                .sortedBy { it.lowercase(Locale.getDefault()) }
            for (n in kids) out.put(n)
            return out.toString()
        }

        @JavascriptInterface
        fun bankToDownloads(md: String?, html: String?, baseName: String?, projectSafe: String?): String {
            val proj = (projectSafe ?: "General").ifBlank { "General" }
            val base = (baseName ?: "ani-session").ifBlank { "ani-session" }
            val mdText = md ?: ""
            val htmlText = html ?: ""

            val projectsDir = resolveProjectsDir(createIfMissing = true)
                ?: return "Vault not set. Use Vault → Grant access."

            val projDir = getOrCreateDir(projectsDir, proj) ?: return "Vault write failed."
            val sessionsDir = getOrCreateDir(projDir, "Sessions") ?: return "Vault write failed."
            val exportsDir = getOrCreateDir(projDir, "Exports") ?: return "Vault write failed."

            val ok1 = writeTextFile(sessionsDir, "$base.md", mdText)
            val ok2 = writeTextFile(exportsDir, "$base.html", htmlText)

            return if (ok1 && ok2) "Saved ✓ (Projects/$proj/Sessions/$base.md)" else "Vault write failed."
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
         * IMPORTANT:
         * Users may pick:
         *  - the AnI folder itself        -> use it directly
         *  - the Projects folder itself   -> use it directly as projectsDir
         *  - a parent folder             -> find/create AnI/Projects under it
         *
         * Also: DocumentFile.findFile() can be case-sensitive; we match ignoring case.
         */
        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val picked = getVaultRoot() ?: return null
            val pickedName = (picked.name ?: "").trim().lowercase(Locale.getDefault())

            // If user picked "Projects" folder directly
            if (pickedName == "projects") {
                return picked
            }

            // Determine aniDir:
            // - if user picked "AnI", aniDir = picked
            // - else try find "AnI" inside picked (case-insensitive)
            // - else create it (if allowed)
            val aniDir: DocumentFile? = if (pickedName == "ani") {
                picked
            } else {
                findChildDirIgnoreCase(picked, "AnI")
                    ?: if (createIfMissing) picked.createDirectory("AnI") else null
            } ?: return null

            // Determine projectsDir:
            // - if user picked a parent folder and aniDir exists, use aniDir/Projects
            // - if aniDir itself is "Projects" (rare), handle above already
            val projectsDir =
                findChildDirIgnoreCase(aniDir, "Projects")
                    ?: if (createIfMissing) aniDir.createDirectory("Projects") else null

            return projectsDir
        }

        private fun findChildDirIgnoreCase(parent: DocumentFile, want: String): DocumentFile? {
            val wantLower = want.lowercase(Locale.getDefault())
            return parent.listFiles().firstOrNull {
                it.isDirectory && ((it.name ?: "").lowercase(Locale.getDefault()) == wantLower)
            }
        }

        private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
            // Case-insensitive find first
            val existing = findChildDirIgnoreCase(parent, name)
            if (existing != null) return existing
            return try {
                parent.createDirectory(name)
            } catch (_: Exception) {
                null
            }
        }

        private fun writeTextFile(dir: DocumentFile, filename: String, text: String): Boolean {
            return try {
                val existing = dir.listFiles().firstOrNull {
                    it.isFile && (it.name ?: "") == filename
                }
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
