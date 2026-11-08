package pl.jardol.apkupdater

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notify.createChannels(this)
        schedule(this)
    }

    companion object {
        /** Czy ekran główny jest na wierzchu (wtedy można od razu pokazać okno instalatora). */
        @Volatile var foreground = false

        /** Słuchacz wyników instalacji: (pakiet, komunikat; null = zainstalowano). */
        var listener: ((String, String?) -> Unit)? = null

        private val main = Handler(Looper.getMainLooper())

        fun post(pkg: String, msg: String?) {
            main.post { listener?.invoke(pkg, msg) }
        }

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val hours = Prefs(ctx).intervalHours
            if (hours <= 0) {
                wm.cancelUniqueWork(CheckWorker.NAME)
                return
            }
            val req = PeriodicWorkRequest.Builder(CheckWorker::class.java, hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(CheckWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
