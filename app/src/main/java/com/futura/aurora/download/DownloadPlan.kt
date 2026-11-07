package com.futura.aurora.download

/** Splits a file into byte ranges for parallel download. Range ends are inclusive. */
object DownloadPlan {
    const val PARALLEL_THRESHOLD_BYTES = 8L * 1024 * 1024
    const val MAX_PARALLEL_CHUNKS = 4

    fun chunks(total: Long, parallel: Int = MAX_PARALLEL_CHUNKS): List<LongRange> {
        require(total > 0) { "total must be positive" }
        if (parallel <= 1 || total < PARALLEL_THRESHOLD_BYTES) return listOf(0L until total)
        val size = (total + parallel - 1) / parallel
        return (0 until parallel).mapNotNull { i ->
            val start = i * size
            if (start >= total) null else start until minOf(start + size, total)
        }
    }
}
