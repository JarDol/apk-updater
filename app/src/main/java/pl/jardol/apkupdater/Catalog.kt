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
    val prerelease: Boolean = false,
    /** Apka ma własną aktualizację i własne ustawienie „Automatyczna aktualizacja” (updater.json: selfUpdate). */
    val selfUpdate: Boolean = false,
    val tagPrefix: String = "",
    val assetPattern: String? = null,
    val releaseConfigLoaded: Boolean = false,
    // uzupełniane lokalnie:
    val installed: String? = null,
    val installedCode: Long = -1,
    /** Dla [selfUpdate]: ustawienie odczytane z apki (null = apka go nie udostępnia → jak „nie”). */
    val appAuto: Boolean? = null,
    /** Ekran, na którym wpis jest pokazywany: [STABLE] albo [BETA] (najnowsza wersja, także beta). */
    val channel: String = STABLE,
) {
    /** Unikalny klucz wiersza (w jednym repo może być kilka apek); wspólny dla obu ekranów. */
    val key get() = "$repo#$pkg#$name"
    val isInstalled get() = installed != null
    val hasUpdate get() = isInstalled && apkUrl != null && Ver.newer(latest, installed)
    val canInstall get() = pkg.isNotEmpty() && apkUrl != null

    fun toJson(): JSONObject = JSONObject()
        .put("repo", repo).put("name", name).put("pkg", pkg)
        .put("latest", latest).put("apkName", apkName).put("apkUrl", apkUrl)
        .put("apkApiUrl", apkApiUrl).put("notes", notes).put("error", error).put("prerelease", prerelease)
        .put("selfUpdate", selfUpdate).put("tagPrefix", tagPrefix).put("assetPattern", assetPattern)
        .put("releaseConfigLoaded", releaseConfigLoaded).put("channel", channel)

    companion object {
        const val STABLE = "stable"
        const val BETA = "beta"

        fun fromJson(o: JSONObject) = AppItem(
            repo = o.getString("repo"), name = o.optString("name"), pkg = o.optString("pkg"),
            latest = o.optStringOrNull("latest"), apkName = o.optStringOrNull("apkName"),
            apkUrl = o.optStringOrNull("apkUrl"), apkApiUrl = o.optStringOrNull("apkApiUrl"),
            notes = o.optString("notes"), error = o.optStringOrNull("error"),
            prerelease = o.optBoolean("prerelease"),
            selfUpdate = o.optBoolean("selfUpdate"),
            tagPrefix = o.optString("tagPrefix"),
            assetPattern = o.optStringOrNull("assetPattern"),
            releaseConfigLoaded = o.optBoolean("releaseConfigLoaded"),
            channel = o.optString("channel", STABLE),
        )
    }
}

fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotEmpty() }

/**
 * Lista apek: wszystkie repo konta [Prefs.owner] z tematem [Prefs.topic].
 * W każdym repo plik updater.json:  {"name": "...", "package": "...", "asset": "regex (opcjonalnie)"}
 * Dla każdej apki powstają dwa wpisy: stable (najnowsze wydanie bez prerelease) i beta
 * (najnowsze wydanie w ogóle — prerelease albo stable, zależnie od tego, co nowsze).
 */
object Catalog {

    fun forChannel(items: List<AppItem>, channel: String) = items.filter { it.channel == channel }

    /** Do pracy w tle: apka zainstalowana jako beta śledzi betę, pozostałe — stable. */
    fun tracked(items: List<AppItem>) = items.filter {
        it.channel == if (it.installed?.contains('-') == true) AppItem.BETA else AppItem.STABLE
    }

    /** Pobiera świeżą listę z GitHuba (sieć — poza wątkiem UI). */
    fun refresh(ctx: Context): List<AppItem> {
        val p = Prefs(ctx)
        val blocked = p.blockedUntil
        if (blocked > System.currentTimeMillis()) throw RateLimitException(403, Http.rateMsg(blocked), blocked)
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
        // chwilowy błąd jednego repo nie kasuje ostatnich dobrych danych
        val old = savedItems(p).associateBy { it.key + "|" + it.channel }
        for (i in out.indices) {
            val cur = out[i]
            val prev = old[cur.key + "|" + cur.channel]
            if (cur.apkUrl == null && cur.error != null && prev != null && prev.error == null) out[i] = prev
        }
        out.sortWith(compareBy<AppItem>({ it.pkg != BuildConfigPkg.SELF }, { it.name.lowercase() }, { it.channel }))
        p.catalogJson = JSONArray().apply { out.forEach { put(it.toJson()) } }.toString()
        p.lastCheck = System.currentTimeMillis()
        return withInstalled(ctx, out)
    }

