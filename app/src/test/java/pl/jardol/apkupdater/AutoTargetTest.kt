package pl.jardol.apkupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTargetTest {

    private fun item(channel: String, latest: String, prerelease: Boolean, appChannel: String?, selfUpdate: Boolean = true) =
        AppItem("JarDol/enigma2-epgscan", "E2Stream", "pl.jardol.e2stream", latest, "a.apk", "https://x/$latest.apk", null, "", null,
            prerelease = prerelease, selfUpdate = selfUpdate, installed = "0.18.53", installedCode = 208, appAuto = true,
            appChannel = appChannel, channel = channel, source = "s1")

    @Test
    fun stableAppIgnoresBetaShownByUpdater() {
        val stable = item(AppItem.STABLE, "0.18.53", false, AppItem.STABLE)
        val beta = item(AppItem.BETA, "0.18.54-beta.4", true, AppItem.STABLE)
        // źródło w APK Updaterze na becie — apka ustawiona na stabilne: nic do instalacji
        assertNull(Catalog.autoTarget(listOf(stable, beta), beta))
    }

    @Test
    fun stableAppGetsNewerStableEvenWhenUpdaterShowsBeta() {
        val stable = item(AppItem.STABLE, "0.18.54", false, AppItem.STABLE)
        val beta = item(AppItem.BETA, "0.18.55-beta.1", true, AppItem.STABLE)
        assertEquals("0.18.54", Catalog.autoTarget(listOf(stable, beta), beta)?.latest)
    }

    @Test
    fun betaAppGetsBeta() {
        val stable = item(AppItem.STABLE, "0.18.53", false, AppItem.BETA)
        val beta = item(AppItem.BETA, "0.18.54-beta.4", true, AppItem.BETA)
        assertEquals("0.18.54-beta.4", Catalog.autoTarget(listOf(stable, beta), stable)?.latest)
    }

    @Test
    fun appWithoutChannelInfoIsStable() {
        val stable = item(AppItem.STABLE, "0.18.53", false, null)
        val beta = item(AppItem.BETA, "0.18.54-beta.4", true, null)
        assertNull(Catalog.autoTarget(listOf(stable, beta), beta))
    }

    @Test
    fun appWithoutOwnUpdateFollowsSourceChannel() {
        val beta = item(AppItem.BETA, "0.18.54-beta.4", true, null, selfUpdate = false)
        assertEquals("0.18.54-beta.4", Catalog.autoTarget(listOf(beta), beta)?.latest)
    }
}
