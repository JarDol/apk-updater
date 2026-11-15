package pl.jardol.apkupdater

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Podpięte źródło apek:
 *  - konto GitHub (`spec` = „JarDol”) — wszystkie jego repo z tematem [topic],
 *  - pojedyncze repo (`spec` = „JarDol/e2stream”) — temat nie ma znaczenia.
 * Każde ma własny token, kanał (stable / beta — wspólny dla wszystkich apek z tego źródła)
 * i własną blokadę po wyczerpaniu limitu zapytań.
 */
data class Source(
    val id: String,
    val spec: String,
    val topic: String = DEFAULT_TOPIC,
    val token: String = "",
    val channel: String = AppItem.STABLE,
    val blockedUntil: Long = 0,
) {
    val isRepo get() = spec.contains('/')
    val owner get() = spec.substringBefore('/')
    val isBeta get() = channel == AppItem.BETA

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("spec", spec).put("topic", topic).put("token", token)
        .put("channel", channel).put("blockedUntil", blockedUntil)

    companion object {
        const val DEFAULT_OWNER = "JarDol"
        const val DEFAULT_TOPIC = "android-app"

        fun fromJson(o: JSONObject) = Source(
            id = o.getString("id"),
            spec = o.optString("spec").ifBlank { DEFAULT_OWNER },
            topic = o.optString("topic").ifBlank { DEFAULT_TOPIC },
            token = o.optString("token"),
            channel = if (o.optString("channel") == AppItem.BETA) AppItem.BETA else AppItem.STABLE,
            blockedUntil = o.optLong("blockedUntil"),
        )

        /** „konto” albo „konto/repo”; akceptuje też adres https://github.com/… */
        fun normSpec(s: String): String? {
            var t = s.trim().removeSuffix("/").removeSuffix(".git")
            t = t.substringAfter("github.com/", t).trim('/')
            val parts = t.split('/').filter { it.isNotEmpty() }
            val ok = Regex("[A-Za-z0-9_.-]+")
            return when {
                parts.size == 1 && ok.matches(parts[0]) -> parts[0]
                parts.size >= 2 && ok.matches(parts[0]) && ok.matches(parts[1]) -> parts[0] + "/" + parts[1]
                else -> null
            }
        }

        fun parseList(s: String): List<Source> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }
}

/** Ustawienia aplikacji. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()

    /** Podpięte źródła. Przy pierwszym odczycie przenosi dawne pojedyncze ustawienia (konto, temat, token, ekran). */
    var sources: List<Source>
        get() {
            val s = sp.getString("sources", null)
            if (s != null) return Source.parseList(s)
            val migrated = listOf(
                Source(
                    id = "s1",
                    spec = sp.getString("owner", null)?.takeIf { it.isNotBlank() } ?: Source.DEFAULT_OWNER,
                    topic = sp.getString("topic", null)?.takeIf { it.isNotBlank() } ?: Source.DEFAULT_TOPIC,
                    token = sp.getString("token", "") ?: "",
                    channel = if (sp.getString("screen", null) == AppItem.BETA) AppItem.BETA else AppItem.STABLE,
                    blockedUntil = sp.getLong("blockedUntil", 0),
                )
            )
            sources = migrated
            return migrated
        }
        set(v) = sp.edit().putString("sources", JSONArray().apply { v.forEach { put(it.toJson()) } }.toString())
            .remove("owner").remove("topic").remove("token").remove("screen").remove("blockedUntil").apply()

    fun source(id: String): Source? = sources.firstOrNull { it.id == id }

    fun updateSource(s: Source) {
        sources = sources.map { if (it.id == s.id) s else it }
    }

    fun addSource(spec: String): Source {
        val cur = sources
        var n = cur.size + 1
        while (cur.any { it.id == "s$n" }) n++
        val s = Source(id = "s$n", spec = spec)
        sources = cur + s
        return s
    }

    fun removeSource(id: String) {
        sources = sources.filter { it.id != id }
    }

    fun setBlocked(id: String, until: Long) {
        source(id)?.let { updateSource(it.copy(blockedUntil = until)) }
    }

    fun tokenFor(sourceId: String): String = source(sourceId)?.token ?: ""

    /** Co ile godzin sprawdzać w tle (0 = wyłączone). */
    var intervalHours: Int
        get() = sp.getInt("interval", 6)
        set(v) = sp.edit().putInt("interval", v.coerceIn(0, 168)).apply()

    /** Pakiety aktualizowane automatycznie. Aktualizator sam siebie domyślnie tak. */
    fun isAuto(pkg: String): Boolean =
        sp.getStringSet("auto", null)?.contains(pkg) ?: (pkg == BuildConfigPkg.SELF)

    fun setAuto(pkg: String, on: Boolean) {
        val cur = (sp.getStringSet("auto", null) ?: setOf(BuildConfigPkg.SELF)).toMutableSet()
        if (on) cur.add(pkg) else cur.remove(pkg)
        sp.edit().putStringSet("auto", cur).apply()
    }

    /** Ostatnio zgłoszone powiadomieniem wersje (żeby nie powtarzać). */
    var notified: String
        get() = sp.getString("notified", "") ?: ""
        set(v) = sp.edit().putString("notified", v).apply()

    var catalogJson: String
        get() = sp.getString("catalog", "") ?: ""
        set(v) = sp.edit().putString("catalog", v).apply()

    /** Ostatni błąd odświeżania na źródło (JSON id → komunikat). */
    var sourceErrors: Map<String, String>
        get() = try {
            val o = JSONObject(sp.getString("srcErr", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (_: Exception) { emptyMap() }
        set(v) = sp.edit().putString("srcErr", JSONObject(v).toString()).apply()

    var lastCheck: Long
        get() = sp.getLong("lastCheck", 0)
        set(v) = sp.edit().putLong("lastCheck", v).apply()
}

object BuildConfigPkg {
    const val SELF = "pl.jardol.apkupdater"
}
