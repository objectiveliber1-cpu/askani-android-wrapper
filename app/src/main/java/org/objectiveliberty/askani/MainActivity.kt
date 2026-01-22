import uuid
import httpx
import gradio as gr
from datetime import datetime, timezone
import re
import os
import tempfile
import hashlib
import json

API = "http://127.0.0.1:8000"

# ============================================================
# Vault / Bank JS
# - Android: AniAndroid bridge (SAF)
# - Browser: File System Access API when available + localStorage fallback
# ============================================================
VAULT_JS = r"""
// ===== AnI Vault helpers =====

window.aniIsAndroidHost = function () {
  return !!(window.AniAndroid && typeof window.AniAndroid.bankToDownloads === "function");
};

window.__aniVaultDirHandle = window.__aniVaultDirHandle || null;

window.aniVaultSupported = function () {
  return !!window.showDirectoryPicker;
};

// ----------------------------
// Project list persistence (browser fallback)
// ----------------------------
function aniLoadLocalProjects() {
  try {
    const raw = localStorage.getItem("ani_projects") || "[]";
    const arr = JSON.parse(raw);
    if (Array.isArray(arr)) return arr.map(String).filter(Boolean);
  } catch (e) {}
  return [];
}
function aniSaveLocalProjects(arr) {
  try {
    const clean = Array.from(new Set((arr || []).map(String).filter(Boolean)));
    localStorage.setItem("ani_projects", JSON.stringify(clean));
  } catch (e) {}
}
window.aniRememberProject = function(name){
  if (!name) return;
  const cur = aniLoadLocalProjects();
  cur.push(String(name));
  aniSaveLocalProjects(cur);
};

// ----------------------------
// Vault picker
// ----------------------------
window.aniPickVaultFolder = async function () {
  if (window.aniIsAndroidHost()) {
    // triggers SAF picker in native
    try {
      if (typeof window.AniAndroid.pickVaultFolder === "function") {
        return window.AniAndroid.pickVaultFolder();
      }
      return "Android host detected. (No picker method.)";
    } catch (e) {
      return "Android host detected. (Picker failed.)";
    }
  }

  if (!window.aniVaultSupported()) {
    return "Vault unavailable in this browser. (Some embedded pages can’t use folder pickers.)";
  }

  try {
    const handle = await window.showDirectoryPicker({ mode: "readwrite" });
    window.__aniVaultDirHandle = handle;
    return "Vault folder selected ✓";
  } catch (e) {
    return "Vault folder not selected.";
  }
};

async function aniGetOrCreateDir(root, name) {
  return await root.getDirectoryHandle(name, { create: true });
}
async function aniTryGetDir(root, name) {
  try { return await root.getDirectoryHandle(name, { create: false }); }
  catch (e) { return null; }
}
async function aniWriteTextFile(dirHandle, filename, text) {
  const fileHandle = await dirHandle.getFileHandle(filename, { create: true });
  const writable = await fileHandle.createWritable();
  await writable.write(text);
  await writable.close();
}

// ----------------------------
// List projects
// - Android: AniAndroid.listProjects()
// - Desktop: reads folders AnI/Projects/* within chosen handle
// - Browser fallback: localStorage
// ----------------------------
window.aniListVaultProjects = async function () {
  // Android path
  if (window.aniIsAndroidHost()) {
    try {
      if (typeof window.AniAndroid.listProjects === "function") {
        const raw = window.AniAndroid.listProjects();
        // also mirror into localStorage so UI remains stable
        try {
          const arr = JSON.parse(raw || "[]");
          if (Array.isArray(arr)) aniSaveLocalProjects(arr);
        } catch(e) {}
        return raw || "[]";
      }
    } catch (e) {}
    // fall back
    return JSON.stringify(aniLoadLocalProjects());
  }

  // Desktop path
  if (window.aniVaultSupported() && window.__aniVaultDirHandle) {
    try {
      const root = window.__aniVaultDirHandle;

      // user might pick AnI directly or parent. Try to find Projects robustly.
      let aniDir = await aniTryGetDir(root, "AnI");
      let projectsDir = null;

      // if they picked AnI itself, it may already contain Projects
      const maybeProjects = await aniTryGetDir(root, "Projects");
      if (maybeProjects) {
        projectsDir = maybeProjects;
      } else if (aniDir) {
        projectsDir = await aniTryGetDir(aniDir, "Projects");
      } else {
        // if neither exists, nothing yet
        projectsDir = null;
      }

      if (!projectsDir) {
        return JSON.stringify(aniLoadLocalProjects());
      }

      const out = [];
      for await (const [name, handle] of projectsDir.entries()) {
        if (handle && handle.kind === "directory") out.push(name);
      }
      out.sort((a,b)=>a.localeCompare(b));
      aniSaveLocalProjects(out);
      return JSON.stringify(out);
    } catch (e) {
      return JSON.stringify(aniLoadLocalProjects());
    }
  }

  // fallback
  return JSON.stringify(aniLoadLocalProjects());
};

// ----------------------------
// Unified Bank
// ----------------------------
window.aniBankToVault = async function (mdText, htmlText, baseName, projectSafe) {
  const md = mdText || "";
  const html = htmlText || "";
  const base = baseName || "ani-session";
  const proj = projectSafe || "General";

  // remember project in browser regardless
  window.aniRememberProject(proj);

  // Android path
  if (window.aniIsAndroidHost()) {
    try {
      const msg = window.AniAndroid.bankToDownloads(md, html, base, proj);
      return msg || "Saved ✓";
    } catch (e) {
      return "Vault: Android save failed.";
    }
  }

  // Desktop path
  if (!window.aniVaultSupported()) {
    return "Vault unsupported here. (Browser sandbox) Use Export / Print.";
  }

  try {
    let root = window.__aniVaultDirHandle;
    if (!root) {
      root = await window.showDirectoryPicker({ mode: "readwrite" });
      window.__aniVaultDirHandle = root;
    }

    // robust: if root is already AnI folder it may contain Projects;
    // otherwise create AnI/Projects
    let projectsDir = await aniTryGetDir(root, "Projects");
    if (!projectsDir) {
      const aniDir = await aniGetOrCreateDir(root, "AnI");
      projectsDir = await aniGetOrCreateDir(aniDir, "Projects");
    }

    const projDir = await aniGetOrCreateDir(projectsDir, proj);
    const sessionsDir = await aniGetOrCreateDir(projDir, "Sessions");
    const exportsDir = await aniGetOrCreateDir(projDir, "Exports");

    await aniWriteTextFile(sessionsDir, `${base}.md`, md);
    await aniWriteTextFile(exportsDir, `${base}.html`, html);

    return `Saved ✓ (Projects/${proj}/Sessions/${base}.md)`;
  } catch (e) {
    return "Vault save failed.";
  }
};

// Optional: native calls this after SAF grant
window.aniOnVaultReady = function(ok){
  // nothing required; UI triggers refresh explicitly via Gradio
  return ok ? "Vault ready" : "Vault not ready";
};
"""

