package pl.jardol.apkupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import java.io.File

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
                file?.let { File(it).delete() }
                Notify.cancel(ctx, pkg)
                if (!App.foreground) Notify.done(ctx, pkg, name)
                App.post(pkg, null)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val text = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalacja anulowana"
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "Konflikt — zainstalowana wersja ma inny podpis? Odinstaluj ją ręcznie i zainstaluj ponownie"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Apka niezgodna z tym urządzeniem"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Za mało miejsca"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Instalacja zablokowana przez system"
                    else -> "Błąd instalacji"
                } + (msg?.let { " ($it)" } ?: "")
                if (!App.foreground) Notify.error(ctx, pkg, name, text)
                App.post(pkg, text)
            }
        }
    }
}
