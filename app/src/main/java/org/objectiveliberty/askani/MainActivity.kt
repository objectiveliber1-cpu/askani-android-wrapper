package org.objectiveliberty.askani

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import android.content.ClipboardManager
import android.content.ClipData
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var vaultAdapter: VaultAdapter
    private lateinit var btnLoadSessions: Button
    private lateinit var txtContextStatus: TextView
    private lateinit var fabDrawer: FloatingActionButton
    
    private val projects = mutableListOf<Project>()
    private val vaultItems = mutableListOf<VaultItem>()
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("ani_prefs", Context.MODE_PRIVATE)
    }
    
    private val bridge = AniBridge(this)
    
    companion object {
        private const val HOME_URL = "https://objectiveliberty-ani.hf.space/"
        private const val REQUEST_VAULT_PICKER = 1001
        private const val KEY_VAULT_URI = "vault_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views
        webView = findViewById(R.id.webView)
        drawerLayout = findViewById(R.id.drawer_layout)
        recyclerView = findViewById(R.id.projects_recycler)
        btnLoadSessions = findViewById(R.id.btn_load_sessions)
        txtContextStatus = findViewById(R.id.txt_context_status)
        fabDrawer = findViewById(R.id.fab_drawer)
        
        setupWebView()
        setupDrawer()
        loadProjects()
    }
    
    private fun setupWebView() {
        // WebView configuration
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        
        webView.addJavascriptInterface(bridge, "AnIAndroid")
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
        
        // URL allowlist
        val allowedHosts = setOf(
            "objectiveliberty-ani.hf.space",
            "huggingface.co",
            "hf.space"
        )
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request?.url?.host ?: return true
                return !allowedHosts.any { host.endsWith(it) }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = Uri.parse(url)
                val host = uri.host ?: return true
                return !allowedHosts.any { host.endsWith(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("AnI", "Page finished loading: $url")
                
            }
        }
        
        // Ensure "general" project exists if vault is set
        if (!prefs.getString(KEY_VAULT_URI, null).isNullOrBlank()) {
            bridge.ensureProject("general")
        }
        
        webView.loadUrl(HOME_URL)
    }
    
    private fun setupDrawer() {
        // Setup RecyclerView
        vaultAdapter = VaultAdapter(
            items = vaultItems,
            onProjectClick = { project -> toggleProject(project) },
            onSessionToggle = { session -> updateContextStatus() }
        )
        
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = vaultAdapter
        }
        
        // FAB to open drawer
        fabDrawer.setOnClickListener {
            drawerLayout.open()
        }
        
        // Load sessions button
        btnLoadSessions.setOnClickListener {
            loadSelectedSessions()
        }
    }
    
    private fun loadProjects() {
        Log.d("AnI", "loadProjects() called")
        val vaultUri = prefs.getString(KEY_VAULT_URI, null)
        if (vaultUri.isNullOrBlank()) {
            Toast.makeText(this, "Please select vault folder", Toast.LENGTH_SHORT).show()
            launchVaultPicker()
            return
        }
        
        try {
            val projectNames = bridge.listProjects()
            projects.clear()
            
            val projectList = JSONArray(projectNames as String)
            for (i in 0 until projectList.length()) {
                val projectName = projectList.getString(i)
                projects.add(Project(name = projectName))
            Log.d("AnI", "Loaded ${projects.size} projects into drawer")
            }
            
            updateVaultItems()
            Log.d("AnI", "updateVaultItems() creating ${vaultItems.size} items for adapter")
        } catch (e: Exception) {
            Log.e("AnI", "Failed to load projects: ${e.message}", e)
            Toast.makeText(this, "Failed to load projects", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun toggleProject(project: Project) {
        project.isExpanded = !project.isExpanded
        
        if (project.isExpanded && project.sessions.isEmpty()) {
            // Load sessions for this project
            try {
                val sessionsJson = bridge.listSessions(project.name)
                val sessionsList = JSONArray(sessionsJson as String)
                
                for (i in 0 until sessionsList.length()) {
                    val sessionName = sessionsList.getString(i)
                    project.sessions.add(Session(
                        name = sessionName,
                        project = project.name,
                        isSelected = false
                    ))
                }
            } catch (e: Exception) {
                Log.e("AnI", "Failed to load sessions: ${e.message}", e)
            }
        }
        
        updateVaultItems()
            Log.d("AnI", "updateVaultItems() creating ${vaultItems.size} items for adapter")
    }
    
    private fun updateVaultItems() {
        Log.d("AnI", "updateVaultItems() called with ${projects.size} projects")
            Log.d("AnI", "updateVaultItems() creating ${vaultItems.size} items for adapter")
        vaultItems.clear()
        
        for (project in projects) {
            vaultItems.add(VaultItem.ProjectItem(project))
            
            if (project.isExpanded) {
                for (session in project.sessions) {
                    vaultItems.add(VaultItem.SessionItem(session))
                }
            }
        }
        
        Log.d("AnI", "Built ${vaultItems.size} vault items, notifying adapter")
        vaultAdapter.updateItems(vaultItems.toList())
        Log.d("AnI", "Adapter notified with ${vaultItems.size} items")
    }
    
    private fun updateContextStatus() {
        val selectedCount = projects.flatMap { it.sessions }.count { it.isSelected }
        txtContextStatus.text = if (selectedCount == 0) {
            "No context loaded"
        } else {
            "📚 $selectedCount session(s) selected"
        }
    }
    
    private fun loadSelectedSessions() {
        val selectedSessions = projects.flatMap { it.sessions }.filter { it.isSelected }
        
        if (selectedSessions.isEmpty()) {
            Toast.makeText(this, "No sessions selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val combinedContext = StringBuilder()
            
            for (session in selectedSessions) {
                val sessionContent = bridge.readSessionFile(session.project, session.name)
                if (sessionContent.isNotEmpty()) {
                    combinedContext.append("=== ${session.project}/${session.name} ===\n")
                    combinedContext.append(extractConversationHistory(sessionContent))
                    combinedContext.append("\n\n")
                }
            }
            
            
            // Create temporary context file
            val contextFile = File(cacheDir, "session_context_${System.currentTimeMillis()}.txt")
            contextFile.writeText(
                "=== AnI Session Context ===\n" +
                "Loaded ${selectedSessions.size} session(s)\n" +
                "Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n" +
                combinedContext.toString()
            )
            
            Log.d("AnI", "Created context file: ${contextFile.absolutePath}, size: ${contextFile.length()} bytes")
            
            // Inject into Gradio textarea with instruction to upload
            val jsCode = """
                (function() {
                    const textarea = document.querySelector('textarea[placeholder*="Type"]') || 
                                   document.querySelector('textarea');
                    if (textarea) {
                        textarea.value = "I've loaded context from ${selectedSessions.size} previous session(s). Please reference the uploaded file 'session_context.txt' for the full conversation history.";
                        textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                    return 'ready';
                })();
            """.trimIndent()
            
            webView.evaluateJavascript(jsCode) { result ->
                Log.d("AnI", "Context injection result: $result")
                // TODO: Programmatically trigger file upload to Gradio
                Toast.makeText(
                    this@MainActivity,
                    "📄 Context saved to file (${contextFile.length()/1024}KB). You can manually upload it to the chat.",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateContextStatus()
            drawerLayout.close()
            
            Toast.makeText(
                this,
                "Loaded ${selectedSessions.size} session(s) as context",
                Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e("AnI", "Failed to load sessions: ${e.message}", e)
            Toast.makeText(this, "Failed to load sessions", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun extractConversationHistory(sessionJson: String): String {
        return try {
            val json = JSONObject(sessionJson)
            val messages = json.optJSONArray("messages") ?: return ""
            
            val history = StringBuilder()
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val role = msg.optString("role", "unknown")
                val content = msg.optString("content", "")
                history.append("[$role]: $content\n\n")
            }
            history.toString()
        } catch (e: Exception) {
            Log.e("AnI", "Failed to parse session: ${e.message}", e)
            ""
        }
    }
    
    private fun launchVaultPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_VAULT_PICKER)
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_VAULT_PICKER && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
                
                webView.evaluateJavascript(
                    "window.__aniVaultAndroidUri = ${jsQuote(uri.toString())};" +
                    "if (window.aniOnVaultReady) window.aniOnVaultReady(true);",
                    null
                )
                
                loadProjects()
            }
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
            val uri = activity.prefs.getString(KEY_VAULT_URI, null)
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
            val uriStr = activity.prefs.getString(KEY_VAULT_URI, null)
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

        @JavascriptInterface
        fun readSessionFile(project: String, session: String): String {
            Log.d("AnI", "readSessionFile called for $project/$session")
            val vaultUri = activity.prefs.getString(KEY_VAULT_URI, null) ?: return ""
            
            try {
                val projectsDir = resolveProjectsDir(false) ?: return ""
                val projectFolder = projectsDir.findFile(project.lowercase()) ?: return ""
                val sessionFile = projectFolder.findFile("$session.json") ?: return ""
                
                return readTextFile(sessionFile.uri)
            } catch (e: Exception) {
                Log.e("AnI", "Failed to read session file: ${e.message}", e)
                return ""
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
