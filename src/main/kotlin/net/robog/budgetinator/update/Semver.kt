package net.robog.budgetinator.update

/** Semantic version (semver.org 2.0.0). Build metadata is kept but ignored for ordering. */
data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
    val build: String? = null,
) : Comparable<Semver> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: Semver): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A version without pre-release identifiers ranks higher than one with them.
        if (preRelease.isEmpty() || other.preRelease.isEmpty())
            return compareValues(other.preRelease.size.coerceAtMost(1), preRelease.size.coerceAtMost(1))
        for (k in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[k], other.preRelease[k])
            if (c != 0) return c
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (preRelease.isNotEmpty()) append('-').append(preRelease.joinToString("."))
        if (build != null) append('+').append(build)
    }

    companion object {
        private val PATTERN = Regex(
            """^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
                """(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?""" +
                """(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$"""
        )

        /** Parses `1.2.3`, `v1.2.3`, `1.2.3-beta.1+sha`; returns null for anything else. */
        fun parse(text: String): Semver? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (a, b, c, pre, build) = m.destructured
            return Semver(
                a.toIntOrNull() ?: return null,
                b.toIntOrNull() ?: return null,
                c.toIntOrNull() ?: return null,
                if (pre.isEmpty()) emptyList() else pre.split('.'),
                build.ifEmpty { null },
            )
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = a.all { it.isDigit() }
            val bn = b.all { it.isDigit() }
            return when {
                an && bn -> a.toBigInteger().compareTo(b.toBigInteger())
                an -> -1 // numeric identifiers have lower precedence than alphanumeric
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