# ============================================================
# Helpers
# ============================================================
def _now_ts_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

def _safe_filename(s: str) -> str:
    s = (s or "").strip()
    if not s:
        return "untitled"
    s = s.lower()
    s = re.sub(r"[^a-z0-9._-]+", "-", s)
    s = re.sub(r"-{2,}", "-", s).strip("-")
    return s[:80] or "untitled"

# History row format:
# [include(bool), ts(str), role(str), content(str), provider(str), model(str), project(str)]
def _row(include=True, ts="", role="user", content="", provider="", model="", project=""):
    return [bool(include), str(ts), str(role), str(content), str(provider), str(model), str(project)]

def _unpack_row(row):
    if not isinstance(row, (list, tuple)):
        return (False, "", "user", "", "", "", "")
    if len(row) >= 7:
        include, ts, role, content, provider, model, project = row[:7]
        return (bool(include), str(ts), str(role), str(content), str(provider), str(model), str(project))
    include = bool(row[0]) if len(row) > 0 else False
    role = str(row[1]) if len(row) > 1 else "user"
    content = str(row[2]) if len(row) > 2 else ""
    provider = str(row[3]) if len(row) > 3 else ""
    model = str(row[4]) if len(row) > 4 else ""
    return (include, "", role, content, provider, model, "")

