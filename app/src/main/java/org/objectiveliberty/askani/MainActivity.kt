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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
        webView.addJavascriptInterface(SessionBridge(this), "sessionBridge")
        
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
        
        // Save session button
        findViewById<Button>(R.id.btn_save_session).setOnClickListener {
            saveCurrentSession()
        }
        
        // Auto-save toggle button
        findViewById<Button>(R.id.btn_toggle_autosave).setOnClickListener {
            toggleAutoSave()
        }
        
        // Advanced options button - shows sub-drawer
        findViewById<Button>(R.id.btn_advanced_options).setOnClickListener {
            showAdvancedDrawer()
        }
        
        // Back button in advanced drawer
        findViewById<Button>(R.id.btn_back_to_main).setOnClickListener {
            hideAdvancedDrawer()
        }
        
        // Apply advanced settings
        findViewById<Button>(R.id.btn_apply_advanced).setOnClickListener {
            applyAdvancedSettings()
        }
        
        // Setup temperature slider
        setupTemperatureSlider()
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
                    val sessionObj = sessionsList.getJSONObject(i)
                    val sessionName = sessionObj.getString("name")
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
                Log.d("AnI", "Session ${session.name}: read ${sessionContent.length} chars")
                if (sessionContent.isNotEmpty()) {
                    combinedContext.append("=== ${session.project}/${session.name} ===\n")
                    val extracted = extractConversationHistory(sessionContent)
                    Log.d("AnI", "Extracted ${extracted.length} chars from ${session.name}")
                    combinedContext.append(extracted)
                    combinedContext.append("\n\n")
                }
            }
            
            
            
            val contextSize = combinedContext.length
            val sizeThreshold = 50000  // ~12.5K tokens
            
            if (contextSize < sizeThreshold) {
                // Small context: inject directly into textarea
                val contextText = "📚 Context from ${selectedSessions.size} session(s):\n\n$combinedContext\n\n---\n[You can now ask questions about this conversation history]"
                
                // Use base64 to safely encode the context text
                val contextBytes = contextText.toByteArray(Charsets.UTF_8)
                val contextBase64 = android.util.Base64.encodeToString(contextBytes, android.util.Base64.NO_WRAP)
                
                val jsCode = """
                    (function() {
                        // Find the chat textarea (not other textareas)
                        const textareas = document.querySelectorAll('textarea');
                        let chatTextarea = null;
                        
                        // Find the textarea that's actually for chat input (largest visible one)
                        for (let ta of textareas) {
                            if (ta.offsetParent !== null && ta.clientHeight > 50) {
                                chatTextarea = ta;
                                break;
                            }
                        }
                        
                        if (chatTextarea) {
                            chatTextarea.value = atob('$contextBase64');
                            chatTextarea.dispatchEvent(new Event('input', { bubbles: true }));
                            chatTextarea.dispatchEvent(new Event('change', { bubbles: true }));
                            
                            // Auto-click send button
                            setTimeout(() => {
                                const sendButton = document.querySelector('button[aria-label*="Send"], button[title*="Send"], button:has(svg)');
                                if (sendButton) sendButton.click();
                            }, 100);
                            
                            return 'injected and sent';
                        }
                        return 'textarea not found';
                    })();
                """.trimIndent()
                
                Log.d("AnI", "Injecting context of ${contextText.length} chars (base64: ${contextBase64.length} chars)")
                
                // Delay to let Gradio finish loading
                webView.postDelayed({
                    webView.evaluateJavascript(jsCode) { result ->
                        Log.d("AnI", "Small context injected: $result")
                        
                        // Verify it was actually set
                        webView.evaluateJavascript("document.querySelector('textarea')?.value.length || 0") { length ->
                            Log.d("AnI", "Textarea now has $length characters")
                        }
                        // Also log the actual value
                        webView.evaluateJavascript("document.querySelector('textarea')?.value.substring(0, 200)") { value ->
                            Log.d("AnI", "Textarea content: $value")
                        }
                    }
                }, 1000) // Wait 1 second for Gradio to be ready
                
                Toast.makeText(this, "✅ Context loaded in chat input! Click SEND to submit it to the AI.", Toast.LENGTH_LONG).show()
                
            } else {
                // Large context: create file and show instructions
                val contextFile = File(getExternalFilesDir(null), "session_context_${System.currentTimeMillis()}.txt")
                contextFile.writeText(
                    "=== AnI Session Context ===\n" +
                    "Loaded ${selectedSessions.size} session(s)\n" +
                    "Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n" +
                    combinedContext.toString()
                )
                
                Log.d("AnI", "Large context file created: ${contextFile.absolutePath}")
                
                // Put instruction in textarea
                val jsCode = """
                    (function() {
                        const textarea = document.querySelector('textarea[placeholder*="Type"]') || document.querySelector('textarea');
                        if (textarea) {
                            textarea.value = "📄 Context is large (${contextSize/1024}KB). File saved: ${contextFile.name}\n\nPlease use the file upload button in the chat to attach this file.";
                            textarea.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        return 'file_created';
                    })();
                """.trimIndent()
                
                webView.evaluateJavascript(jsCode) { result ->
                    Log.d("AnI", "Large context file ready: $result")
                }
                
                Toast.makeText(
                    this,
                    "⚠️ Context is large (${contextSize/1024}KB).\nFile saved to: ${contextFile.name}\n\nUpload it via the chat's file button.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e("AnI", "Failed to load sessions: ${e.message}", e)
            Toast.makeText(this, "Failed to load sessions", Toast.LENGTH_SHORT).show()
        }
    }
    
    
    // ========== NEW SAVE FUNCTIONALITY ==========
    
    private var isAutoSaveEnabled = false
    private var autoSaveJob: android.os.Handler? = null
    
    
    private fun saveCurrentSession() {
        Log.d("AnI", "saveCurrentSession() called")

        // Extract conversation from Gradio and send via SessionBridge
        // This avoids evaluateJavascript() return value size limits
        val jsCode = """
            (function() {
                console.log('Starting JSON extraction via SessionBridge...');

                let messages = [];

                // Try to find Gradio message elements
                const msgElements = document.querySelectorAll('[data-testid="bot"], [data-testid="user"]') ||
                                   document.querySelectorAll('.message') ||
                                   document.querySelectorAll('.prose');

                console.log('Found elements:', msgElements.length);

                if (msgElements && msgElements.length > 0) {
                    msgElements.forEach((msg, idx) => {
                        const role = msg.getAttribute('data-testid') === 'user' ? 'user' : 'assistant';
                        const text = (msg.innerText || msg.textContent || '').trim();

                        if (text) {
                            messages.push({
                                role: role,
                                content: text,
                                timestamp: new Date().toISOString(),
                                include: true
                            });
                        }
                    });
                } else {
                    // Fallback: grab all chat text as one assistant message
                    const chatArea = document.querySelector('[data-testid="chatbot"]') ||
                                    document.querySelector('.chatbot') ||
                                    document.querySelector('gradio-app');
                    if (chatArea) {
                        const allText = (chatArea.innerText || chatArea.textContent || '').trim();
                        if (allText) {
                            messages.push({
                                role: 'assistant',
                                content: allText,
                                timestamp: new Date().toISOString(),
                                include: true
                            });
                        }
                    }
                }

                if (messages.length === 0) {
                    console.log('No messages found to save');
                    return;
                }

                const session = {
                    project: 'general',
                    sessionId: Date.now().toString(),
                    exported: new Date().toISOString(),
                    messages: messages
                };

                console.log('Sending', messages.length, 'messages via sessionBridge');
                window.sessionBridge.receiveSessionData(JSON.stringify(session));
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }
    
    private fun saveSessionToFile(sessionData: String) {
        try {
            val currentProject = projects.firstOrNull() ?: Project("general", mutableListOf())
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
            val filename = "ani-${currentProject.name}-$timestamp.json"
            
            val vaultUri = prefs.getString(KEY_VAULT_URI, null) ?: return
            val projectsDir = DocumentFile.fromTreeUri(this, android.net.Uri.parse(vaultUri))?.findFile("Projects")
            val projectFolder = projectsDir?.findFile(currentProject.name.lowercase())
            val sessionsFolder = projectFolder?.findFile("Sessions")
            
            if (sessionsFolder != null) {
                val sessionFile = sessionsFolder.createFile("application/json", filename)
                if (sessionFile != null) {
                    contentResolver.openOutputStream(sessionFile.uri, "wt").use { os ->
                        os?.write(sessionData.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "✅ Session saved: $filename", Toast.LENGTH_LONG).show()
                    Log.d("AnI", "Session saved: $filename")
                } else {
                    Toast.makeText(this, "Failed to create session file", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Sessions folder not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("AnI", "Failed to save session: ${e.message}", e)
            Toast.makeText(this, "Failed to save session", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun toggleAutoSave() {
        isAutoSaveEnabled = !isAutoSaveEnabled
        
        val button = findViewById<Button>(R.id.btn_toggle_autosave)
        if (isAutoSaveEnabled) {
            button.text = "🔄 Auto-Save: ON"
            startAutoSave()
            Toast.makeText(this, "Auto-save enabled (every 2 minutes)", Toast.LENGTH_SHORT).show()
        } else {
            button.text = "🔄 Auto-Save: OFF"
            stopAutoSave()
            Toast.makeText(this, "Auto-save disabled", Toast.LENGTH_SHORT).show()
        }
        
        Log.d("AnI", "Auto-save toggled: $isAutoSaveEnabled")
    }
    
    private fun startAutoSave() {
        stopAutoSave() // Clear any existing handler
        
        autoSaveJob = android.os.Handler(android.os.Looper.getMainLooper())
        autoSaveJob?.postDelayed(object : Runnable {
            override fun run() {
                if (isAutoSaveEnabled) {
                    Log.d("AnI", "Auto-save triggered")
                    saveCurrentSession()
                    autoSaveJob?.postDelayed(this, 120000) // 2 minutes
                }
            }
        }, 120000) // First save after 2 minutes
    }
    
    private fun stopAutoSave() {
        autoSaveJob?.removeCallbacksAndMessages(null)
        autoSaveJob = null
    }
    
    // ========== ADVANCED OPTIONS DRAWER ==========
    
    private fun showAdvancedDrawer() {
        findViewById<LinearLayout>(R.id.drawer_container).visibility = android.view.View.GONE
        findViewById<ScrollView>(R.id.advanced_drawer).visibility = android.view.View.VISIBLE
    }
    
    private fun hideAdvancedDrawer() {
        findViewById<ScrollView>(R.id.advanced_drawer).visibility = android.view.View.GONE
        findViewById<LinearLayout>(R.id.drawer_container).visibility = android.view.View.VISIBLE
    }
    
    private fun setupTemperatureSlider() {
        val seekBar = findViewById<android.widget.SeekBar>(R.id.seekbar_temperature)
        val textView = findViewById<android.widget.TextView>(R.id.text_temperature_value)
        
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 100.0
                textView.text = String.format("%.1f", temp)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }
    
    private fun applyAdvancedSettings() {
        // TODO: Implement advanced settings application
        // For now, just close the drawer and show confirmation
        Toast.makeText(this, "Advanced settings will be implemented soon", Toast.LENGTH_SHORT).show()
        hideAdvancedDrawer()
        drawerLayout.close()
    }
    private fun extractConversationHistory(sessionJson: String): String {
        // Sessions are Markdown files, not JSON - just return the content
        return sessionJson
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
    
    
    class SessionBridge(private val activity: MainActivity) {

        @JavascriptInterface
        fun receiveSessionData(json: String) {
            Log.d("AnI", "SessionBridge received ${json.length} chars")
            try {
                val sessionObj = JSONObject(json)
                val sessionData = sessionObj.toString(2)
                activity.runOnUiThread {
                    activity.saveSessionToFile(sessionData)
                }
            } catch (e: Exception) {
                Log.e("AnI", "SessionBridge failed to parse JSON: ${e.message}", e)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Failed to save session data", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                Log.d("AnI", "projectsDir found: ${projectsDir?.uri}")
                val projectDir = projectsDir.findFile(project.lowercase())
                val projectFolder = projectDir?.findFile("Sessions") ?: return ""
                Log.d("AnI", "Looking for project folder: ${project.lowercase()}, found: ${projectFolder?.uri}")
                Log.d("AnI", "Files in ${project.lowercase()}: ${projectFolder?.listFiles()?.map { it.name }?.joinToString()}")
                val sessionFile = projectFolder.findFile(session) ?: return ""
                Log.d("AnI", "Looking for session: $session, found: ${sessionFile?.uri}")
                
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
