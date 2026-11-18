package pl.jardol.apkupdater

import android.app.Application
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class InstallConfirmationTest {
    class ExpiredConfirmationHost : Activity() {
        override fun startActivity(intent: Intent) {
            throw android.content.ActivityNotFoundException("expired system confirmation")
        }
    }

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val pkg = InstallRequest.PACKAGE
    private val installer get() = ctx.packageManager.packageInstaller

    @Before
    fun setup() {
        Notify.createChannels(ctx)
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        if (Build.VERSION.SDK_INT >= 33) shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        App.foreground = false
        App.listener = null
    }

    @Test
    fun backgroundCallbackCanBeConfirmedFromRecreatedCardWithoutNotificationsOrDownload() {
        val session = session()
        val apk = File(ctx.cacheDir, "already-downloaded.apk").apply { writeText("already verified") }
        pending(session, apk)
        assertEquals(session, InstallConfirmation.pendingSession(ctx, pkg))
        assertNull(shadowOf(ctx).nextStartedActivity)
        assertTrue(shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        cacheCard()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(confirmButton(controller.get().window.decorView))
        controller.recreate()
        val button = requireNotNull(confirmButton(controller.get().window.decorView))
        assertTrue(button.isFocusable)
        button.performClick()
        val envelope = requireNotNull(shadowOf(controller.get()).nextStartedActivity)
        assertEquals(InstallConfirmationActivity::class.java.name, envelope.component?.className)
        val confirmation = Robolectric.buildActivity(InstallConfirmationActivity::class.java, envelope).create()
        assertEquals("system.confirm.$session", shadowOf(confirmation.get()).nextStartedActivity.action)
        assertEquals("already verified", apk.readText())
        assertEquals(session, InstallConfirmation.pendingSession(ctx, pkg))
        confirmation.destroy()
        controller.pause().stop().destroy()
    }

    @Suppress("DEPRECATION")
    @Test
    fun immutableActionIsRecoveredFromSystemRegistryBySessionIdentity() {
        val session = session()
        pending(session)
        val action = requireNotNull(recover(session))
        assertTrue(shadowOf(action).isImmutable)
        App.listener = null
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        assertNull(InstallConfirmation.launch(activity, pkg, session))
        val recoveredEnvelope = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals("system.confirm.$session",
            recoveredEnvelope.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.action)
    }

    @Test
    fun successAndCancellationRemoveActionAndDoNotAllowLateCallbacksToResurrectIt() {
        for (status in listOf(PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED)) {
            val session = session()
            pending(session)
            val token = requireNotNull(recover(session))
            callback(session, status)
            assertNull(InstallConfirmation.pendingSession(ctx, pkg))
            assertNull(recover(session))
            assertTrue(shadowOf(token).isCanceled)
            pending(session)
            assertNull(InstallConfirmation.pendingSession(ctx, pkg))
            assertNull(recover(session))
        }
    }

    @Test
    fun replacementInvalidatesOldActionAndIgnoresOldSessionResult() {
        val old = session()
        pending(old)
        val oldEnvelope = requireNotNull(shadowOf(requireNotNull(recover(old))).savedIntent)
        val replacement = session()
        pending(replacement)
        assertNull(recover(old))
        assertNull(installer.getSessionInfo(old))
        callback(old, PackageInstaller.STATUS_SUCCESS)
        val activity = Robolectric.buildActivity(InstallConfirmationActivity::class.java).get()
        assertEquals(InstallConfirmation.STALE, InstallConfirmation.openSystemConfirmation(activity, oldEnvelope))
        assertEquals(replacement, InstallConfirmation.pendingSession(ctx, pkg))
        assertEquals("Czeka na potwierdzenie instalacji", Prefs(ctx).externalInstallMessage)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun canceledSystemTokenExpiresCleanlyAndDisappearsFromRestoredCard() {
        val session = session()
        pending(session)
        requireNotNull(recover(session)).cancel()
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        assertEquals(InstallConfirmation.EXPIRED, InstallConfirmation.launch(activity, pkg, session))
        assertEquals(InstallConfirmation.EXPIRED, Prefs(ctx).externalInstallMessage)
        cacheCard()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(confirmButton(controller.get().window.decorView))
        controller.pause().stop().destroy()
    }

    @Test
    fun vanishedInstallerSessionCannotOpenSavedConfirmation() {
        val session = session()
        pending(session)
        val envelope = shadowOf(requireNotNull(recover(session))).savedIntent
        installer.abandonSession(session)
        val activity = Robolectric.buildActivity(InstallConfirmationActivity::class.java).get()
        assertEquals(InstallConfirmation.EXPIRED, InstallConfirmation.openSystemConfirmation(activity, envelope))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(recover(session))
    }

    @Test
    fun vanishedSessionIsReportedWhenCardIsRestoredWithoutClickingOldAction() {
        val session = session()
        pending(session)
        installer.abandonSession(session)
        assertNull(InstallConfirmation.pendingSession(ctx, pkg))
        assertEquals(InstallConfirmation.EXPIRED, Prefs(ctx).externalInstallMessage)
        assertNull(recover(session))
    }

    @Test
    fun expiredSystemActivityReportsAnErrorAndInvalidatesItsAction() {
        val session = session()
        pending(session)
        val envelope = shadowOf(requireNotNull(recover(session))).savedIntent
        val activity = Robolectric.buildActivity(ExpiredConfirmationHost::class.java).get()
        assertEquals(InstallConfirmation.EXPIRED, InstallConfirmation.openSystemConfirmation(activity, envelope))
        assertEquals(InstallConfirmation.EXPIRED, Prefs(ctx).externalInstallMessage)
        assertNull(InstallConfirmation.pendingSession(ctx, pkg))
        assertNull(recover(session))
    }

    @Test
    fun untrackedLegacyWaitingStatusIsNotPresentedAsAWorkingConfirmation() {
        Prefs(ctx).externalInstallMessage = "Czeka na potwierdzenie instalacji"
        assertNull(InstallConfirmation.pendingSession(ctx, pkg))
        assertEquals(InstallConfirmation.EXPIRED, Prefs(ctx).externalInstallMessage)
    }

    @Test
    fun separatePackagesKeepSeparateConfirmationActions() {
        val first = session()
        pending(first)
        val otherPackage = "pl.jardol.other"
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(otherPackage)
        val other = installer.createSession(params)
        InstallConfirmation.begin(ctx, otherPackage, other)
        InstallConfirmation.save(ctx, otherPackage, other, Intent("other.confirm"))
        assertEquals(first, InstallConfirmation.pendingSession(ctx, pkg))
        assertEquals(other, InstallConfirmation.pendingSession(ctx, otherPackage))
        InstallConfirmation.clear(ctx, pkg, first)
        assertNotNull(recover(other))
        assertEquals(other, InstallConfirmation.pendingSession(ctx, otherPackage))
    }

    @Test
    fun notificationRemainsAnOptionalRouteToTheSamePrivateAction() {
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val session = session()
        pending(session)
        val notification = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(recover(session), notification.contentIntent)
        callback(session, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertTrue(shadowOf(requireNotNull(notification.contentIntent)).isCanceled)
    }

    @Test
    fun confirmationActivityIsPrivateAndMainIgnoresInjectedConfirmationIntents() {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getActivityInfo(
            android.content.ComponentName(ctx, InstallConfirmationActivity::class.java), 0)
        assertFalse(info.exported)
        cacheCard()
        val controller = Robolectric.buildActivity(MainActivity::class.java,
            Intent(ctx, MainActivity::class.java).putExtra(Intent.EXTRA_INTENT, Intent("untrusted.action"))).setup()
        assertNull(confirmButton(controller.get().window.decorView))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    private fun session(): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        return installer.createSession(params).also { InstallConfirmation.begin(ctx, pkg, it) }
    }

    private fun pending(id: Int, apk: File? = null) {
        callback(id, PackageInstaller.STATUS_PENDING_USER_ACTION,
            Intent("system.confirm.$id").putExtra(PackageInstaller.EXTRA_SESSION_ID, id), apk)
    }

    private fun callback(id: Int, status: Int, confirm: Intent? = null, apk: File? = null) {
        val intent = Intent(ctx, InstallReceiver::class.java)
            .putExtra(Installer.EXTRA_SESSION, id)
            .putExtra(Installer.EXTRA_PKG, pkg)
            .putExtra(Installer.EXTRA_NAME, "E2Stream")
            .putExtra(Installer.EXTRA_REQUEST, true)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
        if (confirm != null) intent.putExtra(Intent.EXTRA_INTENT, confirm)
        if (apk != null) intent.putExtra(Installer.EXTRA_FILE, apk.absolutePath)
        InstallReceiver().onReceive(ctx, intent)
    }

    private fun recover(id: Int): PendingIntent? = PendingIntent.getActivity(ctx, id,
        Intent(ctx, InstallConfirmationActivity::class.java)
            .setData(Uri.parse("apk-updater://install-confirmation/$id")),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun cacheCard() {
        val item = AppItem(InstallRequest.REPOSITORY, "E2Stream", pkg, "0.18.75-beta.3",
            "e2stream.apk", "https://example.invalid/e2stream.apk", null, "", null,
            selfUpdate = true, source = "test", channel = AppItem.BETA)
        Prefs(ctx).apply {
            sources = listOf(Source("test", InstallRequest.REPOSITORY, channel = AppItem.BETA,
                blockedUntil = Long.MAX_VALUE))
            catalogJson = JSONArray().put(item.toJson()).toString()
        }
    }

    private fun confirmButton(view: View): Button? {
        if (view is Button && view.text == "Potwierdź instalację") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) confirmButton(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
