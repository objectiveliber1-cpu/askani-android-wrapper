// ===============================
// AnI Vault + Android bridge + Draft + Pickers + Saved Sessions
// + Composer fixed-pane helpers + Session-ID persistence
// + Clipboard helper (WebView-safe)
// ===============================
const ANI_LS_PROJECTS = "ani_projects_v3";
const ANI_LS_CURPROJ  = "ani_current_project_v3";
const ANI_LS_CURKEY   = "ani_current_project_key_v3";
const ANI_DRAFT_PREFIX   = "ani_draft_v1__";     // + projectKey
const ANI_SESSION_PREFIX = "ani_session_id_v1__"; // + projectKey
const ANI_AUTOBANK_PREFIX = "ani_autobank_v1__"; // + projectKey
function lsGet(key, fallback) { try { return localStorage.getItem(key) ?? fallback; } catch(e){ return fallback; } }
function lsSet(key, val) { try { localStorage.setItem(key, val); } catch(e){} }
function lsDel(key) { try { localStorage.removeItem(key); } catch(e){} }
function aniLsGetProjects() { try { return JSON.parse(lsGet(ANI_LS_PROJECTS, "[]")) || []; } catch(e){ return []; } }
function aniLsSetProjects(arr) { try { lsSet(ANI_LS_PROJECTS, JSON.stringify(arr || [])); } catch(e){} }
function aniGetCurrentDisplay() {
  return (lsGet(ANI_LS_CURPROJ, "General") || "General").trim() || "General";
}
window.aniSetProjectDisplay = function(displayName){
  try{
    const val = (displayName || "").trim();
    const el = document.querySelector("#aniProjectDisplay textarea, #aniProjectDisplay input");
    if (!el) return "no-el";
    el.value = val;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    return "ok";
  }catch(e){
    return "fail";
  }
};
function toKey(display) {
  const s = (display || "").trim();
  if (!s) return "general";
  return s.toLowerCase()
          .replace(/[^a-z0-9._-]+/g, "-")
          .replace(/-+/g, "-")
          .replace(/^-+|-+$/g, "") || "general";
}
function toDisplayFromKey(key) {
  const k = (key || "").trim();
  if (!k) return "General";
  if (k.toLowerCase() === "general") return "General";
  return k.split("-").filter(Boolean).map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}
