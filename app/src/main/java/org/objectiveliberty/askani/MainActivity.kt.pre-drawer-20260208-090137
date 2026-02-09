package org.objectiveliberty.askani

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

    // --- Configure your Space URL here ---
    private val HOME_URL = "https://objectiveliberty-ani.hf.space"

    // Allowlist: keep WebView from escaping to chatgpt.com / random links
    private val ALLOWED_HOSTS = setOf(
        "objectiveliberty-ani.hf.space",
        "huggingface.co",
        "hf.space"
    )

    private fun isAllowed(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = (uri.host ?: "").lowercase()
            host in ALLOWED_HOSTS || ALLOWED_HOSTS.any { host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }

    private val pickVaultTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        Log.d("AnI", "Vault picker result: resultCode=${res.resultCode}, data=${res.data}")
        
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) {
            try {
                val flags = res.data?.flags ?: 0
                val takeFlags = flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                // Persist permission so it survives app restarts
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
                
                Log.d("AnI", "Vault URI saved: $uri")
                
                // Ensure "general" project exists
                AniBridge(this).ensureProject("general")

                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.__aniVaultAndroidUri = ${jsQuote(uri.toString())};" +
                                "if (window.aniOnVaultReady) window.aniOnVaultReady(true);",
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e("AnI", "Failed to save vault URI", e)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if (window.aniOnVaultReady) window.aniOnVaultReady(false);",
                        null
                    )
                }
            }
        } else {
            Log.w("AnI", "Vault picker cancelled or failed")
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

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Allowlist keeps us in-app; block everything else to prevent "jump to ChatGPT"
                return !isAllowed(url)
            }

            // Backward compat (older Android)
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return false
                return !isAllowed(u)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Prevent target=_blank / new window from escaping to external browser/app
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }

        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(false)

        // Bridge exposed as BOTH AniAndroid and Android (compat)
        val bridge = AniBridge(this)
        webView.addJavascriptInterface(bridge, "AniAndroid")
        webView.addJavascriptInterface(bridge, "Android")
        
        // Ensure "general" project if vault already set
        if (!prefs.getString(KEY_VAULT_URI, null).isNullOrBlank()) {
            bridge.ensureProject("general")
        }

        // ✅ Load AnI (not ChatGPT)
        // Inject vault.js after page loads
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("AnI", "Page finished loading: $url")
                // Inject vault.js from assets
                try {
                    val jsCode = assets.open("vault.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Log.d("AnI", "Injecting vault.js (${jsCode.length} bytes)")
                    // Use base64 to avoid escaping issues
                    val jsBase64 = android.util.Base64.encodeToString(
                        jsCode.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    
                    // Inject by decoding base64 in JavaScript
                    val injectionCode = """
                        (function() {
                            var script = document.createElement('script');
                            script.textContent = atob('$jsBase64');
                            document.head.appendChild(script);
                        })();
                    """.trimIndent()
                    
                    webView.evaluateJavascript(injectionCode) { result ->
                        Log.d("AnI", "Vault.js injection complete, result: $result")
                    }
                } catch (e: Exception) {
                    Log.e("AnI", "Failed to inject vault.js: ${e.message}", e)
                }
            }
        }
        
        webView.loadUrl(HOME_URL)

        // Best-effort: inject saved vault URI for UI diagnostics
        webView.postDelayed({
            val uri = prefs.getString(KEY_VAULT_URI, null)
            if (!uri.isNullOrBlank()) {
                webView.evaluateJavascript("window.__aniVaultAndroidUri = ${jsQuote(uri)};", null)
            }
        }, 600)
    }

    private fun launchVaultPicker() {
        Log.d("AnI", "launchVaultPicker called")
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            pickVaultTree.launch(intent)
            Log.d("AnI", "Vault picker intent launched")
        } catch (e: Exception) {
            Log.e("AnI", "Failed to launch vault picker", e)
        }
    }

    private fun jsQuote(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("'", "\\'")
        return "'$esc'"
    }

    class AniBridge(private val activity: MainActivity) {

        // ---------- Clipboard ----------
        @JavascriptInterface
        fun copyToClipboard(text: String?): String {
            return try {
                val t = (text ?: "").toString()
                if (t.isBlank()) return "copy-failed"

                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("AnI", t)
                cm.setPrimaryClip(clip)
                "copied"
            } catch (_: Exception) {
                "copy-failed"
            }
        }

        // ---------- JS API ----------

        @JavascriptInterface
        fun pickVaultFolder(): String {
            Log.d("AnI", "pickVaultFolder called from JS")
            activity.runOnUiThread { 
                activity.launchVaultPicker() 
            }
            return "Opening folder picker..."
        }

        @JavascriptInterface
        fun vaultStatus(): String {
            val uri = activity.prefs.getString(activity.KEY_VAULT_URI, null)
            return if (uri.isNullOrBlank()) "Vault not set" else "Vault set ✓"
        }

        @JavascriptInterface
        fun listProjects(): String {
            Log.d("AnI", "listProjects called")
            val projectsDir = resolveProjectsDir(createIfMissing = false)
            if (projectsDir == null) {
                Log.w("AnI", "listProjects: projectsDir is null")
                return "[]"
            }
            
            val out = JSONArray()

            val children = projectsDir.listFiles()
                .filter { it.isDirectory }
                .mapNotNull { it.name }
                .sortedBy { it.lowercase() }

            Log.d("AnI", "Found ${children.size} project folders: $children")
            
            for (name in children) out.put(name)
            return out.toString()
        }

        @JavascriptInterface
        fun listSessions(projectSafe: String?): String {
            val proj = (projectSafe ?: "general").ifBlank { "general" }
            Log.d("AnI", "listSessions called for project: $proj")
            
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
            
            Log.d("AnI", "Found ${files.size} sessions in $proj")
            return out.toString()
        }

        @JavascriptInterface
        fun ensureProject(projectKey: String?): String {
            return try {
                val proj = (projectKey ?: "general").ifBlank { "general" }
                Log.d("AnI", "ensureProject called for: $proj")
                
                val projectsDir = resolveProjectsDir(createIfMissing = true)
                if (projectsDir == null) {
                    Log.e("AnI", "ensureProject: vault not accessible")
                    return "NO_VAULT"
                }
                
                val projDir = getOrCreateDir(projectsDir, proj)
                if (projDir == null) {
                    Log.e("AnI", "ensureProject: failed to create project dir")
                    return "ERR:Vault write failed."
                }
                
                if (projDir.isDirectory) {
                    Log.d("AnI", "ensureProject: success for $proj")
                    "ok"
                } else {
                    "ERR:Vault write failed."
                }
            } catch (e: Exception) {
                Log.e("AnI", "ensureProject error", e)
                "ERR:${e.message}"
            }
        }

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

            Log.d("AnI", "bankInternal: project=$proj, base=$base")

            val projectsDir = resolveProjectsDir(createIfMissing = true)
                ?: return "Vault not set. Tap Grant Vault Access."

            val projDir = getOrCreateDir(projectsDir, proj) ?: return "Vault write failed."
            val sessionsDir = getOrCreateDir(projDir, "Sessions") ?: return "Vault write failed."
            val exportsDir = getOrCreateDir(projDir, "Exports") ?: return "Vault write failed."

            val ok1 = writeTextFile(sessionsDir, "$base.md", mdText, "text/markdown")
            val ok2 = writeTextFile(exportsDir, "$base.html", htmlText, "text/html")

            Log.d("AnI", "bankInternal: md=$ok1, html=$ok2")

            return if (ok1 && ok2) "Saved ✓ (Projects/$proj/Sessions/$base.md)" else "Vault write failed."
        }

        private fun resolveProjectsDir(createIfMissing: Boolean): DocumentFile? {
            val uriStr = activity.prefs.getString(activity.KEY_VAULT_URI, null)
            if (uriStr.isNullOrBlank()) {
                Log.w("AnI", "resolveProjectsDir: no vault URI set")
                return null
            }
            
            val treeUri = Uri.parse(uriStr)
            Log.d("AnI", "resolveProjectsDir: vault URI = $uriStr")

            val root = DocumentFile.fromTreeUri(activity, treeUri)
            if (root == null) {
                Log.e("AnI", "resolveProjectsDir: DocumentFile.fromTreeUri returned null")
                return null
            }
            
            val rootName = (root.name ?: "").lowercase()
            Log.d("AnI", "resolveProjectsDir: root folder name = $rootName")

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
            return try { 
                parent.createDirectory(name)
            } catch (e: Exception) { 
                Log.e("AnI", "Failed to create directory: $name", e)
                null 
            }
        }

        private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
            val existing = parent.findFile(name)
            if (existing != null && existing.isDirectory) return existing
            return try { 
                parent.createDirectory(name)
            } catch (e: Exception) { 
                Log.e("AnI", "Failed to create directory: $name", e)
                null 
            }
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
            } catch (e: Exception) {
                Log.e("AnI", "Failed to write file: $filename", e)
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
            } catch (e: Exception) {
                Log.e("AnI", "Failed to read file", e)
                ""
            }
        }
    }
}
