package pl.jardol.apkupdater

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Okresowe sprawdzanie w tle: apki z „Auto” aktualizuje, o pozostałych powiadamia. */
class CheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val p = Prefs(ctx)
        val items = try {
            Catalog.refresh(ctx)
        } catch (_: Exception) {
            return Result.retry()
        }
        val updates = items.filter { it.hasUpdate }
        val manual = ArrayList<AppItem>()
        // Siebie aktualizujemy na końcu — instalacja nowej wersji kończy ten proces.
        for (item in updates.sortedBy { it.pkg == ctx.packageName }) {
            if (p.isAuto(item.pkg) && Installer.canInstall(ctx)) {
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