function draftKeyForProject(displayName){
  return ANI_DRAFT_PREFIX + toKey(displayName || aniGetCurrentDisplay() || "General");
}
function sessionKeyForProject(displayName){
  return ANI_SESSION_PREFIX + toKey(displayName || aniGetCurrentDisplay() || "General");
}
function autobankKeyForProject(displayName){
  return ANI_AUTOBANK_PREFIX + toKey(displayName || aniGetCurrentDisplay() || "General");
}
// ---------- Composer fixed-pane spacer ----------
window.aniFixComposer = function(){
  try{
    const comp = document.getElementById("aniComposer");
    const spacer = document.getElementById("aniTopSpacer");
    if (!comp || !spacer) return "no-elements";
    spacer.style.height = "0px";
    return "ok:0";
  }catch(e){
    return "fail";
  }
};
window.aniBindComposerAutoFix = function(){
  try{
    window.aniFixComposer();
    window.addEventListener("resize", () => window.aniFixComposer(), {passive:true});
    window.addEventListener("orientationchange", () => setTimeout(() => window.aniFixComposer(), 250), {passive:true});
    document.addEventListener("visibilitychange", () => setTimeout(() => window.aniFixComposer(), 250), {passive:true});
  }catch(e){}
  return "bound";
};
// ---------- Android host detection ----------
window.aniIsAndroidHost = function () {
  const hasApi = (obj) => !!(obj && (
    typeof obj.listProjects === "function" ||
    typeof obj.bankToVault === "function" ||
    typeof obj.pickVaultFolder === "function"
  ));
  return hasApi(window.AniAndroid) || hasApi(window.Android);
};
// ---------- Clipboard (WebView-safe + Android bridge aware) ----------
function _normalizeCopyResult(res){
  const s = (res ?? "").toString().trim().toLowerCase();
  // Android returns "copied" on success in the MainActivity.kt I gave you
  if (s === "copied" || s === "ok" || s === "true") return true;
  return false;
}
async function copyViaExecCommand(text){
  try{
    const t = (text || "").toString();
    if (!t.trim()) return false;
    const ta = document.createElement("textarea");
    ta.value = t;
    ta.setAttribute("readonly", "");
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    ta.style.left = "-9999px";
    ta.style.top = "0";
    document.body.appendChild(ta);
    // Ensure focus + selection (some WebViews require focus)
    ta.focus({ preventScroll: true });
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    const ok = document.execCommand("copy");
    document.body.removeChild(ta);
    return !!ok;
  }catch(e){
    return false;
  }
}
window.aniCopyTextRobust = async function(text){
  const t = (text || "").toString();
  if (!t.trim()) return { ok:false, msg:"Nothing to copy" };
  // 1) Prefer Android bridge if available (and VERIFY result)
  try{
    if (window.AniAndroid && typeof window.AniAndroid.copyToClipboard === "function"){
      const r = window.AniAndroid.copyToClipboard(t);
      if (_normalizeCopyResult(r)) return { ok:true, msg:"Copied ✓" };
      return { ok:false, msg:"Copy failed" };
    }
    if (window.Android && typeof window.Android.copyToClipboard === "function"){
      const r = window.Android.copyToClipboard(t);
      if (_normalizeCopyResult(r)) return { ok:true, msg:"Copied ✓" };
      return { ok:false, msg:"Copy failed" };
    }
  }catch(e){
    // fall through to other methods
  }
  // 2) Try modern clipboard (usually works in desktop browsers)
  try{
    if (navigator.clipboard && typeof navigator.clipboard.writeText === "function"){
      await navigator.clipboard.writeText(t);
      return { ok:true, msg:"Copied ✓" };
    }
  }catch(e){
    // fall through
  }
  // 3) Fallback: execCommand
  const ok = await copyViaExecCommand(t);
  return ok ? { ok:true, msg:"Copied ✓" } : { ok:false, msg:"Copy failed" };
};
window.aniBindCopyLink = function(){
  try{
    const link = document.getElementById("aniCopyLink");
    if (!link) return "no-link";
    if (link.dataset.bound === "1") return "already-bound";
    link.dataset.bound = "1";
    link.addEventListener("click", async (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      const src = document.getElementById("aniCopySrc");
      let txt = (src && (src.value || src.textContent)) ? (src.value || src.textContent) : "";
      if (!txt || !txt.trim()) {
        const feed = document.getElementById("aniFeed");
        txt = feed ? (feed.innerText || feed.textContent || "") : "";
      }
      const r = await window.aniCopyTextRobust(txt);
      // tiny feedback swap
      const old = link.textContent;
      link.textContent = r.ok ? "Copied ✓" : "Copy failed";
      setTimeout(() => { link.textContent = old; }, 900);
    }, {passive:false});
    return "bound";
  }catch(e){
    return "bind-failed";
  }
};
// ---------- Project remembering ----------
window.aniRememberProject = function(displayName) {
  const disp = (displayName || "").trim() || "General";
  const key = toKey(disp);
  lsSet(ANI_LS_CURPROJ, disp);
  lsSet(ANI_LS_CURKEY, key);
  const ls = aniLsGetProjects();
  const merged = [...new Set([...ls, disp].map(x => ("" + x).trim()).filter(Boolean))]
                .sort((a,b)=>a.localeCompare(b));
  aniLsSetProjects(merged);
  return JSON.stringify({ display: disp, key });
};
window.aniPickVaultFolder = async function() {
  try {
    if (window.AniAndroid && typeof window.AniAndroid.pickVaultFolder === "function") {
      return window.AniAndroid.pickVaultFolder();
    }
    if (window.Android && typeof window.Android.pickVaultFolder === "function") {
      return window.Android.pickVaultFolder();
    }
  } catch(e) {}
  return "Vault picker not available in this environment.";
};
window.aniListVaultProjects = async function () {
  if (window.aniIsAndroidHost()) {
    try {
      const raw = window.AniAndroid.listProjects(); // JSON array string of KEYS
      try {
        const arr = JSON.parse(raw || "[]") || [];
        if (Array.isArray(arr)) {
          const dispArr = arr.map(k => toDisplayFromKey(k));
          const ls = aniLsGetProjects();
          const merged = [...new Set([...ls, ...dispArr].map(x => (""+x).trim()).filter(Boolean))]
                        .sort((a,b)=>a.localeCompare(b));
          aniLsSetProjects(merged);
        }
      } catch(e) {}
      return raw || "[]";
    } catch(e) {
      return "[]";
    }
  }
  const disp = aniLsGetProjects();
  const keys = disp.map(d => toKey(d));
  return JSON.stringify(keys);
};
window.aniBankToVault = async function(mdText, htmlText, baseName, projectDisplay) {
  const disp = (projectDisplay || "").trim() || "General";
  const key = toKey(disp);
  window.aniRememberProject(disp);
  if (window.aniIsAndroidHost()) {
    try {
      if (window.AniAndroid && typeof window.AniAndroid.bankToVault === "function") {
        const res = window.AniAndroid.bankToVault(mdText||"", htmlText||"", baseName||"ani-session", key);
        try { await window.aniListVaultProjects(); } catch(e){}
        return res || "Saved ✓";
      }
      if (window.AniAndroid && typeof window.AniAndroid.bankToDownloads === "function") {
        const res = window.AniAndroid.bankToDownloads(mdText||"", htmlText||"", baseName||"ani-session", key);
        try { await window.aniListVaultProjects(); } catch(e){}
        return res || "Saved ✓";
      }
      return "Android host missing bankToVault().";
    } catch(e) {
      return "Android save failed.";
    }
  }
  return "Saved (browser memory) ✓ — project remembered.";
};
// ---------------- Draft autosave/restore ----------------
window.aniDraftSave = function(projectDisplay, historyRows, qaMdState) {
  try {
    const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
    const key = draftKeyForProject(disp);
    const payload = {
      v: 1,
      project: disp,
      savedAt: new Date().toISOString(),
      history: historyRows || [],
      qa: qaMdState || ""
    };
    lsSet(key, JSON.stringify(payload));
    return "draft-saved";
  } catch(e) {
    return "draft-save-failed";
  }
};
window.aniDraftLoad = function(projectDisplay) {
  try {
    const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
    const key = draftKeyForProject(disp);
    const raw = lsGet(key, "");
    if (!raw) return JSON.stringify({ found:false, project:disp });
    const parsed = JSON.parse(raw);
    if (!parsed || !parsed.history) return JSON.stringify({ found:false, project:disp });
    return JSON.stringify({ found:true, project:disp, savedAt: parsed.savedAt || null, history: parsed.history || [], qa: parsed.qa || "" });
  } catch(e) {
    return JSON.stringify({ found:false, error: ""+e });
  }
};
window.aniDraftClear = function(projectDisplay) {
  const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
  const key = draftKeyForProject(disp);
  lsDel(key);
  return "draft-cleared";
};
// ---------- Session ID persistence ----------
window.aniSessionIdLoad = function(projectDisplay){
  const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
  const key = sessionKeyForProject(disp);
  const v = (lsGet(key, "") || "").trim();
  return v;
};
window.aniSessionIdSave = function(projectDisplay, sessionId){
  const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
  const key = sessionKeyForProject(disp);
  const sid = (sessionId || "").trim();
  if (!sid) return "no-sid";
  lsSet(key, sid);
  return "saved";
};
window.aniSessionIdClear = function(projectDisplay){
  const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
  const key = sessionKeyForProject(disp);
  lsDel(key);
  return "cleared";
};
// ---------------- HTML Project Picker ----------------
function getProjectEl(){ return document.getElementById("aniProjectSelect"); }
function buildDisplayListFromKeys(keys) {
  let display = (keys || []).map(k => toDisplayFromKey(k));
  if (!display.includes("General")) display.unshift("General");
  const remembered = aniLsGetProjects();
  for (const d of remembered) {
    const s = (""+d).trim();
    if (s && !display.includes(s)) display.push(s);
  }
  display = [...new Set(display)].sort((a,b) => {
    if (a === "General" && b !== "General") return -1;
    if (b === "General" && a !== "General") return 1;
    return a.localeCompare(b);
  });
  return display;
}
window.aniRenderProjectPicker = async function() {
  let keysRaw = "[]";
  try { keysRaw = await window.aniListVaultProjects(); } catch(e) { keysRaw = "[]"; }
  let keys = [];
  try {
    const parsed = JSON.parse(keysRaw || "[]");
    if (Array.isArray(parsed)) keys = parsed.map(x => (""+x).trim()).filter(Boolean);
  } catch(e) {}
  const display = buildDisplayListFromKeys(keys);
  let cur = aniGetCurrentDisplay();
  if (!display.includes(cur)) cur = "General";
  window.aniRememberProject(cur);
  const el = getProjectEl();
  if (!el) return { ok:false, msg:"Project picker element not found" };
  el.innerHTML = "";
  for (const d of display) {
    const opt = document.createElement("option");
    opt.value = d;
    opt.textContent = d;
    if (d === cur) opt.selected = true;
    el.appendChild(opt);
  }
  return { ok:true, current:cur, count:display.length };
};
window.aniProjectFromUI = function() {
  const el = getProjectEl();
  const val = (el && el.value) ? el.value : "General";
  window.aniRememberProject(val);
  return val;
};
window.aniProjectSelect = async function(displayName) {
  const disp = (displayName || "").trim() || "General";
  window.aniRememberProject(disp);
  await window.aniRenderProjectPicker();
  return disp;
};
window.aniStatusLine = async function() {
  const r = await window.aniRenderProjectPicker();
  if (r && r.ok) return `Project: **${r.current}**`;
  return "Project: **General**";
};
// ---------------- Saved sessions picker ----------------
window.aniAutobankGet = function(projectDisplay){
  const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
  const key = autobankKeyForProject(disp);
  const raw = (lsGet(key, "0") || "").trim();
  return (raw === "1" || raw.toLowerCase() === "true");
};
window.aniAutobankSet = function(projectDisplay, enabled){
  const disp = (projectDisplay || "").trim() || aniGetCurrentDisplay() || "General";
  const key = autobankKeyForProject(disp);
  lsSet(key, enabled ? "1" : "0");
  return enabled ? "1" : "0";
};
window.aniEnsureProjectFolder = function(projectDisplay){
  const disp = (projectDisplay || "").trim() || "General";
  const key = toKey(disp);
  window.aniRememberProject(disp);
  if (window.aniIsAndroidHost()) {
    try {
      if (window.AniAndroid && typeof window.AniAndroid.ensureProject === "function") {
        window.AniAndroid.ensureProject(key);
        return `✅ Folder ensured: ${key}`;
      }
      if (window.AniAndroid && typeof window.AniAndroid.createProject === "function") {
        window.AniAndroid.createProject(key);
        return `✅ Folder ensured: ${key}`;
      }
      if (window.Android && typeof window.Android.ensureProject === "function") {
        window.Android.ensureProject(key);
        return `✅ Folder ensured: ${key}`;
      }
      if (window.Android && typeof window.Android.createProject === "function") {
        window.Android.createProject(key);
        return `✅ Folder ensured: ${key}`;
      }
      return "⚠️ Android bridge missing ensureProject/createProject";
    } catch (e) {
      return `⚠️ ensureProject failed: ${e}`;
    }
  }
  return "ℹ️ Browser mode: can't create folders on disk.";
};
window.aniRefreshProjectsStrict = function() {
  if (!window.aniIsAndroidHost()) {
    return JSON.stringify({ ok:false, code:"NO_DISK" });
  }
  try {
    let raw = null;
    if (window.AniAndroid && typeof window.AniAndroid.listProjects === "function") {
      raw = window.AniAndroid.listProjects();
    } else if (window.Android && typeof window.Android.listProjects === "function") {
      raw = window.Android.listProjects();
    }
    if (raw == null) return JSON.stringify({ ok:false, code:"NO_PERMISSION" });
    const parsed = JSON.parse(raw || "[]");
    if (!Array.isArray(parsed)) return JSON.stringify({ ok:false, code:"BAD_DATA" });
    const keys = parsed.map(k => (""+k).trim()).filter(Boolean);
    const display = keys.map(k => toDisplayFromKey(k));
    let list = [...new Set(display)];
    list = list.filter(Boolean).sort((a,b) => a.localeCompare(b));
    if (!list.includes("General")) list.unshift("General");
    const current = aniGetCurrentDisplay();
    const next = list.includes(current) ? current : "General";
    aniLsSetProjects(list);
    lsSet(ANI_LS_CURPROJ, next);
    lsSet(ANI_LS_CURKEY, toKey(next));
    return JSON.stringify({ ok:true, count:list.length, current:next, empty:list.length === 0 });
  } catch (_e) {
    return JSON.stringify({ ok:false, code:"BAD_DATA" });
  }
};
window.aniFormatRefreshProjectsStatus = function(resultJson){
  try {
    const obj = JSON.parse(resultJson || "{}") || {};
    if (obj.ok) {
      if (obj.empty) {
        return "✅ Projects refreshed: no folders found. Fix: Create a project or tap "Grant vault" to choose the correct folder.";
      }
      return `✅ Projects refreshed from vault folders (${obj.count || 0})`;
    }
    switch (obj.code) {
      case "NO_PERMISSION":
        return "⚠️ Vault not accessible. Fix: Tap "Grant vault" and re-select your vault folder, then press ↻ Sync.";
      case "NO_DISK":
        return "ℹ️ Disk refresh isn't available in browser mode. Fix: Use the Android app for vault folder listing.";
      case "BAD_DATA":
      default:
        return "⚠️ Couldn't read project list. Fix: Tap "Grant vault", then press ↻ Sync again.";
    }
  } catch (_e) {
    return "⚠️ Couldn't read project list. Fix: Tap "Grant vault", then press ↻ Sync again.";
  }
};
window.aniListSessions = async function(projectDisplay){
  const dispRaw = (projectDisplay || "").trim();
  const key = dispRaw ? toKey(dispRaw) : "";
  if (window.aniIsAndroidHost()) {
    try {
      if (window.AniAndroid && typeof window.AniAndroid.listSessions === "function") {
        return window.AniAndroid.listSessions(key) || "[]";
      }
      if (window.Android && typeof window.Android.listSessions === "function") {
        return window.Android.listSessions(key) || "[]";
      }
    } catch(e) {}
  }
  return "[]";
};
window.aniRenderSessionPicker = async function(projectDisplay){
  const wrap = document.getElementById("aniSessionsWrap");
  const select = document.getElementById("aniSessionSelect");
  if (!wrap || !select) return { ok:false, msg:"Session picker element not found" };
  let raw = "[]";
  try { raw = await window.aniListSessions(projectDisplay); } catch(e) { raw = "[]"; }
  let items = [];
  try {
    const parsed = JSON.parse(raw || "[]");
    if (Array.isArray(parsed)) items = parsed;
  } catch(e) {}
  select.innerHTML = "";
  wrap.style.display = "";
  if (!items.length) {
    const opt = document.createElement("option");
    opt.value = "";
    opt.textContent = "No sessions found";
    select.appendChild(opt);
    return { ok:true, count:0 };
  }
  for (const it of items) {
    const name = (it && (it.name || it.label)) ? (it.name || it.label) : ("" + it);
    if (!name) continue;
    const opt = document.createElement("option");
    opt.value = name;
    opt.textContent = (it && it.label) ? it.label : name;
    select.appendChild(opt);
  }
  return { ok:true, count: items.length };
};
window.aniSessionFromUI = function(){
  const el = document.getElementById("aniSessionSelect");
  return (el && el.value) ? el.value : "";
};
window.aniLoadSession = async function(projectDisplay, filename){
  const disp = (projectDisplay || "").trim() || "General";
  const key = toKey(disp);
  const fn = (filename || "").trim();
  if (!fn) return "";
  if (window.aniIsAndroidHost()) {
    try {
      if (window.AniAndroid && typeof window.AniAndroid.readSession === "function") {
        return window.AniAndroid.readSession(key, fn) || "";
      }
      if (window.Android && typeof window.Android.readSession === "function") {
        return window.Android.readSession(key, fn) || "";
      }
    } catch(e) {}
  }
  return "";
};

