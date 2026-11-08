package pl.jardol.apkupdater

import android.content.Context
import android.net.Uri

/**
 * Kto decyduje o automatycznej aktualizacji:
 *  - apka bez własnej aktualizacji → przełącznik „Auto” w APK Updaterze,
 *  - apka z własną aktualizacją (updater.json: "selfUpdate": true) → jej własne ustawienie,
 *    udostępniane przez provider  content://<pakiet>.updateinfo/settings  (kolumna "auto": 1/0).
 *    Brak providera (starsza wersja apki) = „nie”: APK Updater nic nie robi sam.
 */
object AppAuto {

    fun query(ctx: Context, pkg: String): Boolean? = try {
        ctx.contentResolver.query(Uri.parse("content://$pkg.updateinfo/settings"), null, null, null, null)?.use { c ->
            val col = c.getColumnIndex("auto")
            if (col >= 0 && c.moveToFirst()) c.getInt(col) == 1 else null
        }
    } catch (_: Exception) {
        null
    }

    /** Czy APK Updater ma sam zaktualizować tę apkę. */
    fun enabled(prefs: Prefs, item: AppItem): Boolean =
        if (item.selfUpdate) item.appAuto == true else prefs.isAuto(item.pkg)
}