def _filter_included_rows(history_rows):
    out = []
    for row in (history_rows or []):
        include, ts, role, content, provider, model, project = _unpack_row(row)
        if include:
            out.append(_row(True, ts, role, content, provider, model, project))
    return out

def _history_to_context(history_rows, show_sources_to_model: bool, allowed_projects=None):
    allowed = None
    if allowed_projects:
        allowed = {p for p in allowed_projects if (p or "").strip()}
    msgs = []
    for row in (history_rows or []):
        include, ts, role, content, provider, model, project = _unpack_row(row)
        if not include:
            continue
        if allowed is not None and (project or "").strip() not in allowed:
            continue
        role = (role or "user").strip()
        content = content or ""
        if show_sources_to_model and role == "assistant":
            pm = "/".join([p for p in [provider, model] if p])
            if pm:
                content = f"[{pm}] {content}"
        msgs.append({"role": role, "content": content})
    return msgs

def toggle_advanced(is_visible: bool):
    is_visible = bool(is_visible)
    return (not is_visible), gr.update(visible=not is_visible)

def _normalize_provider_for_backend(label: str) -> str:
    if not label or label == "Easy Mode":
        return "auto"
    lowered = label.lower().strip()
    if lowered == "openai": return "openai"
    if lowered == "gemini": return "gemini"
    if lowered == "groq": return "groq"
    if lowered == "xai": return "xai"
    if lowered == "openrouter": return "openrouter"
    if lowered == "together": return "together"
    if lowered == "perplexity": return "perplexity"
    return lowered

def _backend_label(p: str) -> str:
    p = (p or "").lower().strip()
    if p == "openai": return "OpenAI"
    if p == "gemini": return "Gemini"
    if p == "groq": return "Groq"
    if p == "xai": return "xAI"
    if p == "openrouter": return "OpenRouter"
    if p == "together": return "Together"
    if p == "perplexity": return "Perplexity"
    if p == "auto": return "Easy Mode"
    return p or "Unknown"

def on_mode_change(mode_label: str):
    mode = (mode_label or "").strip()
    if mode == "Easy Mode":
        return (
            gr.update(visible=False),
            gr.update(visible=False, value=""),
            gr.update(visible=False),
            gr.update(visible=True),
        )
    if mode == "Compare Mode":
        return (
            gr.update(visible=True),
            gr.update(visible=False, value=""),
            gr.update(visible=False),
            gr.update(visible=False),
        )
    return (
        gr.update(visible=False),
        gr.update(visible=True),
        gr.update(visible=False),
        gr.update(visible=False),
    )

def _render_compare_markdown(provider_responses):
    blocks = []
    for item in provider_responses:
        human = item.get("provider_label") or _backend_label(item.get("provider_key"))
        model = (item.get("model") or "").strip()
        header = f"### {human} ({model})" if model else f"### {human}"
        err = item.get("error")
        if err:
            blocks.append(f"{header}\n\n⚠️ **Error:** `{err}`")
        else:
            blocks.append(f"{header}\n\n{(item.get('text') or '').strip()}")
    return "\n\n---\n\n".join(blocks)

