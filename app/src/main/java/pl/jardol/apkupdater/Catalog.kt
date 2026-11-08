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
    /** Apka ma własną aktualizację i własne ustawienie „Automatyczna aktualizacja” (updater.json: selfUpdate). */
    val selfUpdate: Boolean = false,
    // uzupełniane lokalnie:
    val installed: String? = null,
    val installedCode: Long = -1,
    /** Dla [selfUpdate]: ustawienie odczytane z apki (null = apka go nie udostępnia → jak „nie”). */
    val appAuto: Boolean? = null,
) {
    /** Unikalny klucz wiersza (w jednym repo może być kilka apek). */
    val key get() = "$repo#$pkg#$name"
    val isInstalled get() = installed != null
    val hasUpdate get() = isInstalled && apkUrl != null && Ver.newer(latest, installed)
    val canInstall get() = pkg.isNotEmpty() && apkUrl != null

    fun toJson(): JSONObject = JSONObject()
        .put("repo", repo).put("name", name).put("pkg", pkg)
        .put("latest", latest).put("apkName", apkName).put("apkUrl", apkUrl)
        .put("apkApiUrl", apkApiUrl).put("notes", notes).put("error", error)
        .put("selfUpdate", selfUpdate)

    companion object {
        fun fromJson(o: JSONObject) = AppItem(
            repo = o.getString("repo"), name = o.optString("name"), pkg = o.optString("pkg"),
            latest = o.optStringOrNull("latest"), apkName = o.optStringOrNull("apkName"),
            apkUrl = o.optStringOrNull("apkUrl"), apkApiUrl = o.optStringOrNull("apkApiUrl"),
            notes = o.optString("notes"), error = o.optStringOrNull("error"),
            selfUpdate = o.optBoolean("selfUpdate"),
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
            out.addAll(readRepo(ctx, full, r.optString("default_branch", "main"), r.optString("name"), token))
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
                item.copy(
                    installed = pi.versionName ?: "?",
                    installedCode = PackageInfoCompat.getLongVersionCode(pi),
                    appAuto = if (item.selfUpdate) AppAuto.query(ctx, item.pkg) else null,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                item.copy(installed = null, installedCode = -1, appAuto = null)
            }
        }
    }

    private fun err(full: String, name: String, pkg: String, msg: String?) =
        listOf(AppItem(full, name, pkg, null, null, null, null, "", msg))

    /**
     * Apki z jednego repo. updater.json może opisywać jedną apkę albo kilka (monorepo):
     *   {"name": "...", "package": "...", "asset": "regex", "tag": "prefiks-tagu-", "selfUpdate": true}
     *   {"apps": [ {...}, {...} ]}
     * Z "tag": najwyższa wersja spośród wydań o tagu zaczynającym się od prefiksu.
     * Bez "tag": wydanie oznaczone jako najnowsze (latest).
     */
    private fun readRepo(ctx: Context, full: String, branch: String, repoName: String, token: String): List<AppItem> {
        val meta = try {
            JSONObject(Http.get(ctx, "https://raw.githubusercontent.com/$full/$branch/updater.json", token))
        } catch (e: HttpException) {
            return err(full, repoName, "", if (e.code == 404) "Brak pliku updater.json w repo" else e.message)
        } catch (e: Exception) {
            return err(full, repoName, "", "updater.json: ${e.message}")
        }
        val defs = meta.optJSONArray("apps")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            ?: listOf(meta)

        var releases: JSONArray? = null   // lista wydań, pobierana raz na repo
        val out = ArrayList<AppItem>()
        for (d in defs) {
            val start = out.size
            val self = d.optBoolean("selfUpdate")
            val name = d.optString("name").ifEmpty { repoName }
            val pkg = d.optString("package")
            if (pkg.isEmpty()) { out += err(full, name, "", "updater.json bez pola \"package\""); continue }
            val assetRe = d.optString("asset").takeIf { it.isNotEmpty() }?.let {
                try { Regex(it) } catch (_: Exception) { null }
            }
            val prefix = d.optString("tag")
            try {
                if (prefix.isEmpty()) {
                    val rel = JSONObject(Http.get(ctx, "https://api.github.com/repos/$full/releases/latest", token))
                    out += fromRelease(full, name, pkg, rel, assetRe, "")
                } else {
                    val list = releases
                        ?: JSONArray(Http.get(ctx, "https://api.github.com/repos/$full/releases?per_page=100", token))
                            .also { releases = it }
                    var best: AppItem? = null
                    var lastErr: AppItem? = null
                    for (i in 0 until list.length()) {
                        val rel = list.getJSONObject(i)
                        if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
                        if (!rel.optString("tag_name").startsWith(prefix)) continue
                        val cand = fromRelease(full, name, pkg, rel, assetRe, prefix)
                        if (cand.apkUrl == null) { if (lastErr == null) lastErr = cand; continue }
                        if (best == null || Ver.newer(cand.latest, best.latest)) best = cand
                    }
                    out += best ?: lastErr ?: err(full, name, pkg, "Brak wydań z tagiem $prefix…").first()
                }
            } catch (e: HttpException) {
                out += err(full, name, pkg, if (e.code == 404) "Brak wydań (Releases)" else e.message)
            }
            for (k in start until out.size) out[k] = out[k].copy(selfUpdate = self)
        }
        return out
    }

    private fun fromRelease(full: String, name: String, pkg: String, rel: JSONObject, assetRe: Regex?, prefix: String): AppItem {
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
        val version = Ver.norm(tag.removePrefix(prefix)) ?: apk?.let { Ver.norm(it.optString("name")) } ?: tag
        if (apk == null) return AppItem(full, name, pkg, version, null, null, null, notes, "Wydanie $tag bez pliku APK")
        return AppItem(
            repo = full, name = name, pkg = pkg, latest = version,
            apkName = apk.optString("name"),
            apkUrl = apk.optString("browser_download_url"),
            apkApiUrl = apk.optString("url"),
            notes = notes, error = null,
        )
    }
}
