package pl.jardol.apkupdater

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Okresowe sprawdzanie w tle: apki z automatyczną aktualizacją aktualizuje, o pozostałych powiadamia.
 * Apki z własną aktualizacją ustawioną na „nie” są pomijane (bez powiadomień).
 */
class CheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val p = Prefs(ctx)
        // błąd / limit jednego źródła nie przerywa pozostałych (Catalog zostawia jego ostatnie dane)
        val items = try {
            Catalog.refresh(ctx)
        } catch (_: Exception) {
            return Result.retry()
        }
        val updates = Catalog.tracked(items, p.sources).filter { it.hasUpdate }
        val manual = ArrayList<AppItem>()
        // Siebie aktualizujemy na końcu — instalacja nowej wersji kończy ten proces.
        for (item in updates.sortedBy { it.pkg == ctx.packageName }) {
            if (item.selfUpdate && item.appAuto != true) continue   // apka ma ręczną aktualizację — z jej poziomu
            // apka z własną aktualizacją: tylko wydanie z kanału ustawionego w apce (np. stabilny),
            // nawet gdy w APK Updaterze źródło pokazuje bety
            val target = if (AppAuto.enabled(p, item)) Catalog.autoTarget(items, item) else null
            if (target != null && Installer.canInstall(ctx)) {
                try {
                    Installer.downloadAndInstall(ctx, target) { }
                } catch (_: Exception) {
                    manual.add(item)
                }
            } else if (item.selfUpdate && item.appAuto == true) {
                // nowsza wersja tylko w innym kanale niż apki (np. beta przy stabilnym) — informacja na liście, bez powiadomienia
                continue
            } else {
                manual.add(item)
            }
        }
        val key = manual.joinToString(",") { "${it.pkg}:${it.latest}" }
        if (manual.isEmpty()) {
            p.notified = ""
            Notify.cancelUpdates(ctx)
        } else if (key != p.notified) {
            p.notified = key
            Notify.updates(ctx, manual)
        }
        return Result.success()
    }

    companion object {
        const val NAME = "check-updates"
    }
}
