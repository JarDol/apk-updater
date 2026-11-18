package pl.jardol.apkupdater

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat

/** Only session identifiers live on disk; Android keeps the private confirmation Intent in a PendingIntent. */
object InstallConfirmation {
    const val EXPIRED = "Potwierdzenie instalacji wygasło. Zleć instalację ponownie."
    const val STALE = "To potwierdzenie instalacji nie jest już aktualne."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("install-confirmations", Context.MODE_PRIVATE)
    private fun current(ctx: Context, pkg: String) = prefs(ctx).getInt("$pkg.session", -1)
    private fun waiting(ctx: Context, pkg: String) = prefs(ctx).getBoolean("$pkg.waiting", false)
    private fun immutable() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun identity(ctx: Context, id: Int) = Intent(ctx, InstallConfirmationActivity::class.java)
        .setData(Uri.parse("apk-updater://install-confirmation/$id"))

    private fun recover(ctx: Context, id: Int): PendingIntent? =
        PendingIntent.getActivity(ctx, id, identity(ctx, id), PendingIntent.FLAG_NO_CREATE or immutable())

    private fun exists(ctx: Context, pkg: String, id: Int) =
        ctx.packageManager.packageInstaller.mySessions.any { it.sessionId == id && it.appPackageName == pkg }

    @Synchronized
    fun isCurrent(ctx: Context, pkg: String, id: Int) = id >= 0 && current(ctx, pkg) == id

    @Synchronized
    fun begin(ctx: Context, pkg: String, id: Int) {
        replace(ctx, pkg)
        check(prefs(ctx).edit().putInt("$pkg.session", id).putBoolean("$pkg.waiting", false).commit()) {
            "Nie można zapisać sesji instalacji"
        }
    }

    @Synchronized
    fun replace(ctx: Context, pkg: String) {
        val id = current(ctx, pkg)
        val wasWaiting = waiting(ctx, pkg)
        if (id < 0) {
            if (pkg == InstallRequest.PACKAGE &&
                Prefs(ctx).externalInstallMessage == "Czeka na potwierdzenie instalacji") {
                Prefs(ctx).externalInstallMessage = null
            }
            return
        }
        clear(ctx, pkg, id)
        if (pkg == InstallRequest.PACKAGE) Prefs(ctx).externalInstallMessage = null
        if (wasWaiting && exists(ctx, pkg, id)) ctx.packageManager.packageInstaller.abandonSession(id)
    }

    @Synchronized
    fun clear(ctx: Context, pkg: String, id: Int) {
        if (!isCurrent(ctx, pkg, id)) return
        recover(ctx, id)?.cancel()
        check(prefs(ctx).edit().remove("$pkg.session").remove("$pkg.waiting").commit()) {
            "Nie można usunąć potwierdzenia instalacji"
        }
        Notify.cancel(ctx, pkg)
    }

    @Synchronized
    fun save(ctx: Context, pkg: String, id: Int, confirm: Intent): PendingIntent? {
        if (!isCurrent(ctx, pkg, id)) return null
        if (!exists(ctx, pkg, id)) {
            expire(ctx, pkg, id)
            return null
        }
        val action = PendingIntent.getActivity(ctx, id, identity(ctx, id)
            .putExtra(Installer.EXTRA_PKG, pkg)
            .putExtra(Intent.EXTRA_INTENT, confirm),
            PendingIntent.FLAG_CANCEL_CURRENT or immutable())
        check(prefs(ctx).edit().putBoolean("$pkg.waiting", true).commit()) {
            "Nie można zapisać potwierdzenia instalacji"
        }
        return action
    }

    @Synchronized
    fun pendingSession(ctx: Context, pkg: String): Int? {
        val id = current(ctx, pkg)
        if (id < 0) {
            if (pkg == InstallRequest.PACKAGE &&
                Prefs(ctx).externalInstallMessage == "Czeka na potwierdzenie instalacji") expire(ctx, pkg, id)
            return null
        }
        if (!waiting(ctx, pkg)) return null
        if (exists(ctx, pkg, id) && recover(ctx, id) != null) return id
        expire(ctx, pkg, id)
        return null
    }

    @Synchronized
    fun launch(activity: Activity, pkg: String, id: Int): String? {
        if (!isCurrent(activity, pkg, id) || !waiting(activity, pkg)) return STALE
        val action = recover(activity, id)
        if (action == null || !exists(activity, pkg, id)) return expire(activity, pkg, id)
        return try {
            activity.startIntentSender(action.intentSender, null, 0, 0, 0)
            null
        } catch (_: IntentSender.SendIntentException) {
            expire(activity, pkg, id)
        } catch (_: SecurityException) {
            expire(activity, pkg, id)
        } catch (_: ActivityNotFoundException) {
            expire(activity, pkg, id)
        }
    }

    @Synchronized
    fun openSystemConfirmation(activity: Activity, envelope: Intent): String? {
        val pkg = envelope.getStringExtra(Installer.EXTRA_PKG) ?: return STALE
        val id = envelope.data?.lastPathSegment?.toIntOrNull() ?: return STALE
        if (!isCurrent(activity, pkg, id) || !waiting(activity, pkg)) return STALE
        val confirm = IntentCompat.getParcelableExtra(envelope, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null || !exists(activity, pkg, id)) return expire(activity, pkg, id)
        return try {
            activity.startActivity(confirm)
            null
        } catch (_: ActivityNotFoundException) {
            expire(activity, pkg, id)
        } catch (_: SecurityException) {
            expire(activity, pkg, id)
        }
    }

    private fun expire(ctx: Context, pkg: String, id: Int): String {
        clear(ctx, pkg, id)
        Notify.cancel(ctx, pkg)
        if (pkg == InstallRequest.PACKAGE) Prefs(ctx).externalInstallMessage = EXPIRED
        App.post(pkg, EXPIRED)
        return EXPIRED
    }
}
