package pl.jardol.apkupdater

/** Porównywanie wersji X.Y.Z (z tagów typu v1.2.3, e2stream-1.2.3 itp.). */
object Ver {
    private val NUM = Regex("\\d+(\\.\\d+)*")

    fun norm(s: String?): String? = s?.let { NUM.find(it)?.value }

    /** true, jeśli [a] jest nowsza niż [b]. */
    fun newer(a: String?, b: String?): Boolean {
        val x = norm(a)?.split(".")?.map { it.toIntOrNull() ?: 0 } ?: return false
        val y = norm(b)?.split(".")?.map { it.toIntOrNull() ?: 0 } ?: return true
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
