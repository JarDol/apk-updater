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
}
