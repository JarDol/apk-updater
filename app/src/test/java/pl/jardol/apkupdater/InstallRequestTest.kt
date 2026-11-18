package pl.jardol.apkupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallRequestTest {
    private val beta = "e2stream-0.18.75-beta.1"
    private val certificate = InstallRequest.CERTIFICATE

    @Test
    fun authenticatedRequestKeepsExactReleaseChannelAndDowngradeConsent() {
        val request = request()
        assertEquals(beta, request.version)
        assertEquals(AppItem.BETA, request.channel)
        assertFalse(request.allowDowngrade)
        assertTrue(request(allowDowngrade = true).allowDowngrade)
    }

    @Test
    fun untrustedCallerCannotAuthorizeEvenConfirmedDowngrade() {
        for (caller in listOf(null, "untrusted.app", InstallRequest.PACKAGE)) {
            assertThrows(IllegalArgumentException::class.java) {
                request(caller = caller, certificates = listOf("forged"), allowDowngrade = true)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { request(caller = null) }
        assertThrows(IllegalArgumentException::class.java) { request(caller = "untrusted.app") }
        assertThrows(IllegalArgumentException::class.java) { request(certificates = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { request(certificates = listOf(certificate, "another")) }
    }

    @Test
    fun rejectsOtherRepositoriesPackagesAndActions() {
        assertThrows(IllegalArgumentException::class.java) { request(repository = "untrusted/repo") }
        assertThrows(IllegalArgumentException::class.java) { request(pkg = "another.package") }
        assertThrows(IllegalArgumentException::class.java) { request(action = null) }
    }

    @Test
    fun requiresFullReleaseTagAndExplicitValidChannel() {
        for (version in listOf(null, "", "0.18.75-beta.1", "latest", "e2stream-../bad", "$beta/asset")) {
            assertThrows(IllegalArgumentException::class.java) { request(version = version) }
        }
        for (channel in listOf(null, "", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) { request(channel = channel) }
        }
    }

    @Test
    fun acceptsOnlyExactPublishedReleaseWithApkAndMatchingChannel() {
        request().validateRelease(beta, false, true, true)
        request(channel = AppItem.STABLE).validateRelease(beta, false, false, true)
        assertThrows(IllegalArgumentException::class.java) {
            request(channel = AppItem.STABLE).validateRelease(beta, false, true, true)
        }
        assertThrows(IllegalArgumentException::class.java) { request().validateRelease("e2stream-0.18.76", false, true, true) }
        assertThrows(IllegalArgumentException::class.java) { request().validateRelease(beta, true, true, true) }
        assertThrows(IllegalArgumentException::class.java) { request().validateRelease(beta, false, true, false) }
    }

    @Test
    fun betaChannelMaySelectAnExactStableRelease() {
        val stable = "e2stream-0.18.75"
        request(version = stable).validateRelease(stable, false, false, true)
    }

    @Test
    fun downgradeNeedsConsentByBothVersionNameAndActualVersionCode() {
        assertThrows(IllegalArgumentException::class.java) {
            request().validateDowngrade("0.18.75", "0.18.75-beta.1", 150, 151)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request().validateDowngrade("0.18.74", "0.18.75-beta.1", 150, 149)
        }
        request(allowDowngrade = true).validateDowngrade("0.18.75", "0.18.75-beta.1", 150, 149)
        request().validateDowngrade("0.18.74", "0.18.75-beta.1", 149, 150)
        request().validateDowngrade("0.18.74", "0.18.74", 149, 149)
    }

    @Test
    fun checksDownloadedApkPackageVersionAndPinnedSigner() {
        request().validateApk(InstallRequest.PACKAGE, "0.18.75-beta.1", "0.18.75-beta.1", listOf(certificate))
        assertThrows(IllegalArgumentException::class.java) {
            request().validateApk("another.package", "0.18.75-beta.1", "0.18.75-beta.1", listOf(certificate))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request().validateApk(InstallRequest.PACKAGE, "0.18.75", "0.18.75-beta.1", listOf(certificate))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request().validateApk(InstallRequest.PACKAGE, null, null, listOf(certificate))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request().validateApk(InstallRequest.PACKAGE, "0.18.75-beta.1", "0.18.75-beta.1", listOf("forged"))
        }
    }

    private fun request(
        caller: String? = InstallRequest.PACKAGE,
        certificates: List<String> = listOf(certificate),
        action: String? = InstallRequest.ACTION,
        repository: String? = InstallRequest.REPOSITORY,
        pkg: String? = InstallRequest.PACKAGE,
        version: String? = beta,
        channel: String? = AppItem.BETA,
        allowDowngrade: Boolean = false,
    ) = InstallRequest.validate(caller, certificates, action, repository, pkg, version, channel, allowDowngrade)
}
