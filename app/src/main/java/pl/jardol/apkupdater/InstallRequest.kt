package pl.jardol.apkupdater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class InstallRequest(val version: String, val channel: String, val allowDowngrade: Boolean) {
    companion object {
        const val ACTION = "pl.jardol.apkupdater.action.REQUEST_INSTALL"
        const val EXTRA = "pl.jardol.apkupdater.extra."
        const val PACKAGE = "pl.jardol.e2stream"
        const val REPOSITORY = "JarDol/enigma2-epgscan"
        const val CERTIFICATE = "a49dd189ff764da20075deb49ab2807943b841ad63f63ba108b4d78364098da6"

        fun validate(
            caller: String?, certificates: List<String>, action: String?, repository: String?,
            pkg: String?, version: String?, channel: String?, allowDowngrade: Boolean,
        ): InstallRequest {
            require(caller == PACKAGE && certificates == listOf(CERTIFICATE)) {
                "Nieuprawniona aplikacja wywołująca"
            }
            require(action == ACTION && repository == REPOSITORY && pkg == PACKAGE) {
                "Nieobsługiwane zlecenie instalacji"
            }
            require(version != null && version.length <= 160 &&
                Regex("e2stream-[0-9]+(?:\\.[0-9]+)*(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?").matches(version)) {
                "Wymagany dokładny tag wydania E2Stream"
            }
            require(channel == AppItem.STABLE || channel == AppItem.BETA) { "Nieprawidłowy kanał wydania" }
            return InstallRequest(version, channel, allowDowngrade)
        }

        @Suppress("DEPRECATION")
        fun certificates(info: PackageInfo): List<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                else info.signatures
            return signatures?.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
            } ?: emptyList()
        }

        fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        internal fun archiveSignatureFlags(): Int =
            // Older archive parsers need GET_SIGNATURES to collect certificates, even on API 28+.
            signatureFlags() or PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        internal fun readArchive(ctx: Context, path: String): PackageInfo? =
            ctx.packageManager.getPackageArchiveInfo(path, archiveSignatureFlags())

        @Suppress("DEPRECATION")
        fun callerCertificates(ctx: Context, pkg: String): List<String> =
            certificates(ctx.packageManager.getPackageInfo(pkg, signatureFlags()))
    }

    fun validateRelease(tag: String, draft: Boolean, prerelease: Boolean, hasApk: Boolean) {
        require(tag == version && !draft && hasApk) { "Wybrane wydanie nie zawiera dostępnego APK" }
        require(channel != AppItem.STABLE || !prerelease) { "Wydanie beta nie należy do kanału stable" }
    }

    fun validateApk(pkg: String, versionName: String?, expectedVersion: String?, certificates: List<String>) {
        require(pkg == PACKAGE && versionName != null && versionName == expectedVersion) {
            "Pobrane APK ma inny pakiet lub wersję niż wybrane wydanie"
        }
        require(certificates == listOf(CERTIFICATE)) { "Nieprawidłowy podpis APK E2Stream" }
    }

    fun validateDowngrade(installedVersion: String?, candidateVersion: String?, installedCode: Long = -1, candidateCode: Long = -1) {
        require(allowDowngrade || (!Ver.newer(installedVersion, candidateVersion) &&
            !Installer.isDowngrade(installedCode, candidateCode))) {
            "Wybrana wersja jest starsza. Potwierdź downgrade w E2Stream"
        }
    }
}
