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
        synchronized(InstallConfirmation) { receive(ctx, intent) }
    }

    private fun receive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(Installer.EXTRA_PKG) ?: ""
        val name = intent.getStringExtra(Installer.EXTRA_NAME) ?: pkg
        val file = intent.getStringExtra(Installer.EXTRA_FILE)
        val external = intent.getBooleanExtra(Installer.EXTRA_REQUEST, false)
        val session = intent.getIntExtra(Installer.EXTRA_SESSION,
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
        if (!InstallConfirmation.isCurrent(ctx, pkg, session)) return
        if (!external && pkg == InstallRequest.PACKAGE) Prefs(ctx).externalInstallMessage = null

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    InstallConfirmation.clear(ctx, pkg, session)
                    val message = "Android nie przekazał potwierdzenia instalacji; spróbuj ponownie"
                    if (external) InstallRequestWorker.report(ctx, message)
                    Notify.error(ctx, pkg, name, message)
                    App.post(pkg, message)
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val action = InstallConfirmation.save(ctx, pkg, session, confirm) ?: return
                if (external) InstallRequestWorker.report(ctx, "Czeka na potwierdzenie instalacji")
                if (App.foreground) {
                    try {
                        ctx.startIntentSender(action.intentSender, null, 0, 0, 0)
                    } catch (e: android.content.IntentSender.SendIntentException) {
                        android.util.Log.w("APK-Updater", "Nie można otworzyć potwierdzenia w tej chwili", e)
                    } catch (e: SecurityException) {
                        android.util.Log.w("APK-Updater", "Android zablokował automatyczne otwarcie potwierdzenia", e)
                    }
                }
                Notify.confirm(ctx, pkg, name, action)
                App.post(pkg, "Czeka na potwierdzenie instalacji")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                InstallConfirmation.clear(ctx, pkg, session)
                file?.let { File(it).delete() }
                Notify.cancel(ctx, pkg)
                if (external) InstallRequestWorker.report(ctx, "Zainstalowano")
                if (!App.foreground) Notify.done(ctx, pkg, name)
                App.post(pkg, null)
            }
            else -> {
                InstallConfirmation.clear(ctx, pkg, session)
                file?.let { File(it).delete() }     // nieudana / anulowana instalacja — plik nie zostaje w pamięci
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
                if (external) InstallRequestWorker.report(ctx, text)
                if (!App.foreground) Notify.error(ctx, pkg, name, text)
                App.post(pkg, text)
            }
        }
    }
}
