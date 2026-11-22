package pl.jardol.apkupdater

import android.content.pm.PackageInstaller
import android.util.Log
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

    fun cleanupInstalled(cacheDir: File, hasActiveSessions: Boolean, matchesInstalled: (File) -> Boolean): Boolean {
        if (hasActiveSessions) return false
        val root = File(cacheDir, "apk").canonicalFile
        if (!root.exists()) return true
        val entries = root.listFiles()
        if (entries == null) {
            Log.w("InstallFiles", "Nie można odczytać katalogu pobrań: $root")
            return false
        }
        for (entry in entries) {
            val apk = when {
                entry.isDirectory && entry.name.startsWith(PREFIX) -> File(entry, APK)
                entry.isFile && entry.name.endsWith(".apk", ignoreCase = true) -> entry
                else -> continue
            }
            if (!apk.isFile || apk.canonicalFile != apk.absoluteFile || !matchesInstalled(apk)) continue
            if (apk.parentFile == root) {
                val part = File(root, apk.name + ".part")
                if (!apk.delete()) Log.w("InstallFiles", "Nie można usunąć $apk")
                if (part.exists() && !part.delete()) Log.w("InstallFiles", "Nie można usunąć $part")
            } else if (!cleanup(cacheDir, apk.absolutePath)) {
                Log.w("InstallFiles", "Nie można usunąć plików instalacji: $apk")
            }
        }
        return true
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
