package pl.jardol.apkupdater

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pobieranie logów z apki, która je udostępnia (E2Stream):
 *   content://<pakiet>.updateinfo/logs           — lista plików ("name", "size", "modified"),
 *   content://<pakiet>.updateinfo/logs/<nazwa>   — plik.
 * Działa także wtedy, gdy główne okno apki się nie otwiera. Pliki trafiają na podłączony dysk (USB / karta),
 * a gdy go brak — do pamięci urządzenia.
 */
object Logs {

    data class Entry(val name: String, val size: Long)

    /** Pliki logu udostępniane przez apkę; null — apka nie udostępnia logów (starsza wersja albo inna apka). */
    fun list(ctx: Context, pkg: String): List<Entry>? = try {
        ctx.contentResolver.query(Uri.parse("content://$pkg.updateinfo/logs"), null, null, null, null)?.use { c ->
            val n = c.getColumnIndex("name")
            val s = c.getColumnIndex("size")
            if (n < 0) return null
            val out = ArrayList<Entry>()
            while (c.moveToNext()) out += Entry(c.getString(n), if (s >= 0) c.getLong(s) else 0)
            out
        }
    } catch (_: Exception) {
        null
    }

    /** Zapis wszystkich plików logu; zwraca opis miejsca (do komunikatu). */
    fun save(ctx: Context, pkg: String, appName: String, files: List<Entry>): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val folder = "${appName.replace(Regex("[^A-Za-z0-9_-]"), "")}-logi"
        val target = target(ctx, folder)
        for (e in files) {
            val name = "$stamp-${e.name}"
            ctx.contentResolver.openInputStream(Uri.parse("content://$pkg.updateinfo/logs/${Uri.encode(e.name)}"))!!.use { inp ->
                target.open(name).use { out -> inp.copyTo(out) }
            }
        }
        return target.where
    }

    private class Target(val where: String, val open: (String) -> OutputStream)

    private fun target(ctx: Context, folder: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            // Download/<apka>-logi na dysku USB / karcie (gdy jest), inaczej w pamięci urządzenia
            val vols = MediaStore.getExternalVolumeNames(ctx)
            val vol = vols.firstOrNull { it != MediaStore.VOLUME_EXTERNAL_PRIMARY } ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            val rel = "${Environment.DIRECTORY_DOWNLOADS}/$folder"
            val where = (if (vol == MediaStore.VOLUME_EXTERNAL_PRIMARY) "pamięć urządzenia" else "dysk $vol") + ": $rel"
            return Target(where) { name ->
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.getContentUri(vol), cv)
                    ?: throw IllegalStateException("nie można utworzyć pliku na $where")
                ctx.contentResolver.openOutputStream(uri)!!
            }
        }
        // Android 9 i starsze: katalog apki na dysku USB / karcie (Android/data/pl.jardol.apkupdater/files/<apka>-logi)
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        val base = dirs.drop(1).firstOrNull() ?: dirs.firstOrNull() ?: ctx.filesDir
        val dir = File(base, folder).apply { mkdirs() }
        return Target(dir.absolutePath) { name -> File(dir, name).outputStream() }
    }
}