// ============== AUTO-INIT ON LOAD ==============
// This runs automatically when the page loads to populate everything
window.aniAutoInit = async function() {
  console.log("AnI Auto-Init starting...");
  
  try {
    // 1. Force refresh from Android vault
    if (window.aniIsAndroidHost()) {
      const refreshResult = window.aniRefreshProjectsStrict();
      console.log("Refresh result:", refreshResult);
    }
    
    // 2. Render project picker
    await window.aniRenderProjectPicker();
    console.log("Project picker rendered");
    
    // 3. Update Gradio's project display
    const currentProj = window.aniProjectFromUI();
    window.aniSetProjectDisplay(currentProj);
    console.log("Current project:", currentProj);
    
    // 4. Render session picker for current project
    await window.aniRenderSessionPicker(currentProj);
    console.log("Session picker rendered");
    
    console.log("AnI Auto-Init complete!");
    return "✅ Auto-init complete";
  } catch(e) {
    console.error("AnI Auto-Init error:", e);
    return "⚠️ Auto-init error: " + e;
  }
};

// Run auto-init after DOM is ready and elements exist
function waitForElement(selector, callback, maxWait = 10000) {
  const startTime = Date.now();
  const checkInterval = setInterval(() => {
    const el = document.querySelector(selector);
    if (el) {
      clearInterval(checkInterval);
      callback();
    } else if (Date.now() - startTime > maxWait) {
      clearInterval(checkInterval);
      console.error("Timeout waiting for element:", selector);
    }
  }, 100);
}

// Wait for the project select element to exist, then run auto-init
waitForElement('#aniProjectSelect', () => {
  console.log("Project select element found, running auto-init...");
  window.aniAutoInit();
});