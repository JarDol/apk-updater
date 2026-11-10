package pl.jardol.apkupdater

/** Porównywanie wersji semver, także prerelease X.Y.Z-beta.N. */
object Ver {
    private val VERSION = Regex("(?i)\\d+(?:\\.\\d+)*(?:-[0-9a-z]+(?:[.-][0-9a-z]+)*)?")

    fun norm(s: String?): String? = s?.let { VERSION.find(it)?.value }

    fun newer(a: String?, b: String?): Boolean = compare(a, b) > 0

    private fun compare(a: String?, b: String?): Int {
        if (a == null) return -1
        if (b == null) return 1
        fun split(value: String): Pair<List<Int>, List<String>?> {
            val parts = value.removePrefix("v").split('-', limit = 2)
            return parts[0].split('.').map { it.toIntOrNull() ?: 0 } to parts.getOrNull(1)?.split('.')
        }
        val (aCore, aPre) = split(a)
        val (bCore, bPre) = split(b)
        for (i in 0 until maxOf(aCore.size, bCore.size)) {
            val diff = aCore.getOrElse(i) { 0 }.compareTo(bCore.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        if (aPre == null || bPre == null) return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            else -> -1
        }
        for (i in 0 until maxOf(aPre.size, bPre.size)) {
            if (i >= aPre.size) return -1
            if (i >= bPre.size) return 1
            val left = aPre[i]
            val right = bPre[i]
            val leftNum = left.toIntOrNull()
            val rightNum = right.toIntOrNull()
            val diff = when {
                leftNum != null && rightNum != null -> leftNum.compareTo(rightNum)
                leftNum != null -> -1
                rightNum != null -> 1
                else -> left.compareTo(right)
            }
            if (diff != 0) return diff
        }
        return 0
    }
}
