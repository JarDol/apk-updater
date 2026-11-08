package pl.jardol.apkupdater

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject

/** Jedna apka z repo GitHub. */
data class AppItem(
    val repo: String,               // np. JarDol/e2stream
    val name: String,
    val pkg: String,                // pusty, gdy brak updater.json
    val latest: String?,            // wersja z najnowszego release'u
    val apkName: String?,
    val apkUrl: String?,            // browser_download_url
    val apkApiUrl: String?,         // URL assetu w API (prywatne repo)
    val notes: String,
    val error: String?,
    // uzupełniane lokalnie:
    val installed: String? = null,
    val installedCode: Long = -1,
) {
    val isInstalled get() = installed != null
    val hasUpdate get() = isInstalled && apkUrl != null && Ver.newer(latest, installed)
    val canInstall get() = pkg.isNotEmpty() && apkUrl != null

    fun toJson(): JSONObject = JSONObject()
        .put("repo", repo).put("name", name).put("pkg", pkg)
        .put("latest", latest).put("apkName", apkName).put("apkUrl", apkUrl)
        .put("apkApiUrl", apkApiUrl).put("notes", notes).put("error", error)

    companion object {
        fun fromJson(o: JSONObject) = AppItem(
            repo = o.getString("repo"), name = o.optString("name"), pkg = o.optString("pkg"),
            latest = o.optStringOrNull("latest"), apkName = o.optStringOrNull("apkName"),
            apkUrl = o.optStringOrNull("apkUrl"), apkApiUrl = o.optStringOrNull("apkApiUrl"),
            notes = o.optString("notes"), error = o.optStringOrNull("error"),
        )
    }
}

fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotEmpty() }

/**
 * Lista apek: wszystkie repo konta [Prefs.owner] z tematem [Prefs.topic].
 * W każdym repo plik updater.json:  {"name": "...", "package": "...", "asset": "regex (opcjonalnie)"}
 * APK brane z najnowszego (nie draft, nie prerelease) GitHub Release.
 */
object Catalog {

    /** Pobiera świeżą listę z GitHuba (sieć — poza wątkiem UI). */
    fun refresh(ctx: Context): List<AppItem> {
        val p = Prefs(ctx)
        val token = p.token
        val owner = p.owner
        val reposUrl = if (token.isNotEmpty())
            "https://api.github.com/user/repos?per_page=100&affiliation=owner"
        else
            "https://api.github.com/users/$owner/repos?per_page=100&type=owner"

        val repos = JSONArray(Http.get(ctx, reposUrl, token))
        val out = ArrayList<AppItem>()
        for (i in 0 until repos.length()) {
            val r = repos.getJSONObject(i)
            if (r.optBoolean("archived")) continue
            val full = r.getString("full_name")
            if (!full.substringBefore('/').equals(owner, ignoreCase = true)) continue
            val topics = r.optJSONArray("topics") ?: continue
            if ((0 until topics.length()).none { topics.optString(it) == p.topic }) continue
            out.add(readRepo(ctx, full, r.optString("default_branch", "main"), r.optString("name"), token))
        }
        out.sortWith(compareBy<AppItem>({ it.pkg != BuildConfigPkg.SELF }, { it.name.lowercase() }))
        p.catalogJson = JSONArray().apply { out.forEach { put(it.toJson()) } }.toString()
        p.lastCheck = System.currentTimeMillis()
        return withInstalled(ctx, out)
    }

    /** Ostatnio pobrana lista (bez sieci). */
    fun cached(ctx: Context): List<AppItem> {
        val s = Prefs(ctx).catalogJson
        if (s.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(s)
            withInstalled(ctx, (0 until a.length()).map { AppItem.fromJson(a.getJSONObject(it)) })
        } catch (_: Exception) { emptyList() }
    }

    /** Uzupełnia zainstalowane wersje (lokalnie, szybkie). */
    fun withInstalled(ctx: Context, items: List<AppItem>): List<AppItem> {
        val pm = ctx.packageManager
        return items.map { item ->
            if (item.pkg.isEmpty()) return@map item
            try {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(item.pkg, 0)
                item.copy(installed = pi.versionName ?: "?", installedCode = PackageInfoCompat.getLongVersionCode(pi))
            } catch (_: PackageManager.NameNotFoundException) {
                item.copy(installed = null, installedCode = -1)
            }
        }
    }

    private fun readRepo(ctx: Context, full: String, branch: String, repoName: String, token: String): AppItem {
        // 1) updater.json
        val meta = try {
            JSONObject(Http.get(ctx, "https://raw.githubusercontent.com/$full/$branch/updater.json", token))
        } catch (e: HttpException) {
            val msg = if (e.code == 404) "Brak pliku updater.json w repo" else e.message
            return AppItem(full, repoName, "", null, null, null, null, "", msg)
        } catch (e: Exception) {
            return AppItem(full, repoName, "", null, null, null, null, "", "updater.json: ${e.message}")
        }
        val name = meta.optString("name").ifEmpty { repoName }
        val pkg = meta.optString("package")
        val assetRe = meta.optString("asset").takeIf { it.isNotEmpty() }?.let {
            try { Regex(it) } catch (_: Exception) { null }
        }
        if (pkg.isEmpty()) return AppItem(full, name, "", null, null, null, null, "", "updater.json bez pola \"package\"")

        // 2) najnowszy release
        val rel = try {
            JSONObject(Http.get(ctx, "https://api.github.com/repos/$full/releases/latest", token))
        } catch (e: HttpException) {
            val msg = if (e.code == 404) "Brak wydań (Releases)" else e.message
            return AppItem(full, name, pkg, null, null, null, null, "", msg)
        }
        val tag = rel.optString("tag_name")
        val assets = rel.optJSONArray("assets") ?: JSONArray()
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name")
            if (!n.endsWith(".apk", ignoreCase = true)) continue
            if (assetRe == null || assetRe.matches(n)) { apk = a; break }
        }
        val notes = rel.optString("body").trim()
        if (apk == null) return AppItem(full, name, pkg, Ver.norm(tag) ?: tag, null, null, null, notes, "Release $tag bez pliku APK")
        val version = Ver.norm(tag) ?: Ver.norm(apk.optString("name")) ?: tag
        return AppItem(
            repo = full, name = name, pkg = pkg, latest = version,
            apkName = apk.optString("name"),
            apkUrl = apk.optString("browser_download_url"),
            apkApiUrl = apk.optString("url"),
            notes = notes, error = null,
        )
    }
}
