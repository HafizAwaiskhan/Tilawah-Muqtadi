package com.qurancompanion.engine.align

/** Similarity functions in [0, 1]. */
object Similarity {
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1 - editDistance / maxLength. */
    fun normalized(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / m
    }

    fun exact(a: String, b: String): Double = if (a == b) 1.0 else 0.0

    /** Normalized edit similarity between two unit sequences (e.g. phoneme lists). */
    fun sequence(a: List<String>, b: List<String>): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[b.size].toDouble() / maxOf(a.size, b.size)
    }
}
