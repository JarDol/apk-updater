package pl.jardol.apkupdater

import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import java.util.UUID

internal object InstallFiles {
    private const val APK = "update.apk"
    private const val PREFIX = "install-"

    fun newDownload(cacheDir: File): File {
        val root = File(cacheDir, "apk")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Nie mogę utworzyć katalogu pobierania")
        val dir = File(root, PREFIX + UUID.randomUUID())
        if (!dir.mkdir()) throw IOException("Nie mogę utworzyć katalogu instalacji")
        return File(dir, APK)
    }

    fun matchesResult(session: Int, resultSession: Int, pkg: String, resultPackage: String?): Boolean =
        session >= 0 && session == resultSession && pkg.isNotEmpty() && pkg == resultPackage

    fun cleanupOnResult(
        cacheDir: File, path: String, status: Int,
        session: Int, resultSession: Int, pkg: String, resultPackage: String?,
    ): Boolean {
        if (status != PackageInstaller.STATUS_SUCCESS ||
            !matchesResult(session, resultSession, pkg, resultPackage)) return false
        return cleanup(cacheDir, path)
    }

    private fun cleanup(cacheDir: File, path: String): Boolean {
        val root = File(cacheDir, "apk").canonicalFile
        val apk = File(path).canonicalFile
        val dir = apk.parentFile ?: return false
        if (dir.parentFile != root || !dir.name.startsWith(PREFIX) || apk.name != APK) return false
        var ok = true
        for (file in listOf(apk, File(dir, "$APK.part"))) {
            if (file.exists() && !file.delete()) ok = false
        }
        if (dir.exists() && !dir.delete()) ok = false
        return ok
    }
}
