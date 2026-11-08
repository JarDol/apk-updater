package pl.jardol.apkupdater

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class HttpException(val code: Int, msg: String) : IOException(msg)

/**
 * GET z pamięcią podręczną ETag. Odpowiedź 304 z GitHuba nie zużywa limitu zapytań
 * (60/h bez tokena, wspólne dla całej sieci domowej).
 */
object Http {
    const val UA = "JarDol-APK-Updater"

    fun get(ctx: Context, url: String, token: String): String {
        val dir = File(ctx.cacheDir, "http").apply { mkdirs() }
        val key = sha1(url)
        val body = File(dir, "$key.body")
        val etag = File(dir, "$key.etag")

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        if (url.startsWith("https://api.github.com/")) {
            c.setRequestProperty("Accept", "application/vnd.github+json")
        }
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        if (body.exists() && etag.exists()) c.setRequestProperty("If-None-Match", etag.readText())
        try {
            when (val code = c.responseCode) {
                200 -> {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    body.writeText(text)
                    c.getHeaderField("ETag")?.let { etag.writeText(it) } ?: etag.delete()
                    return text
                }
                304 -> return body.readText()
                else -> {
                    if ((code == 403 || code == 429) && c.getHeaderField("X-RateLimit-Remaining") == "0") {
                        val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                        val mins = reset?.let { ((it * 1000 - System.currentTimeMillis()) / 60000).coerceAtLeast(1) }
                        throw HttpException(code, "Limit zapytań GitHub wyczerpany" +
                            (mins?.let { " (odnowi się za ok. $it min)" } ?: "") +
                            ". Dodaj token w ustawieniach.")
                    }
                    throw HttpException(code, "HTTP $code: $url")
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
