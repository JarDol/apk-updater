package pl.jardol.apkupdater

import android.content.Context
import android.content.SharedPreferences

/** Ustawienia aplikacji. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Konto GitHub, którego repo są przeszukiwane. */
    var owner: String
        get() = sp.getString("owner", null)?.takeIf { it.isNotBlank() } ?: "JarDol"
        set(v) = sp.edit().putString("owner", v.trim()).apply()

    /** Temat (topic), którym oznaczone są repo z apkami. */
    var topic: String
        get() = sp.getString("topic", null)?.takeIf { it.isNotBlank() } ?: "android-app"
        set(v) = sp.edit().putString("topic", v.trim().lowercase()).apply()

    /** Ostatnio otwarty ekran: [AppItem.STABLE] lub [AppItem.BETA]. */
    var screen: String
        get() = sp.getString("screen", AppItem.STABLE) ?: AppItem.STABLE
        set(v) = sp.edit().putString("screen", v).apply()

    /** Opcjonalny token GitHub (prywatne repo / wyższy limit zapytań). */
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

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

    var lastCheck: Long
        get() = sp.getLong("lastCheck", 0)
        set(v) = sp.edit().putLong("lastCheck", v).apply()
}

object BuildConfigPkg {
    const val SELF = "pl.jardol.apkupdater"
}