def _rebuild_qa_md(qa_state):
    qa_blocks = []
    for entry in reversed(qa_state or []):
        q = entry.get("question", "")
        a = entry.get("answer", "")
        ts = entry.get("ts", "")
        proj = entry.get("project", "")
        meta_bits = []
        if ts: meta_bits.append(ts)
        if proj: meta_bits.append(f"Project: **{proj}**")
        meta = " · ".join(meta_bits)
        if meta:
            meta = f"*{meta}*\n\n"
        qa_blocks.append(
            f"{meta}"
            "#### Q\n\n"
            "```text\n"
            f"{q}\n"
            "```\n\n"
            f"{a}\n\n"
            "---"
        )
    return "\n".join(qa_blocks)

def _session_markdown(history_rows, project_name: str, session_id: str):
    lines = []
    lines.append("# AnI Session Export")
    if project_name:
        lines.append(f"**Project:** {project_name}")
    lines.append(f"**Session ID:** {session_id}")
    lines.append(f"**Exported:** {_now_ts_utc()}")
    lines.append("")
    lines.append("---")
    lines.append("")
    for row in (history_rows or []):
        include, ts, role, content, provider, model, project = _unpack_row(row)
        if not include:
            continue
        role_label = "User" if role == "user" else "Assistant"
        src = ""
        if role == "assistant":
            pm = "/".join([p for p in [provider, model] if p])
            if pm:
                src = f" ({pm})"
        meta_bits = []
        if ts: meta_bits.append(ts)
        if project: meta_bits.append(f"Project: {project}")
        meta = " · ".join(meta_bits)
        lines.append(f"## {role_label}{src}")
        if meta:
            lines.append(f"*{meta}*")
        lines.append("")
        lines.append(content or "")
        lines.append("")
        lines.append("---")
        lines.append("")
    return "\n".join(lines)

def _session_html(history_rows, project_name: str, session_id: str):
    def esc(s: str) -> str:
        return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    parts = []
    parts.append("<!doctype html><html><head><meta charset='utf-8'/>")
    parts.append("<meta name='viewport' content='width=device-width,initial-scale=1'/>")
    parts.append("<title>AnI Session Export</title>")
    parts.append(
        "<style>"
        "body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial,sans-serif;max-width:900px;margin:24px auto;padding:0 16px;}"
        "h1{margin:0 0 8px;} .meta{color:#555;margin-bottom:16px;} "
        ".msg{border-top:1px solid #ddd;padding:12px 0;} .role{font-weight:700;margin:0 0 6px;} "
        ".small{color:#666;font-size:0.92em;margin:0 0 8px;} pre{white-space:pre-wrap;word-wrap:break-word;}"
        "</style>"
    )
    parts.append("</head><body>")
    parts.append("<h1>AnI Session Export</h1>")
    parts.append("<div class='meta'>")
    if project_name:
        parts.append(f"<div><b>Project:</b> {esc(project_name)}</div>")
    parts.append(f"<div><b>Session ID:</b> {esc(session_id)}</div>")
    parts.append(f"<div><b>Exported:</b> {esc(_now_ts_utc())}</div>")
    parts.append("</div>")
    for row in (history_rows or []):
        include, ts, role, content, provider, model, project = _unpack_row(row)
        if not include:
            continue
        role_label = "User" if role == "user" else "Assistant"
        pm = "/".join([p for p in [provider, model] if p])
        parts.append("<div class='msg'>")
        parts.append(f"<div class='role'>{esc(role_label)}{(' — ' + esc(pm)) if (pm and role=='assistant') else ''}</div>")
        meta_bits = []
        if ts: meta_bits.append(ts)
        if project: meta_bits.append(f"Project: {project}")
        if meta_bits:
            parts.append(f"<div class='small'>{esc(' · '.join(meta_bits))}</div>")
        parts.append(f"<pre>{esc(content)}</pre>")
        parts.append("</div>")
    parts.append("</body></html>")
    return "".join(parts)

def export_session_files(history_rows, project_name: str, session_id: str):
    proj_safe = _safe_filename(project_name or "session")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    base = f"ani-{proj_safe}-{stamp}"
    md = _session_markdown(history_rows, project_name, session_id)
    html = _session_html(history_rows, project_name, session_id)
    out_dir = tempfile.mkdtemp(prefix="ani_export_")
    md_path = os.path.join(out_dir, f"{base}.md")
    html_path = os.path.join(out_dir, f"{base}.html")
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(md)
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(html)
    return md_path, html_path

