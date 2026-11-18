package pl.jardol.apkupdater

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [21, 28, 35])
class InstallHandoffAndroidTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Notify.createChannels(ctx)
        Prefs(ctx).externalInstallMessage = null
        App.foreground = false
        App.listener = null
    }

    @Test
    fun intentExtrasCannotSubstituteForSystemCallerIdentity() {
        val controller = Robolectric.buildActivity(InstallRequestActivity::class.java, requestIntent()
            .putExtra("callingPackage", InstallRequest.PACKAGE)
            .putExtra("android.intent.extra.REFERRER_NAME", "android-app://${InstallRequest.PACKAGE}"))
        val activity = controller.create().get()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("Nieuprawniona aplikacja wywołująca",
            shadowOf(activity).resultIntent.getStringExtra(InstallRequest.EXTRA + "MESSAGE"))
        controller.destroy()
    }

    @Suppress("DEPRECATION")
    @Test
    fun correctCallingPackageWithoutPinnedSignerIsRejectedBeforeQueueing() {
        val info = PackageInfo().apply {
            packageName = InstallRequest.PACKAGE
            signatures = arrayOf(Signature(byteArrayOf(1, 2, 3)))
        }
        shadowOf(ctx.packageManager).installPackage(info)
        val controller = Robolectric.buildActivity(InstallRequestActivity::class.java, requestIntent())
        shadowOf(controller.get()).setCallingActivity(ComponentName(InstallRequest.PACKAGE, "UpdateActivity"))
        val activity = controller.create().get()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("Nieuprawniona aplikacja wywołująca",
            shadowOf(activity).resultIntent.getStringExtra(InstallRequest.EXTRA + "MESSAGE"))
        controller.destroy()
    }

    @Test
    fun androidConfirmationRemainsPendingRatherThanReportingSuccess() {
        InstallReceiver().onReceive(ctx, result(PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_VIEW)))
        assertEquals("Czeka na potwierdzenie instalacji", Prefs(ctx).externalInstallMessage)
    }

    @Test
    fun missingSystemConfirmationIsAnExplicitPersistentError() {
        InstallReceiver().onReceive(ctx, result(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals("Android nie przekazał potwierdzenia instalacji; spróbuj ponownie",
            Prefs(ctx).externalInstallMessage)
    }

    @Test
    fun storesActualSystemFailureAndSuccessAcrossPrefsInstances() {
        InstallReceiver().onReceive(ctx, result(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals("Za mało miejsca", Prefs(ctx).externalInstallMessage)
        InstallReceiver().onReceive(ctx, result(PackageInstaller.STATUS_SUCCESS))
        assertEquals("Zainstalowano", Prefs(ctx).externalInstallMessage)
    }

    @Test
    fun downgradeRejectionReportsAndroidLimitation() {
        InstallReceiver().onReceive(ctx, result(PackageInstaller.STATUS_FAILURE)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertTrue(Prefs(ctx).externalInstallMessage!!.contains("Android zablokował"))
    }

    @Test
    fun cancellationIsNotReportedAsSuccessfulInstallation() {
        InstallReceiver().onReceive(ctx, result(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals("Instalacja anulowana", Prefs(ctx).externalInstallMessage)
    }

    @Test
    fun existingManualInstallClearsStaleExternalRequestStatus() {
        Prefs(ctx).externalInstallMessage = "Poprzedni błąd instalacji"
        InstallReceiver().onReceive(ctx, result(PackageInstaller.STATUS_SUCCESS)
            .putExtra(Installer.EXTRA_REQUEST, false))
        assertNull(Prefs(ctx).externalInstallMessage)
    }

    private fun requestIntent() = Intent(InstallRequest.ACTION)
        .putExtra(InstallRequest.EXTRA + "REPOSITORY", InstallRequest.REPOSITORY)
        .putExtra(InstallRequest.EXTRA + "PACKAGE", InstallRequest.PACKAGE)
        .putExtra(InstallRequest.EXTRA + "VERSION", "e2stream-0.18.75-beta.1")
        .putExtra(InstallRequest.EXTRA + "CHANNEL", AppItem.BETA)
        .putExtra(InstallRequest.EXTRA + "ALLOW_DOWNGRADE", true)

    private fun result(status: Int): Intent {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(InstallRequest.PACKAGE)
        val session = ctx.packageManager.packageInstaller.createSession(params)
        InstallConfirmation.begin(ctx, InstallRequest.PACKAGE, session)
        return Intent(ctx, InstallReceiver::class.java)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra(Installer.EXTRA_PKG, InstallRequest.PACKAGE)
            .putExtra(Installer.EXTRA_NAME, "E2Stream")
            .putExtra(Installer.EXTRA_REQUEST, true)
            .putExtra(Installer.EXTRA_SESSION, session)
    }
}
