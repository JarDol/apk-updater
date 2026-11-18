package pl.jardol.apkupdater

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters

class InstallRequestWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val request = InstallRequest(
                requireNotNull(inputData.getString("version")),
                requireNotNull(inputData.getString("channel")),
                inputData.getBoolean("allowDowngrade", false),
            )
            require(InstallRequest.callerCertificates(ctx, InstallRequest.PACKAGE) == listOf(InstallRequest.CERTIFICATE)) {
                "Nieprawidłowy podpis zainstalowanej aplikacji E2Stream"
            }
            val item = Catalog.requestedRelease(ctx, request)
            report(ctx, "Pobieranie ${request.version}…")
            Notify.installing(ctx, InstallRequest.PACKAGE, "E2Stream", request.version)
            Installer.downloadAndInstall(ctx, item, request.allowDowngrade, request) { }
            Result.success()
        } catch (e: Exception) {
            val message = "Błąd instalacji: ${e.message ?: e.javaClass.simpleName}"
            report(ctx, message)
            Notify.error(ctx, InstallRequest.PACKAGE, "E2Stream", message)
            Result.failure(Data.Builder().putString("message", message).build())
        }
    }

    companion object {
        fun report(ctx: Context, message: String) {
            Prefs(ctx).externalInstallMessage = message
            App.post(InstallRequest.PACKAGE, message)
        }
    }
}
