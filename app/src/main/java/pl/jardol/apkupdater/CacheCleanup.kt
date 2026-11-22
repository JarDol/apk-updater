package pl.jardol.apkupdater

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class ApkIdentity(val pkg: String, val version: Long, val signatures: Set<String>) {
    fun matches(other: ApkIdentity): Boolean =
        pkg.isNotEmpty() && pkg == other.pkg && version == other.version &&
            signatures.isNotEmpty() && signatures == other.signatures
}

internal object CacheCleanup {
    private const val PERIODIC = "cleanup-installed-apks"
    private const val IMMEDIATE = "cleanup-installed-apks-now"

    fun schedule(ctx: Context) {
        val work = PeriodicWorkRequest.Builder(CacheCleanupWorker::class.java, 6, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
        scheduleNow(ctx)
    }

    fun scheduleNow(ctx: Context) {
        val work = OneTimeWorkRequest.Builder(CacheCleanupWorker::class.java).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, work)
    }

    @Suppress("DEPRECATION")
    fun matchesInstalled(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES) ?: return false
        val installed = try {
            pm.getPackageInfo(archive.packageName, PackageManager.GET_SIGNATURES)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        fun identity(info: android.content.pm.PackageInfo) = ApkIdentity(
            info.packageName,
            PackageInfoCompat.getLongVersionCode(info),
            info.signatures?.map { it.toCharsString() }?.toSet().orEmpty(),
        )
        return identity(archive).matches(identity(installed))
    }
}

class CacheCleanupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = synchronized(Installer) {
        val ctx = applicationContext
        try {
            val cleaned = InstallFiles.cleanupInstalled(
                ctx.cacheDir, ctx.packageManager.packageInstaller.mySessions.isNotEmpty()
            ) { CacheCleanup.matchesInstalled(ctx, it) }
            if (cleaned) Result.success() else Result.retry()
        } catch (e: SecurityException) {
            Log.e("CacheCleanup", "Nie można sprawdzić sesji lub zainstalowanych pakietów", e)
            Result.retry()
        } catch (e: IOException) {
            Log.e("CacheCleanup", "Nie można sprawdzić plików pobrań", e)
            Result.retry()
        }
    }
}

class CacheCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) CacheCleanup.schedule(ctx)
    }
}
