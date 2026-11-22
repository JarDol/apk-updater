package pl.jardol.apkupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/** Wynik sesji PackageInstaller. */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(Installer.EXTRA_PKG) ?: ""
        val name = intent.getStringExtra(Installer.EXTRA_NAME) ?: pkg
        val file = intent.getStringExtra(Installer.EXTRA_FILE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (App.foreground) {
                    try { ctx.startActivity(confirm); return } catch (_: Exception) { }
                }
                // W tle Android nie pozwala otworzyć okna — prosimy powiadomieniem.
                Notify.confirm(ctx, pkg, name, confirm)
                App.post(pkg, "Czeka na potwierdzenie instalacji")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val session = intent.getIntExtra(Installer.EXTRA_SESSION, -1)
                val resultSession = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                val resultPackage = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                if (file == null || !InstallFiles.cleanupOnResult(
                        ctx.cacheDir, file, status, session, resultSession, pkg, resultPackage
                    )) {
                    Log.w("InstallReceiver", "Nie można potwierdzić instalacji lub usunąć jej plików: $file")
                }
                Notify.cancel(ctx, pkg)
                if (!App.foreground) Notify.done(ctx, pkg, name)
                App.post(pkg, null)
                CacheCleanup.scheduleNow(ctx)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val downgradeBlocked = msg?.contains("VERSION_DOWNGRADE", ignoreCase = true) == true ||
                    msg?.contains("version downgrade", ignoreCase = true) == true
                val text = when {
                    downgradeBlocked ->
                        "Android zablokował instalację starszej wersji. Możesz odinstalować obecną aplikację i spróbować ponownie; jej dane mogą zostać usunięte."
                    status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalacja anulowana"
                    status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "Konflikt — zainstalowana wersja ma inny podpis? Odinstaluj ją ręcznie i zainstaluj ponownie"
                    status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Apka niezgodna z tym urządzeniem"
                    status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Za mało miejsca"
                    status == PackageInstaller.STATUS_FAILURE_BLOCKED -> "Instalacja zablokowana przez system"
                    else -> "Błąd instalacji"
                } + if (msg != null && !downgradeBlocked) " ($msg)" else ""
                if (!App.foreground) Notify.error(ctx, pkg, name, text)
                App.post(pkg, text)
                CacheCleanup.scheduleNow(ctx)
            }
        }
    }
}
