package pl.jardol.apkupdater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notify {
    private const val CH_UPDATES = "updates"
    private const val CH_INSTALL = "install"
    private const val ID_UPDATES = 1

    fun createChannels(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Dostępne aktualizacje").build()
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_INSTALL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Instalacja").build()
        )
    }

    private fun immutable(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            immutable()
        )

    private fun idFor(pkg: String) = 1000 + (pkg.hashCode() and 0xffff)

    private fun show(ctx: Context, id: Int, n: NotificationCompat.Builder) {
        try {
            val nm = NotificationManagerCompat.from(ctx)
            if (nm.areNotificationsEnabled()) nm.notify(id, n.build())
        } catch (_: SecurityException) { }
    }

    fun updates(ctx: Context, items: List<AppItem>) {
        val text = items.joinToString(", ") { "${it.name} ${it.latest}" }
        show(ctx, ID_UPDATES, NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (items.size == 1) "Dostępna aktualizacja" else "Dostępne aktualizacje: ${items.size}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true))
    }

    fun cancelUpdates(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_UPDATES)

    fun confirm(ctx: Context, pkg: String, name: String, confirm: Intent) {
        val pi = PendingIntent.getActivity(ctx, idFor(pkg), confirm, immutable())
        show(ctx, idFor(pkg), NotificationCompat.Builder(ctx, CH_INSTALL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$name — gotowe do instalacji")
            .setContentText("Dotknij, aby zainstalować")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true))
    }

    fun done(ctx: Context, pkg: String, name: String) {
        show(ctx, idFor(pkg), NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$name zaktualizowana")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true))
    }

    fun error(ctx: Context, pkg: String, name: String, msg: String) {
        show(ctx, idFor(pkg), NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$name — nie zainstalowano")
            .setContentText(msg)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true))
    }

    fun cancel(ctx: Context, pkg: String) = NotificationManagerCompat.from(ctx).cancel(idFor(pkg))
}
