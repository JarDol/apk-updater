package pl.jardol.apkupdater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class NeedInstallPermission : Exception("Zezwól tej aplikacji na instalowanie apek, potem spróbuj ponownie")

/** Pobieranie APK i instalacja przez PackageInstaller (sieć — poza wątkiem UI). */
object Installer {

    const val EXTRA_PKG = "pkg"
    const val EXTRA_NAME = "name"
    const val EXTRA_FILE = "file"
    const val EXTRA_SESSION = "session"

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Otwiera ekran „Instalowanie nieznanych aplikacji” dla tej apki. */
    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
    }

    /** Pobiera, sprawdza i przekazuje APK do instalacji. Wynik przychodzi do [InstallReceiver]. */
    fun downloadAndInstall(
        ctx: Context,
        item: AppItem,
        allowDowngrade: Boolean = false,
        progress: (Int) -> Unit,
    ) {
        if (!canInstall(ctx)) throw NeedInstallPermission()
        try {
            synchronized(this) {
                var last: IOException? = null
                var ok: File? = null
                for (attempt in 1..2) {
                    try {
                        val candidate = download(ctx, item, progress)
                        val candidateCode = verify(ctx, candidate, item.pkg)
                        if (isDowngrade(item.installedCode, candidateCode) && !allowDowngrade) {
                            throw IOException("Pobrane APK jest starsze. Wybierz wydanie ręcznie i potwierdź downgrade.")
                        }
                        ok = candidate
                        break
                    } catch (e: IOException) { last = e }
                }
                install(ctx, ok ?: throw last!!, item)
            }
        } finally {
            CacheCleanup.scheduleNow(ctx)
        }
    }

    private fun download(ctx: Context, item: AppItem, progress: (Int) -> Unit): File {
        val token = Prefs(ctx).tokenFor(item.source)
        val out = InstallFiles.newDownload(ctx.cacheDir)
        val tmp = File(out.parentFile, out.name + ".part")

        // Z tokenem: przez API (działa też dla prywatnych repo). Bez: zwykły link.
        val useApi = token.isNotEmpty() && item.apkApiUrl != null
        var url = URL(if (useApi) item.apkApiUrl else item.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", Http.UA)
            if (hops == 0 && useApi) {
                c.setRequestProperty("Accept", "application/octet-stream")
                c.setRequestProperty("Authorization", "Bearer $token")
            }
            val code = c.responseCode
            if (code in 300..399 && hops < 6) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Brak przekierowania")
                c.disconnect()
                url = URL(url, loc)   // dalej bez tokena (podpisany link do magazynu plików)
                hops++
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("Pobieranie: HTTP $code")
            }
            break
        }
        try {
            val total = c.contentLength.toLong()
            c.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var sum = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        sum += n
                        if (total > 0) {
                            val pct = (sum * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && tmp.length() != total) {
                throw IOException("Pobrano ${tmp.length()} z $total B — spróbuj ponownie")
            }
        } finally {
            c.disconnect()
        }
        if (!tmp.renameTo(out)) throw IOException("Nie mogę zapisać ${out.name}")
        return out
    }

    /** Pobrany plik musi być tą apką, którą deklaruje updater.json. */
    private fun verify(ctx: Context, apk: File, pkg: String): Long {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException("Pobrany plik nie jest poprawnym APK")
        if (info.packageName != pkg) {
            throw IOException("APK ma pakiet ${info.packageName}, oczekiwano $pkg")
        }
        return PackageInfoCompat.getLongVersionCode(info)
    }

    internal fun isDowngrade(installedCode: Long, apkCode: Long) =
        installedCode >= 0 && apkCode < installedCode

    private fun install(ctx: Context, apk: File, item: AppItem) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(item.pkg)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            // Bez pytania, jeśli to my zainstalowaliśmy tę apkę wcześniej (Android 12+)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                apk.inputStream().use { input ->
                    s.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        s.fsync(out)
                    }
                }
                val intent = Intent(ctx, InstallReceiver::class.java)
                    .setPackage(ctx.packageName)
                    .setData(Uri.parse("apk-updater://install/${requireNotNull(apk.parentFile).name}"))
                    .putExtra(EXTRA_PKG, item.pkg)
                    .putExtra(EXTRA_NAME, item.name)
                    .putExtra(EXTRA_FILE, apk.absolutePath)
                    .putExtra(EXTRA_SESSION, id)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            try { pi.abandonSession(id) } catch (_: Exception) { }
            throw e
        }
    }
}
