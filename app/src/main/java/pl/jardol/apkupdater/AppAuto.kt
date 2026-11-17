package pl.jardol.apkupdater

import android.content.Context
import android.net.Uri

/**
 * Kto decyduje o automatycznej aktualizacji:
 *  - apka bez własnej aktualizacji → przełącznik „Auto” w APK Updaterze,
 *  - apka z własną aktualizacją (updater.json: "selfUpdate": true) → jej własne ustawienie,
 *    udostępniane przez provider  content://<pakiet>.updateinfo/settings  (kolumna "auto": 1/0).
 *    Brak providera (starsza wersja apki) = „nie”: APK Updater nic nie robi sam.
 *  - kanał automatycznej aktualizacji takiej apki też jest jej własny (kolumna "channel": stable/beta);
 *    kanał wybrany w APK Updaterze decyduje tylko o tym, co widać na liście i o ręcznej instalacji.
 *    Brak kolumny (starsza wersja apki) = stabilny.
 */
object AppAuto {

    fun query(ctx: Context, pkg: String): Boolean? = try {
        ctx.contentResolver.query(Uri.parse("content://$pkg.updateinfo/settings"), null, null, null, null)?.use { c ->
            val col = c.getColumnIndex("auto")
            val mode = c.getColumnIndex("mode")
            // tryb "notify" też jest automatyczny (instalacja przy zatrzymanej apce) — o terminie decyduje [canInstallNow]
            if (mode >= 0 && c.moveToFirst()) c.getString(mode) != "off"
            else if (col >= 0 && c.moveToFirst()) c.getInt(col) == 1 else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Czy teraz wolno zainstalować aktualizację apki z własnym ustawieniem:
     * "on" — zawsze, "notify" — tylko gdy apka jest zatrzymana, "off" — nigdy; przy nagrywaniu (busy) — czekamy.
     * Starsza apka bez kolumny "mode": decyduje "auto".
     */
    fun canInstallNow(ctx: Context, pkg: String): Boolean = try {
        ctx.contentResolver.query(Uri.parse("content://$pkg.updateinfo/settings"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use false
            fun int(name: String) = c.getColumnIndex(name).let { if (it >= 0) c.getInt(it) else 0 }
            val modeCol = c.getColumnIndex("mode")
            val mode = if (modeCol >= 0) c.getString(modeCol) else if (int("auto") == 1) "on" else "off"
            when {
                int("busy") == 1 -> false
                mode == "on" -> true
                mode == "notify" -> int("running") == 0
                else -> false
            }
        } ?: false
    } catch (_: Exception) {
        false
    }

    /** Kanał aktualizacji ustawiony w apce ([AppItem.STABLE] / [AppItem.BETA]) albo null, gdy apka go nie udostępnia. */
    fun queryChannel(ctx: Context, pkg: String): String? = try {
        ctx.contentResolver.query(Uri.parse("content://$pkg.updateinfo/settings"), null, null, null, null)?.use { c ->
            val col = c.getColumnIndex("channel")
            if (col >= 0 && c.moveToFirst()) (if (c.getString(col) == AppItem.BETA) AppItem.BETA else AppItem.STABLE) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Czy APK Updater ma sam zaktualizować tę apkę. */
    fun enabled(prefs: Prefs, item: AppItem): Boolean =
        if (item.selfUpdate) item.appAuto == true else prefs.isAuto(item.pkg)
}
