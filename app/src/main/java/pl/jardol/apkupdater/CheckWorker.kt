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
        val items = try {
            Catalog.refresh(ctx)
        } catch (_: Exception) {
            return Result.retry()
        }
        val updates = Catalog.tracked(items).filter { it.hasUpdate }
        val manual = ArrayList<AppItem>()
        // Siebie aktualizujemy na końcu — instalacja nowej wersji kończy ten proces.
        for (item in updates.sortedBy { it.pkg == ctx.packageName }) {
            if (item.selfUpdate && item.appAuto != true) continue   // apka ma ręczną aktualizację — z jej poziomu
            if (AppAuto.enabled(p, item) && Installer.canInstall(ctx)) {
                try {
                    Installer.downloadAndInstall(ctx, item) { }
                } catch (_: Exception) {
                    manual.add(item)
                }
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
