package pl.jardol.apkupdater

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun successRemovesOnlyFilesForThatDownload() {
        val cache = temporary.root
        val first = InstallFiles.newDownload(cache)
        val second = InstallFiles.newDownload(cache)
        val firstPart = File(first.parentFile, first.name + ".part")
        val secondPart = File(second.parentFile, second.name + ".part")
        listOf(first, firstPart, second, secondPart).forEach { it.writeText("data") }

        assertNotEquals(first.parentFile, second.parentFile)
        assertTrue(InstallFiles.cleanupOnResult(cache, first.absolutePath, PackageInstaller.STATUS_SUCCESS, 42, 42, "app", "app"))
        assertFalse(first.exists())
        assertFalse(firstPart.exists())
        assertFalse(requireNotNull(first.parentFile).exists())
        assertEquals("data", second.readText())
        assertEquals("data", secondPart.readText())
    }

    @Test
    fun resultMustMatchBothSessionAndPackage() {
        assertTrue(InstallFiles.matchesResult(42, 42, "example.app", "example.app"))
        assertFalse(InstallFiles.matchesResult(42, 43, "example.app", "example.app"))
        assertFalse(InstallFiles.matchesResult(-1, -1, "example.app", "example.app"))
        assertFalse(InstallFiles.matchesResult(42, 42, "example.app", "other.app"))
        assertFalse(InstallFiles.matchesResult(42, 42, "example.app", null))
        assertFalse(InstallFiles.matchesResult(42, 42, "", ""))
    }

    @Test
    fun failureOrUnmatchedResultPreservesFiles() {
        val cache = temporary.root
        val apk = InstallFiles.newDownload(cache).apply { writeText("data") }
        val part = File(apk.parentFile, apk.name + ".part").apply { writeText("partial") }
        assertFalse(InstallFiles.cleanupOnResult(cache, apk.absolutePath, PackageInstaller.STATUS_PENDING_USER_ACTION, 42, 42, "app", "app"))
        assertFalse(InstallFiles.cleanupOnResult(cache, apk.absolutePath, PackageInstaller.STATUS_FAILURE_ABORTED, 42, 42, "app", "app"))
        assertFalse(InstallFiles.cleanupOnResult(cache, apk.absolutePath, PackageInstaller.STATUS_FAILURE, 42, 42, "app", "app"))
        assertFalse(InstallFiles.cleanupOnResult(cache, apk.absolutePath, PackageInstaller.STATUS_SUCCESS, 42, 43, "app", "app"))
        assertFalse(InstallFiles.cleanupOnResult(cache, apk.absolutePath, PackageInstaller.STATUS_SUCCESS, 42, 42, "app", "other"))
        assertEquals("data", apk.readText())
        assertEquals("partial", part.readText())
    }

    @Test
    fun cleanupRejectsPathsOutsideIsolatedDownloads() {
        val cache = temporary.root
        val unrelated = File(cache, "unrelated.apk").apply { writeText("data") }
        val legacy = File(File(cache, "apk"), "old.apk").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("data")
        }

        assertFalse(InstallFiles.cleanupOnResult(cache, unrelated.absolutePath, PackageInstaller.STATUS_SUCCESS, 42, 42, "app", "app"))
        assertFalse(InstallFiles.cleanupOnResult(cache, legacy.absolutePath, PackageInstaller.STATUS_SUCCESS, 42, 42, "app", "app"))
        assertTrue(unrelated.exists())
        assertTrue(legacy.exists())
    }

    @Test
    fun installedCleanupHandlesLegacyAndIsolatedFilesWithoutRemovingOthers() {
        val cache = temporary.root
        val matched = InstallFiles.newDownload(cache).apply { writeText("installed") }
        val part = File(matched.parentFile, matched.name + ".part").apply { writeText("partial") }
        val different = InstallFiles.newDownload(cache).apply { writeText("different") }
        val orphan = InstallFiles.newDownload(cache)
        val orphanPart = File(orphan.parentFile, orphan.name + ".part").apply { writeText("partial") }
        val legacy = File(File(cache, "apk"), "old.apk").apply { writeText("installed") }
        val legacyPart = File(legacy.parentFile, legacy.name + ".part").apply { writeText("partial") }

        assertTrue(InstallFiles.cleanupInstalled(cache, false) { it.readText() == "installed" })

        assertFalse(matched.exists())
        assertFalse(part.exists())
        assertFalse(legacy.exists())
        assertFalse(legacyPart.exists())
        assertEquals("different", different.readText())
        assertEquals("partial", orphanPart.readText())
    }

    @Test
    fun installedCleanupWaitsForActiveSessions() {
        val cache = temporary.root
        val apk = InstallFiles.newDownload(cache).apply { writeText("installed") }
        assertFalse(InstallFiles.cleanupInstalled(cache, true) { true })
        assertTrue(apk.exists())
        assertTrue(InstallFiles.cleanupInstalled(cache, false) { true })
        assertFalse(apk.exists())
    }

    @Test
    fun installedIdentityRequiresSamePackageVersionAndNonemptySignature() {
        val installed = ApkIdentity("example.app", 7, setOf("signature"))
        assertTrue(installed.matches(ApkIdentity("example.app", 7, setOf("signature"))))
        assertFalse(installed.matches(ApkIdentity("other.app", 7, setOf("signature"))))
        assertFalse(installed.matches(ApkIdentity("example.app", 8, setOf("signature"))))
        assertFalse(installed.matches(ApkIdentity("example.app", 7, setOf("other"))))
        assertFalse(ApkIdentity("example.app", 7, emptySet()).matches(ApkIdentity("example.app", 7, emptySet())))
    }
}
