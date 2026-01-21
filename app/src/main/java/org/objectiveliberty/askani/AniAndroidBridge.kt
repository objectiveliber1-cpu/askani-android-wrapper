package jp.nonbili.nora  // <-- change to your actual package

import android.content.Context
import android.os.Environment
import android.webkit.JavascriptInterface
import org.json.JSONArray
import java.io.File

class AniAndroidBridge(private val ctx: Context) {

    private fun projectsRoot(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        // Downloads/AnI/Projects
        return File(File(downloads, "AnI"), "Projects")
    }

    private fun ensureDirs(projectSafe: String): Pair<File, File> {
        val root = projectsRoot()
        val projDir = File(root, projectSafe)
        val sessionsDir = File(projDir, "Sessions")
        val exportsDir = File(projDir, "Exports")
        sessionsDir.mkdirs()
        exportsDir.mkdirs()
        return Pair(sessionsDir, exportsDir)
    }

    @JavascriptInterface
    fun listProjects(): String {
        return try {
            val root = projectsRoot()
            if (!root.exists()) return "[]"
            val dirs = root.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
            val arr = JSONArray()
            dirs.sorted().forEach { arr.put(it) }
            arr.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    @JavascriptInterface
    fun bankToDownloads(mdText: String?, htmlText: String?, baseName: String?, projectSafe: String?): String {
        return try {
            val md = mdText ?: ""
            val html = htmlText ?: ""
            val base = baseName ?: "ani-session"
            val proj = projectSafe ?: "General"

            val (sessionsDir, exportsDir) = ensureDirs(proj)

            val mdFile = File(sessionsDir, "$base.md")
            val htmlFile = File(exportsDir, "$base.html")

            mdFile.writeText(md, Charsets.UTF_8)
            htmlFile.writeText(html, Charsets.UTF_8)

            "Saved to Downloads/AnI/Projects/$proj/Sessions/$base.md ✓"
        } catch (e: Exception) {
            "Vault: Android save failed (${e.javaClass.simpleName})."
        }
    }
}
