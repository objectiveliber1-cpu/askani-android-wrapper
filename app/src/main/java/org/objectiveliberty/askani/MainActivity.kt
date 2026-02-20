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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
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
    private lateinit var projectSpinner: Spinner
    private lateinit var projectSpinnerAdapter: ArrayAdapter<String>

    private lateinit var chipContainer: LinearLayout
    private lateinit var chipScrollView: HorizontalScrollView
    private val pendingSessionContent = linkedMapOf<String, String>()

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
        projectSpinner = findViewById(R.id.spinner_project)
        projectSpinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        projectSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        projectSpinner.adapter = projectSpinnerAdapter
        chipContainer = findViewById(R.id.chip_container)
        chipScrollView = findViewById(R.id.chip_scroll_view)

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
                // Hide Gradio's "Tools" button — replaced by the native FAB/drawer
                view?.evaluateJavascript("""
                    (function() {
                        function hideToolsBtn() {
                            var btns = document.querySelectorAll('button');
                            for (var i = 0; i < btns.length; i++) {
                                if (btns[i].textContent.trim().match(/^[\u2630☰]?\s*Tools$/)) {
                                    btns[i].style.display = 'none';
                                }
                            }
                        }
                        hideToolsBtn();
                        setTimeout(hideToolsBtn, 1500);
                        setTimeout(hideToolsBtn, 4000);
                    })();
                """.trimIndent(), null)
                if (isAutoSaveEnabled) {
                    // Re-inject observer after page reload
                    view?.postDelayed({ injectAutoSaveObserver() }, 2000)
                }
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
        
        // New project button
        findViewById<Button>(R.id.btn_new_project).setOnClickListener {
            showNewProjectDialog()
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

        // Setup model override spinner
        val modelSpinner = findViewById<Spinner>(R.id.spinner_model)
        val models = listOf(
            "(default)", "gemini/gemini-2.5-flash", "gemini/gemini-2.5-pro",
            "openai/gpt-4o", "openai/gpt-4o-mini",
            "groq/llama-3.3-70b-versatile", "groq/llama-3.1-8b-instant",
            "xai/grok-3-mini"
        )
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = modelAdapter
    }
    
    private fun selectedProject(): String {
        return (projectSpinner.selectedItem as? String) ?: "general"
    }

    private fun showNewProjectDialog() {
        val input = EditText(this)
        input.hint = "Project name"

        AlertDialog.Builder(this)
            .setTitle("New Project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val result = bridge.ensureProject(name)
                    if (result == "ok") {
                        // Also ensure Sessions subfolder exists
                        val projectsDir = bridge.resolveProjectsDirPublic(true)
                        if (projectsDir != null) {
                            val projDir = projectsDir.findFile(name)
                            if (projDir != null) {
                                bridge.getOrCreateDirPublic(projDir, "Sessions")
                                bridge.getOrCreateDirPublic(projDir, "Exports")
                            }
                        }
                        loadProjects()
                        // Select the new project in spinner
                        val pos = projectSpinnerAdapter.getPosition(name)
                        if (pos >= 0) projectSpinner.setSelection(pos)
                        Toast.makeText(this, "Project '$name' created", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to create project: $result", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

            // Populate project spinner
            val spinnerNames = projects.map { it.name }
            projectSpinnerAdapter.clear()
            projectSpinnerAdapter.addAll(spinnerNames)
            projectSpinnerAdapter.notifyDataSetChanged()
            // Default-select "general" if present
            val generalIdx = spinnerNames.indexOfFirst { it.equals("general", ignoreCase = true) }
            if (generalIdx >= 0) projectSpinner.setSelection(generalIdx)
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
        if (pendingSessionContent.isNotEmpty()) {
            txtContextStatus.text = "📎 ${pendingSessionContent.size} session(s) attached"
            return
        }
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
            var newlyAdded = 0
            for (session in selectedSessions) {
                val key = "${session.project}/${session.name}"
                if (pendingSessionContent.containsKey(key)) continue

                val raw = bridge.readSessionFile(session.project, session.name)
                Log.d("AnI", "Session ${session.name}: read ${raw.length} chars")
                if (raw.isNotEmpty()) {
                    val extracted = extractConversationHistory(raw, session.name)
                    Log.d("AnI", "Extracted ${extracted.length} chars from ${session.name}")
                    pendingSessionContent[key] = extracted
                    addSessionChip(key, session.name)
                    newlyAdded++
                }
            }

            if (pendingSessionContent.isNotEmpty()) {
                chipScrollView.visibility = android.view.View.VISIBLE
                injectSendInterceptor()
            }

            // Deselect sessions so drawer looks clean on next open
            for (session in selectedSessions) {
                session.isSelected = false
            }
            vaultAdapter.notifyDataSetChanged()
            updateContextStatus()

            drawerLayout.close()
            if (newlyAdded > 0) {
                Toast.makeText(this, "📎 $newlyAdded session(s) attached — type your message and send", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Sessions already attached", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("AnI", "Failed to load sessions: ${e.message}", e)
            Toast.makeText(this, "Failed to load sessions", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== CHIP UI MANAGEMENT ==========

    private fun addSessionChip(key: String, displayName: String) {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                setColor(0xFF5C6BC0.toInt())
                cornerRadius = 16f * resources.displayMetrics.density
            }
            background = bg
            val hPad = (10 * resources.displayMetrics.density).toInt()
            val vPad = (4 * resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            tag = key
        }

        val label = TextView(this).apply {
            val truncated = if (displayName.length > 22) displayName.take(20) + "…" else displayName
            text = truncated
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }

        val closeBtn = TextView(this).apply {
            text = " ✕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setOnClickListener {
                pendingSessionContent.remove(key)
                chipContainer.removeView(chip)
                if (pendingSessionContent.isEmpty()) {
                    chipScrollView.visibility = android.view.View.GONE
                    disconnectSendInterceptor()
                }
                // Also uncheck the session in the drawer if still visible
                val (proj, sessionName) = key.split("/", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else "" to key
                }
                projects.flatMap { it.sessions }
                    .find { it.project == proj && it.name == sessionName }
                    ?.let { it.isSelected = false }
                vaultAdapter.notifyDataSetChanged()
                updateContextStatus()
            }
        }

        chip.setOnLongClickListener {
            val content = pendingSessionContent[key] ?: ""
            val preview = if (content.length > 2000) content.take(2000) + "\n\n… (truncated)" else content
            val scrollView = ScrollView(this).apply {
                val tv = TextView(this@MainActivity).apply {
                    text = preview
                    setPadding(24, 24, 24, 24)
                    textSize = 12f
                }
                addView(tv)
            }
            AlertDialog.Builder(this)
                .setTitle(key)
                .setView(scrollView)
                .setPositiveButton("OK", null)
                .show()
            true
        }

        chip.addView(label)
        chip.addView(closeBtn)
        chipContainer.addView(chip)
    }

    private fun clearAllChips() {
        chipContainer.removeAllViews()
        pendingSessionContent.clear()
        chipScrollView.visibility = android.view.View.GONE
        disconnectSendInterceptor()
        updateContextStatus()
    }

    // ========== SEND INTERCEPTION ==========

    private fun injectSendInterceptor() {
        val js = """
            (function() {
                // Remove previous interceptor if present
                if (window.__aniSendInterceptor) {
                    const oldBtn = window.__aniSendInterceptorBtn;
                    if (oldBtn) oldBtn.removeEventListener('click', window.__aniSendInterceptor, true);
                    window.__aniSendInterceptor = null;
                    window.__aniSendInterceptorBtn = null;
                }

                function findSendButton() {
                    return document.querySelector('#aniAskBtn, button[aria-label*="Send"], button[title*="Send"]')
                        || document.querySelector('button:has(svg)');
                }

                function findTextarea() {
                    const textareas = document.querySelectorAll('textarea');
                    for (let ta of textareas) {
                        if (ta.offsetParent !== null && ta.clientHeight > 30) return ta;
                    }
                    return null;
                }

                const sendBtn = findSendButton();
                if (!sendBtn) { console.log('AnI: send button not found for interceptor'); return; }

                function interceptHandler(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    e.stopImmediatePropagation();
                    const ta = findTextarea();
                    const userMsg = ta ? ta.value : '';
                    console.log('AnI: intercepted send, user msg length=' + userMsg.length);
                    window.sessionBridge.onSendWithContext(userMsg);
                }

                sendBtn.addEventListener('click', interceptHandler, true);
                window.__aniSendInterceptor = interceptHandler;
                window.__aniSendInterceptorBtn = sendBtn;
                console.log('AnI: send interceptor installed');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun disconnectSendInterceptor() {
        val js = """
            (function() {
                if (window.__aniSendInterceptor && window.__aniSendInterceptorBtn) {
                    window.__aniSendInterceptorBtn.removeEventListener('click', window.__aniSendInterceptor, true);
                    window.__aniSendInterceptor = null;
                    window.__aniSendInterceptorBtn = null;
                    console.log('AnI: send interceptor removed');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
    
    
    // ========== NEW SAVE FUNCTIONALITY ==========
    
    private var isAutoSaveEnabled = false
    
    
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
                    project: '${selectedProject().replace("'", "\\'")}',
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
            val projectName = selectedProject()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
            val filename = "ani-${projectName}-$timestamp.json"

            val projectsDir = bridge.resolveProjectsDirPublic(false) ?: run {
                Toast.makeText(this, "Vault not accessible", Toast.LENGTH_SHORT).show()
                return
            }
            val projectFolder = projectsDir.findFile(projectName) ?: projectsDir.findFile(projectName.lowercase())
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
            injectAutoSaveObserver()
            Toast.makeText(this, "Auto-save enabled (triggers after AI responses)", Toast.LENGTH_SHORT).show()
        } else {
            button.text = "🔄 Auto-Save: OFF"
            disconnectAutoSaveObserver()
            Toast.makeText(this, "Auto-save disabled", Toast.LENGTH_SHORT).show()
        }

        Log.d("AnI", "Auto-save toggled: $isAutoSaveEnabled")
    }

    private fun injectAutoSaveObserver() {
        val js = """
            (function() {
                if (window.__aniAutoSaveObserver) {
                    window.__aniAutoSaveObserver.disconnect();
                }
                let debounceTimer = null;
                const chatContainer = document.querySelector('[data-testid="chatbot"]') ||
                                      document.querySelector('.chatbot') ||
                                      document.querySelector('gradio-app');
                if (!chatContainer) {
                    console.log('AnI: No chat container found for auto-save observer');
                    return;
                }
                window.__aniAutoSaveObserver = new MutationObserver(function(mutations) {
                    let hasNewBot = false;
                    for (const m of mutations) {
                        for (const node of m.addedNodes) {
                            if (node.nodeType === 1) {
                                if (node.matches && node.matches('[data-testid="bot"]')) hasNewBot = true;
                                if (node.querySelector && node.querySelector('[data-testid="bot"]')) hasNewBot = true;
                            }
                        }
                        // Also detect text changes inside bot messages (streaming)
                        if (m.type === 'characterData' || m.type === 'childList') {
                            const target = m.target.nodeType === 1 ? m.target : m.target.parentElement;
                            if (target && target.closest && target.closest('[data-testid="bot"]')) hasNewBot = true;
                        }
                    }
                    if (hasNewBot) {
                        if (debounceTimer) clearTimeout(debounceTimer);
                        debounceTimer = setTimeout(function() {
                            console.log('AnI: Auto-save triggered by new bot message');
                            window.sessionBridge.autoSaveTriggered();
                        }, 3000);
                    }
                });
                window.__aniAutoSaveObserver.observe(chatContainer, {
                    childList: true,
                    subtree: true,
                    characterData: true
                });
                console.log('AnI: Auto-save MutationObserver installed');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun disconnectAutoSaveObserver() {
        val js = """
            (function() {
                if (window.__aniAutoSaveObserver) {
                    window.__aniAutoSaveObserver.disconnect();
                    window.__aniAutoSaveObserver = null;
                    console.log('AnI: Auto-save MutationObserver disconnected');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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
        // Read values from Android UI
        val temperature = findViewById<SeekBar>(R.id.seekbar_temperature).progress / 100.0
        val maxTokensText = findViewById<android.widget.EditText>(R.id.edit_max_tokens).text.toString().trim()
        val maxTokens = maxTokensText.toIntOrNull()
        val modelRaw = (findViewById<Spinner>(R.id.spinner_model).selectedItem as? String) ?: ""
        val modelOverride = if (modelRaw == "(default)") "" else modelRaw
        val includeContext = findViewById<android.widget.CheckBox>(R.id.checkbox_include_context).isChecked
        val includeSources = findViewById<android.widget.CheckBox>(R.id.checkbox_include_sources).isChecked
        val systemInstructions = findViewById<android.widget.EditText>(R.id.edit_system_instructions).text.toString()

        // Escape strings for JS injection
        val modelJs = modelOverride.replace("\\", "\\\\").replace("'", "\\'")
        val systemJs = systemInstructions.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

        val jsCode = """
            (function() {
                // Helper: find a Gradio component container by its label text
                function findByLabel(labelText) {
                    const labels = document.querySelectorAll('label span, label');
                    for (const lbl of labels) {
                        if (lbl.textContent.trim().startsWith(labelText)) {
                            // Walk up to find the Gradio component container
                            let container = lbl.closest('.gradio-container, .gradio-group, [class*="wrap"], [class*="block"]');
                            if (!container) container = lbl.parentElement?.parentElement;
                            return container;
                        }
                    }
                    return null;
                }

                // Helper: set a Gradio textbox value
                function setTextbox(labelText, value) {
                    const container = findByLabel(labelText);
                    if (!container) { console.log('AnI: textbox not found: ' + labelText); return; }
                    const input = container.querySelector('input[type="text"], textarea');
                    if (input) {
                        const nativeSetter = Object.getOwnPropertyDescriptor(
                            input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype, 'value'
                        ).set;
                        nativeSetter.call(input, value);
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                        console.log('AnI: set ' + labelText + ' = ' + value);
                    }
                }

                // Helper: set a Gradio slider value
                function setSlider(labelText, value) {
                    const container = findByLabel(labelText);
                    if (!container) { console.log('AnI: slider not found: ' + labelText); return; }
                    const range = container.querySelector('input[type="range"]');
                    const number = container.querySelector('input[type="number"]');
                    if (range) {
                        const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                        nativeSetter.call(range, value);
                        range.dispatchEvent(new Event('input', { bubbles: true }));
                        range.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    if (number) {
                        const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                        nativeSetter.call(number, value);
                        number.dispatchEvent(new Event('input', { bubbles: true }));
                        number.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    console.log('AnI: set slider ' + labelText + ' = ' + value);
                }

                // Helper: set a Gradio checkbox
                function setCheckbox(labelText, checked) {
                    const container = findByLabel(labelText);
                    if (!container) { console.log('AnI: checkbox not found: ' + labelText); return; }
                    const input = container.querySelector('input[type="checkbox"]');
                    if (input && input.checked !== checked) {
                        input.click();
                        console.log('AnI: toggled ' + labelText + ' = ' + checked);
                    }
                }

                // First, open the Advanced accordion if it's closed
                const accordions = document.querySelectorAll('.label-wrap');
                for (const acc of accordions) {
                    if (acc.textContent.trim().includes('Advanced')) {
                        const isOpen = acc.classList.contains('open') ||
                                       acc.parentElement?.querySelector('.hide') === null;
                        if (!acc.classList.contains('open')) {
                            acc.click();
                        }
                        break;
                    }
                }

                // Apply settings after a short delay to let accordion open
                setTimeout(function() {
                    setSlider('Temperature', ${String.format("%.1f", temperature)});
                    ${if (maxTokens != null) "setSlider('Max output tokens', $maxTokens);" else ""}
                    setTextbox('Model override', '$modelJs');
                    setCheckbox('Include context', $includeContext);
                    setCheckbox('Show sources to model', $includeSources);
                    setTextbox('System instruction', '$systemJs');
                    console.log('AnI: All advanced settings applied');
                }, 500);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            Log.d("AnI", "Advanced settings injection result: $result")
        }

        Toast.makeText(this, "Settings applied to chat interface", Toast.LENGTH_SHORT).show()
        hideAdvancedDrawer()
        drawerLayout.close()
    }
    private fun extractConversationHistory(content: String, filename: String): String {
        if (!filename.lowercase().endsWith(".json")) {
            // Markdown files — return as-is
            return content
        }

        // JSON session format: { "messages": [ { "role": "...", "content": "..." }, ... ] }
        return try {
            val obj = JSONObject(content)
            val messages = obj.optJSONArray("messages") ?: return content
            val sb = StringBuilder()
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val role = msg.optString("role", "unknown")
                val text = msg.optString("content", "")
                if (text.isNotBlank()) {
                    sb.append("[${role}]: ${stripGradioChrome(text)}\n\n")
                }
            }
            sb.toString().ifBlank { content }
        } catch (e: Exception) {
            Log.w("AnI", "Failed to parse JSON session $filename, treating as plain text: ${e.message}")
            content
        }
    }

    private fun stripGradioChrome(text: String): String {
        var cleaned = text

        // Strip "Loading..." lines anywhere (standalone or after [assistant]:)
        cleaned = cleaned.replace(Regex("""(?m)^?\s*Loading\.{3}\s*$"""), "")
        cleaned = cleaned.replace(Regex("""\[assistant\]:\s*Loading\.{3}\s*"""), "")

        // Strip Gradio UI chrome patterns anywhere in text
        val chromeRegexes = listOf(
            Regex("""Textbox\s+Dropdown\s+Ask AnI\s*"""),
            Regex("""Attachments:\s*none"""),
            Regex("""[\u2630☰]\s*Tools\s*\n?\s*Built with Gradio\s*\n?\s*[·\u00b7]\s*\n?\s*Settings"""),
            Regex("""[\u2630☰]\s+Tools\s+Built with Gradio\s+[·\u00b7]\s+Settings"""),
            Regex("""Built with Gradio\s*[·\u00b7]\s*Settings"""),
            Regex("""📋\s*Copy"""),
            Regex("""\uD83D\uDCCB\s*Copy"""),
            Regex("""(?m)^\s*Copy\s*$"""),  // standalone "Copy" on its own line
        )
        for (regex in chromeRegexes) {
            cleaned = cleaned.replace(regex, "")
        }

        // Strip "ð" garbled emoji sequences (common from DOM scrape encoding issues)
        cleaned = cleaned.replace(Regex("""ð[\u0080-\u00FF]{2,3}"""), "")
        // Strip "Â·" (garbled "·") and lone "Â" artifacts
        cleaned = cleaned.replace("Â·", "·")
        cleaned = cleaned.replace(Regex("""Â(?=[^a-zA-Z])"""), "")
        // Collapse excessive blank lines (3+ newlines → 2)
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")
        return cleaned.trim()
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
        fun autoSaveTriggered() {
            Log.d("AnI", "autoSaveTriggered() called from MutationObserver")
            activity.runOnUiThread {
                if (activity.isAutoSaveEnabled) {
                    activity.saveCurrentSession()
                }
            }
        }

        @JavascriptInterface
        fun onSendWithContext(userMessage: String) {
            Log.d("AnI", "onSendWithContext called, userMsg=${userMessage.length} chars, pending=${activity.pendingSessionContent.size}")
            activity.runOnUiThread {
                val sessionCount = activity.pendingSessionContent.size
                val combined = StringBuilder()
                combined.append("=== LOADED CONTEXT ($sessionCount session(s)) ===\n\n")
                for ((key, content) in activity.pendingSessionContent) {
                    val shortKey = key.substringAfterLast("/")
                    combined.append("--- $shortKey ---\n")
                    combined.append(content.trimEnd())
                    combined.append("\n\n")
                }
                combined.append("=== END CONTEXT ===\n\n")
                combined.append(userMessage)

                val finalText = combined.toString()
                val finalBytes = finalText.toByteArray(Charsets.UTF_8)
                val finalBase64 = android.util.Base64.encodeToString(finalBytes, android.util.Base64.NO_WRAP)

                // Disconnect interceptor first so the programmatic click goes through
                activity.disconnectSendInterceptor()

                val js = """
                    (function() {
                        const textareas = document.querySelectorAll('textarea');
                        let ta = null;
                        for (let t of textareas) {
                            if (t.offsetParent !== null && t.clientHeight > 30) { ta = t; break; }
                        }
                        if (!ta) { console.log('AnI: textarea not found for context send'); return; }

                        const nativeSetter = Object.getOwnPropertyDescriptor(
                            window.HTMLTextAreaElement.prototype, 'value'
                        ).set;
                        var decoded = (function(b64) {
                            var binStr = atob(b64);
                            var bytes = new Uint8Array(binStr.length);
                            for (var i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
                            return new TextDecoder('utf-8').decode(bytes);
                        })('$finalBase64');
                        nativeSetter.call(ta, decoded);
                        ta.dispatchEvent(new Event('input', { bubbles: true }));
                        ta.dispatchEvent(new Event('change', { bubbles: true }));

                        setTimeout(function() {
                            const sendBtn = document.querySelector('#aniAskBtn, button[aria-label*="Send"], button[title*="Send"]')
                                || Array.from(document.querySelectorAll('button')).find(b => b.querySelector('svg') && b.offsetParent !== null);
                            if (sendBtn) {
                                sendBtn.click();
                                console.log('AnI: context+message sent via ' + (sendBtn.id || sendBtn.className));
                            } else {
                                console.log('AnI: send button not found');
                            }
                        }, 150);
                    })();
                """.trimIndent()

                activity.webView.evaluateJavascript(js) { result ->
                    Log.d("AnI", "Context send result: $result")
                }

                activity.clearAllChips()
                Toast.makeText(
                    activity,
                    "Sent with context from $sessionCount session(s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

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
                .filter { it.lowercase().endsWith(".md") || it.lowercase().endsWith(".json") }
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

        // ---------- Public helpers for MainActivity ----------

        fun resolveProjectsDirPublic(createIfMissing: Boolean): DocumentFile? {
            return resolveProjectsDir(createIfMissing)
        }

        fun getOrCreateDirPublic(parent: DocumentFile, name: String): DocumentFile? {
            return getOrCreateDir(parent, name)
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
                val projectDir = projectsDir.findFile(project) ?: projectsDir.findFile(project.lowercase())
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