def _history_signature(filtered_rows, project_name: str, session_id: str) -> str:
    payload = {"project": project_name or "", "session_id": session_id or "", "rows": filtered_rows or []}
    b = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return hashlib.sha256(b).hexdigest()

def prepare_bank(history_rows, project_name: str, session_id: str):
    filtered = _filter_included_rows(history_rows or [])
    project = (project_name or "").strip() or "General"
    proj_safe = _safe_filename(project)
    sid = session_id or "default-session"
    md_text = _session_markdown(filtered, project, sid)
    html_text = _session_html(filtered, project, sid)
    md_path, html_path = export_session_files(filtered, project, sid)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    base = f"ani-{proj_safe}-{stamp}"
    return md_text, html_text, base, proj_safe, md_path, html_path

def merge_discovered_projects(discovered_json: str, projects_list, current_project):
    projects_list = projects_list or ["General"]
    current_project = current_project or "General"
    try:
        found = json.loads(discovered_json or "[]")
        if not isinstance(found, list):
            found = []
        found = [str(x).strip() for x in found if str(x).strip()]
    except Exception:
        found = []

    merged = list(projects_list)
    for p in found:
        if p not in merged:
            merged.append(p)
    if "General" not in merged:
        merged.insert(0, "General")

    cur = current_project if current_project in merged else "General"
    return (
        merged,
        gr.update(choices=merged, value=cur),
        gr.update(choices=merged, value=cur),
        gr.update(choices=merged, value=cur),
    )

def export_click(history_rows, current_project, session_id):
    filtered = _filter_included_rows(history_rows or [])
    md_path, html_path = export_session_files(filtered, current_project or "", session_id or "default-session")
    return md_path, html_path

