package pl.jardol.apkupdater

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Only for startActivityForResult: Android supplies the caller, never an Intent extra. */
class InstallRequestActivity : Activity() {
    private lateinit var request: InstallRequest
    private var accepting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(TextView(this).apply {
            text = "Sprawdzanie zlecenia instalacji…"
            setPadding(32, 32, 32, 32)
        })
        try {
            val caller = callingActivity?.packageName
            val certificates = if (caller == InstallRequest.PACKAGE)
                InstallRequest.callerCertificates(this, caller) else emptyList()
            request = InstallRequest.validate(
                caller, certificates, intent.action,
                intent.getStringExtra(InstallRequest.EXTRA + "REPOSITORY"),
                intent.getStringExtra(InstallRequest.EXTRA + "PACKAGE"),
                intent.getStringExtra(InstallRequest.EXTRA + "VERSION"),
                intent.getStringExtra(InstallRequest.EXTRA + "CHANNEL"),
                intent.getBooleanExtra(InstallRequest.EXTRA + "ALLOW_DOWNGRADE", false),
            )
            if (!Installer.canInstall(this)) {
                Installer.openInstallPermission(this)
                reject(NeedInstallPermission().message!!)
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else accept()
        } catch (e: Exception) {
            reject(e.message ?: "Nie można sprawdzić zlecenia")
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1) accept()
    }

    private fun accept() {
        if (accepting || isFinishing) return
        if (!Notify.canConfirm(this)) {
            reject("Włącz powiadomienia APK Updatera i kanał Instalacja, aby potwierdzić instalację Androida")
            return
        }
        accepting = true
        Thread({
            try {
                Catalog.requestedRelease(this, request)
                val work = OneTimeWorkRequest.Builder(InstallRequestWorker::class.java)
                    .setInputData(Data.Builder()
                        .putString("version", request.version)
                        .putString("channel", request.channel)
                        .putBoolean("allowDowngrade", request.allowDowngrade).build())
                    .setInitialDelay(3, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(this).enqueue(work).result.get()
                runOnUiThread {
                    setResult(RESULT_OK, Intent()
                        .putExtra(InstallRequest.EXTRA + "REQUEST_ID", work.id.toString())
                        .putExtra(InstallRequest.EXTRA + "MESSAGE", "Zlecenie instalacji przyjęte"))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { reject(e.message ?: "Nie można przyjąć zlecenia instalacji") }
            }
        }, "zlecenie-instalacji").start()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!accepting) super.onBackPressed()
    }

    private fun reject(message: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(InstallRequest.EXTRA + "MESSAGE", message))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
