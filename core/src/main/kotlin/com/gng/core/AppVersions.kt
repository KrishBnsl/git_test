package com.gng.core

/** Compares app versions such as "0.2.0", "v0.3.1" or the odd "0.1.01". */
object AppVersions {
    /** "v1.2.3-beta+5" becomes [1, 2, 3]. Returns null when it isn't a version at all. */
    fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() && it.all { n -> n >= 0 } }
    }

    /** Missing trailing parts count as zero, so "1.0" equals "1.0.0" and "0.1.01" equals "0.1.1". */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** True only when [candidate] is clearly newer. Anything unparseable is treated as not newer. */
    fun isNewer(candidate: String, current: String): Boolean = (compare(candidate, current) ?: 0) > 0
}