# ============================================================
# Core chat flow
# ============================================================
def send(
    mode_label,
    compare_choices,
    model_override,
    user_text,
    system_text,
    temperature,
    max_tokens,
    include_history_master,
    show_sources_master,
    current_project,
    session_id,
    history_rows,
    qa_state,
    last_banked_sig,
    autobank_enabled,
):
    qa_state = qa_state or []
    history_rows = history_rows or []
    last_banked_sig = last_banked_sig or ""

    user_text = (user_text or "").strip()
    if not user_text:
        return (gr.update(), history_rows, qa_state, user_text, False, "", "", "", "", last_banked_sig)

    mode = (mode_label or "").strip()
    model_override_str = (model_override or "").strip()
    proj = (current_project or "").strip() or "General"
    ts = _now_ts_utc()

    # Decide providers
    providers_to_call = []
    if mode == "Compare Mode":
        for label in (compare_choices or []):
            key = _normalize_provider_for_backend(label)
            if key and key not in ("auto", "compare") and key not in providers_to_call:
                providers_to_call.append(key)
        if not providers_to_call:
            qa_md = "⚠️ **Compare Mode:** No providers selected."
            return (qa_md, history_rows, qa_state, user_text, False, "", "", "", "", last_banked_sig)
    else:
        providers_to_call = [_normalize_provider_for_backend(mode)]

    msgs = []
    if system_text and system_text.strip():
        msgs.append({"role": "system", "content": system_text.strip()})
    msgs.append({"role": "user", "content": user_text})

    history_context = (
        _history_to_context(history_rows, bool(show_sources_master), allowed_projects=[proj])
        if include_history_master
        else []
    )

    # Add user message to history
    history_rows.append(_row(True, ts, "user", user_text, "", "", proj))

    provider_responses = []
    for provider_key in providers_to_call:
        payload = {
            "provider": provider_key,
            "model": (model_override_str or None) if mode not in ("Compare Mode", "Easy Mode") else None,
            "messages": msgs,
            "include_history": bool(include_history_master),
            "show_sources_to_model": bool(show_sources_master),
            "history": history_context,
            "temperature": float(temperature),
            "max_output_tokens": int(max_tokens),
            "conversation_id": str(uuid.uuid5(uuid.NAMESPACE_DNS, session_id or "default-session")),
        }
        try:
            r = httpx.post(API + "/chat", json=payload, timeout=180)
            if r.status_code >= 400:
                raise RuntimeError(f"HTTP {r.status_code}: {r.text[:300]}")
            data = r.json()
            text = (data.get("text", "") or "").strip()
            used_provider = (data.get("provider", provider_key) or provider_key).strip()
            used_model = (data.get("model", "") or "").strip()
            provider_responses.append({
                "provider_key": used_provider,
                "provider_label": _backend_label(used_provider),
                "model": used_model,
                "text": text,
                "error": None,
            })
            if mode != "Compare Mode":
                history_rows.append(_row(True, _now_ts_utc(), "assistant", text, used_provider, used_model, proj))
        except Exception as e:
            provider_responses.append({
                "provider_key": provider_key,
                "provider_label": _backend_label(provider_key),
                "model": "",
                "text": "",
                "error": f"{type(e).__name__}: {e}",
            })

    combined_answer_md = _render_compare_markdown(provider_responses)
    qa_state.append({"question": user_text, "answer": combined_answer_md, "ts": ts, "project": proj})
    qa_md = _rebuild_qa_md(qa_state)

    # Auto-bank (single-project, KISS)
    do_autobank = False
    ab_md = ab_html = ab_base = ab_proj = ""
    if bool(autobank_enabled) and mode != "Compare Mode":
        filtered = _filter_included_rows(history_rows or [])
        sig = _history_signature(filtered, proj, session_id or "default-session")
        if sig != last_banked_sig:
            md_text, html_text, base, proj_safe, _, _ = prepare_bank(history_rows, proj, session_id or "default-session")
            ab_md, ab_html, ab_base, ab_proj = md_text, html_text, base, proj_safe
            last_banked_sig = sig
            do_autobank = True

    return (qa_md, history_rows, qa_state, "", do_autobank, ab_md, ab_html, ab_base, ab_proj, last_banked_sig)

