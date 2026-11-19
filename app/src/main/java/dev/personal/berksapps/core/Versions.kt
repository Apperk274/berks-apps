package dev.personal.berksapps.core

object Versions {
    fun compareNames(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0L }
            val r = right.getOrElse(i) { 0L }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    fun updateAvailable(
        installedCode: Long?,
        installedName: String?,
        catalogCode: Long?,
        catalogName: String?,
    ): Boolean {
        if (installedCode == null && installedName == null) return false
        if (installedCode != null && catalogCode != null) return catalogCode > installedCode
        if (installedName != null && catalogName != null) {
            return compareNames(catalogName, installedName) > 0
        }
        return false
    }

    private fun parts(version: String): List<Long> =
        version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.take(18).toLongOrNull() ?: 0L }
}