    private fun savedItems(p: Prefs): List<AppItem> {
        val s = p.catalogJson
        if (s.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { AppItem.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    /** Ostatnio pobrana lista (bez sieci). */
    fun cached(ctx: Context): List<AppItem> = withInstalled(ctx, savedItems(Prefs(ctx)))

    /** Dostępne wydania danej apki (sieć — poza wątkiem UI). */
    fun releasesFor(ctx: Context, item: AppItem): List<AppItem> {
        val p = Prefs(ctx)
        if (p.blockedUntil > System.currentTimeMillis()) {
            throw RateLimitException(403, Http.rateMsg(p.blockedUntil), p.blockedUntil)
        }
        check(item.releaseConfigLoaded) { "Odśwież listę apek przed wyborem wydania" }
        val list = JSONArray(Http.get(ctx, "https://api.github.com/repos/${item.repo}/releases?per_page=100", p.token))
        val assetRe = item.assetPattern?.let { pattern -> try { Regex(pattern) } catch (_: Exception) { null } }
        val out = ArrayList<AppItem>()
        for (i in 0 until list.length()) {
            val rel = list.getJSONObject(i)
            val tag = rel.optString("tag_name")
            val cand = fromRelease(item.repo, item.name, item.pkg, rel, assetRe, item.tagPrefix, item.assetPattern)
            if (!isSelectableRelease(tag, item.tagPrefix, rel.optBoolean("draft"), cand.apkUrl != null)) continue
            out += cand.copy(
                selfUpdate = item.selfUpdate,
                channel = item.channel,
                installed = item.installed,
                installedCode = item.installedCode,
                appAuto = item.appAuto,
            )
        }
        return sortReleases(out)
    }

    internal fun isSelectableRelease(tag: String, prefix: String, draft: Boolean, hasApk: Boolean) =
        !draft && tag.startsWith(prefix) && hasApk

    internal fun sortReleases(releases: List<AppItem>) = releases.sortedWith { a, b ->
        when {
            Ver.newer(a.latest, b.latest) -> -1
            Ver.newer(b.latest, a.latest) -> 1
            else -> 0
        }
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
        AppItem(full, name, pkg, null, null, null, null, "", msg)

    private fun bothChannels(item: AppItem, beta: AppItem = item) =
        listOf(item.copy(channel = AppItem.STABLE), beta.copy(channel = AppItem.BETA))

    /**
     * Apki z jednego repo. updater.json może opisywać jedną apkę albo kilka (monorepo):
     *   {"name": "...", "package": "...", "asset": "regex", "tag": "prefiks-tagu-", "selfUpdate": true}
     *   {"apps": [ {...}, {...} ]}
     * Z "tag": najwyższa wersja spośród wydań o tagu zaczynającym się od prefiksu.
     * Bez "tag": najnowsze (wg daty) wydanie danego rodzaju.
     * Dla każdej apki zwraca wpis stable i wpis beta.
     */
    private fun readRepo(ctx: Context, full: String, branch: String, repoName: String, token: String): List<AppItem> {
        val meta = try {
            JSONObject(Http.get(ctx, "https://raw.githubusercontent.com/$full/$branch/updater.json", token))
        } catch (e: RateLimitException) {
            throw e
        } catch (e: HttpException) {
            return bothChannels(err(full, repoName, "", if (e.code == 404) "Brak pliku updater.json w repo" else e.message))
        } catch (e: Exception) {
            return bothChannels(err(full, repoName, "", "updater.json: ${e.message}"))
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
            if (pkg.isEmpty()) { out += bothChannels(err(full, name, "", "updater.json bez pola \"package\"")); continue }
            val assetPattern = d.optString("asset").takeIf { it.isNotEmpty() }
            val assetRe = assetPattern?.let { try { Regex(it) } catch (_: Exception) { null } }
            val prefix = d.optString("tag")
            try {
                val list = releases
                    ?: JSONArray(Http.get(ctx, "https://api.github.com/repos/$full/releases?per_page=100", token))
                        .also { releases = it }
                var bestStable: AppItem? = null
                var bestBeta: AppItem? = null
                var lastErr: AppItem? = null
                for (i in 0 until list.length()) {
                    val rel = list.getJSONObject(i)
                    if (rel.optBoolean("draft")) continue
                    if (!rel.optString("tag_name").startsWith(prefix)) continue
                    val cand = fromRelease(full, name, pkg, rel, assetRe, prefix, assetPattern)
                    if (cand.apkUrl == null) { if (lastErr == null) lastErr = cand; continue }
                    // bez prefiksu wygrywa pierwsze (najnowsze wg daty), z prefiksem — najwyższa wersja
                    fun better(cur: AppItem?) = cur == null || (prefix.isNotEmpty() && Ver.newer(cand.latest, cur.latest))
                    if (rel.optBoolean("prerelease")) { if (better(bestBeta)) bestBeta = cand }
                    else if (better(bestStable)) bestStable = cand
                }
                val newest = when {
                    bestBeta == null -> bestStable
                    bestStable == null -> bestBeta
                    Ver.newer(bestStable.latest, bestBeta.latest) -> bestStable
                    else -> bestBeta
                }
                val fallback = lastErr ?: err(full, name, pkg,
                    if (list.length() == 0) "Brak wydań (Releases)" else "Brak wydań z tagiem $prefix…")
                out += bothChannels(bestStable ?: fallback, newest ?: fallback)
            } catch (e: RateLimitException) {
                throw e
            } catch (e: HttpException) {
                out += bothChannels(err(full, name, pkg, if (e.code == 404) "Brak wydań (Releases)" else e.message))
            }
            for (k in start until out.size) out[k] = out[k].copy(selfUpdate = self)
        }
        return out
    }

    private fun fromRelease(
        full: String,
        name: String,
        pkg: String,
        rel: JSONObject,
        assetRe: Regex?,
        prefix: String,
        assetPattern: String?,
    ): AppItem {
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
        if (apk == null) return AppItem(
            full, name, pkg, version, null, null, null, notes, "Wydanie $tag bez pliku APK",
            prerelease = rel.optBoolean("prerelease"),
            tagPrefix = prefix,
            assetPattern = assetPattern,
            releaseConfigLoaded = true,
        )
        return AppItem(
            repo = full, name = name, pkg = pkg, latest = version,
            apkName = apk.optString("name"),
            apkUrl = apk.optString("browser_download_url"),
            apkApiUrl = apk.optString("url"),
            prerelease = rel.optBoolean("prerelease"),
            notes = notes, error = null,
            tagPrefix = prefix,
            assetPattern = assetPattern,
            releaseConfigLoaded = true,
        )
    }
}