# ============================================================
# UI
# ============================================================
with gr.Blocks(title="AnI", js=VAULT_JS) as demo:
    gr.HTML("<style>#ani-root{max-width:980px;margin:0 auto;}</style>")

    qa_state = gr.State([])
    history_state = gr.State([])
    session_id = gr.State(str(uuid.uuid4()))

    projects_list = gr.State(["General"])
    current_project = gr.State("General")

    autobank_enabled = gr.State(False)
    last_banked_sig = gr.State("")

    with gr.Column(elem_id="ani-root"):
        with gr.Row():
            gr.Markdown("## AnI")
            burger = gr.Button("☰", size="sm")

        with gr.Row():
            mode = gr.Dropdown(
                ["Easy Mode", "OpenAI", "Gemini", "Groq", "Compare Mode"],
                value="Easy Mode",
                show_label=False,
                scale=1,
            )
            user_text = gr.Textbox(
                placeholder="Ask anything…",
                show_label=False,
                lines=1,
                scale=6,
                autofocus=True,
            )
            send_btn = gr.Button("Ask", variant="primary")

        # Minimal bank row (KISS)
        with gr.Row():
            bank_btn = gr.Button("Bank", variant="secondary", scale=6)
            auto_chk = gr.Checkbox(label="Auto", value=False, interactive=False, scale=1)
            bank_opts_btn = gr.Button("⋯", scale=1)

        bank_opts_open = gr.State(False)

        # Options (hidden by default; auto-hides after actions)
        with gr.Row(visible=False) as bank_opts_row:
            grant_btn = gr.Button("Grant Vault Access", variant="secondary")
            refresh_btn = gr.Button("Refresh Projects", variant="secondary")
            project_dropdown = gr.Dropdown(choices=["General"], value="General", label="Project", scale=2)
            new_project = gr.Textbox(placeholder="New project name…", label="New project (optional)", scale=3)
            select_project_btn = gr.Button("Select", variant="primary", scale=1)

        vault_status = gr.Markdown("", visible=False)

        # Hidden banking payload for JS call
        bank_md_text = gr.Textbox(visible=False)
        bank_html_text = gr.Textbox(visible=False)
        bank_base_name = gr.Textbox(visible=False)
        bank_project_safe = gr.Textbox(visible=False)

        # Auto-bank payload
        autobank_do = gr.Checkbox(value=False, visible=False)
        autobank_md_text = gr.Textbox(visible=False)
        autobank_html_text = gr.Textbox(visible=False)
        autobank_base_name = gr.Textbox(visible=False)
        autobank_project_safe = gr.Textbox(visible=False)

        # Advanced panel (kept minimal)
        with gr.Column(visible=False) as advanced_panel:
            include_history_master = gr.Checkbox(value=True, label="Include previous messages as context")
            show_sources_master = gr.Checkbox(value=False, label="Show sources to model")
            temperature = gr.Slider(0.0, 2.0, value=0.7, step=0.1, label="Temperature")
            max_tokens = gr.Slider(64, 4000, value=800, step=64, label="Max output tokens")
            system_text = gr.Textbox(label="System instruction (optional)", lines=3)

            with gr.Accordion("Export / Print", open=False):
                export_md_btn = gr.Button("Download Markdown", variant="primary")
                export_html_btn = gr.Button("Download HTML", variant="secondary")
                export_md_file = gr.File(label="Markdown export")
                export_html_file = gr.File(label="HTML export")

        qa_feed = gr.Markdown(value="", elem_id="ani-answers")

        # hidden bridge pipe for refresh results
        vault_projects_json = gr.Textbox(visible=False)

    # ---------- wiring ----------
    burger.click(lambda v: (not v, gr.update(visible=not v)), inputs=[advanced_panel], outputs=[advanced_panel, advanced_panel])

    def _toggle_bank_opts(open_):
        open_ = bool(open_)
        return (not open_), gr.update(visible=not open_), gr.update(visible=False, value="")
    bank_opts_btn.click(_toggle_bank_opts, inputs=[bank_opts_open], outputs=[bank_opts_open, bank_opts_row, vault_status])

    # select project (KISS): add to list, set current, hide options
    def _select_project(name, plist):
        name = (name or "").strip() or "General"
        plist = list(plist or ["General"])
        if name not in plist:
            plist.append(name)
        if "General" not in plist:
            plist.insert(0, "General")
        return plist, name, gr.update(choices=plist, value=name), "", False, gr.update(visible=False)
    select_project_btn.click(
        _select_project,
        inputs=[new_project, projects_list],
        outputs=[projects_list, current_project, project_dropdown, new_project, bank_opts_open, bank_opts_row],
    )

    project_dropdown.change(lambda v: v, inputs=[project_dropdown], outputs=[current_project])

    # Grant vault access (JS picker). After, immediately refresh projects list.
    grant_btn.click(
        fn=None,
        inputs=[],
        outputs=[vault_status],
        js="() => window.aniPickVaultFolder()",
    ).then(
        fn=None,
        inputs=[],
        outputs=[vault_projects_json],
        js="() => window.aniListVaultProjects()",
    ).then(
        merge_discovered_projects,
        inputs=[vault_projects_json, projects_list, current_project],
        outputs=[projects_list, project_dropdown, project_dropdown, project_dropdown],
    )

    # Refresh (hidden in opts) — also auto-hide opts afterwards
    refresh_btn.click(
        fn=None,
        inputs=[],
        outputs=[vault_projects_json],
        js="() => window.aniListVaultProjects()",
    ).then(
        merge_discovered_projects,
        inputs=[vault_projects_json, projects_list, current_project],
        outputs=[projects_list, project_dropdown, project_dropdown, project_dropdown],
    ).then(
        lambda: (False, gr.update(visible=False)),
        inputs=[],
        outputs=[bank_opts_open, bank_opts_row],
    )

    # Send
    send_evt = send_btn.click(
        send,
        inputs=[
            mode, gr.State([]), gr.State(""),
            user_text, system_text, temperature, max_tokens,
            include_history_master, show_sources_master,
            current_project, session_id,
            history_state, qa_state,
            last_banked_sig, autobank_enabled,
        ],
        outputs=[
            qa_feed, history_state, qa_state, user_text,
            autobank_do, autobank_md_text, autobank_html_text, autobank_base_name, autobank_project_safe,
            last_banked_sig,
        ],
    )

    # Auto-bank JS
    send_evt.then(
        fn=None,
        inputs=[autobank_do, autobank_md_text, autobank_html_text, autobank_base_name, autobank_project_safe],
        outputs=[],
        js=r"""
(doIt, md, html, base, projSafe) => {
  if (!doIt) return;
  window.aniRememberProject(projSafe);
  window.aniBankToVault(md, html, base, projSafe);
}
""",
    )

    # Manual Bank (banks to current project). After bank: enable Auto + refresh projects + hide opts.
    bank_btn.click(
        prepare_bank,
        inputs=[history_state, current_project, session_id],
        outputs=[bank_md_text, bank_html_text, bank_base_name, bank_project_safe, gr.State(None), gr.State(None)],
    ).then(
        fn=None,
        inputs=[bank_md_text, bank_html_text, bank_base_name, bank_project_safe],
        outputs=[vault_status],
        js="(md, html, base, projSafe) => window.aniBankToVault(md, html, base, projSafe)",
    ).then(
        lambda: gr.update(value=True, interactive=True),
        inputs=[],
        outputs=[auto_chk],
    ).then(
        lambda v: bool(v),
        inputs=[auto_chk],
        outputs=[autobank_enabled],
    ).then(
        fn=None,
        inputs=[],
        outputs=[vault_projects_json],
        js="() => window.aniListVaultProjects()",
    ).then(
        merge_discovered_projects,
        inputs=[vault_projects_json, projects_list, current_project],
        outputs=[projects_list, project_dropdown, project_dropdown, project_dropdown],
    ).then(
        lambda: (False, gr.update(visible=False)),
        inputs=[],
        outputs=[bank_opts_open, bank_opts_row],
    )

    # Auto checkbox
    auto_chk.change(lambda v: bool(v), inputs=[auto_chk], outputs=[autobank_enabled])

    # Enter to submit
    user_text.submit(
        send,
        inputs=[
            mode, gr.State([]), gr.State(""),
            user_text, system_text, temperature, max_tokens,
            include_history_master, show_sources_master,
            current_project, session_id,
            history_state, qa_state,
            last_banked_sig, autobank_enabled,
        ],
        outputs=[
            qa_feed, history_state, qa_state, user_text,
            autobank_do, autobank_md_text, autobank_html_text, autobank_base_name, autobank_project_safe,
            last_banked_sig,
        ],
    )

    # Exports
    export_md_btn.click(export_click, inputs=[history_state, current_project, session_id], outputs=[export_md_file, export_html_file])
    export_html_btn.click(export_click, inputs=[history_state, current_project, session_id], outputs=[export_md_file, export_html_file])

    # Initial: refresh projects from localStorage / Android if available (no prompts)
    demo.load(
        fn=None,
        inputs=[],
        outputs=[vault_projects_json],
        js="() => window.aniListVaultProjects()",
    ).then(
        merge_discovered_projects,
        inputs=[vault_projects_json, projects_list, current_project],
        outputs=[projects_list, project_dropdown, project_dropdown, project_dropdown],
    )

def main():
    demo.launch(server_name="0.0.0.0", server_port=7860, share=True)

if __name__ == "__main__":
    main()
