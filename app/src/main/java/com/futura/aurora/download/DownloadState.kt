package com.futura.aurora.download

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Progress saved beside a partial download, so an interrupted run resumes instead of restarting. */
internal class DownloadState(
    val url: String,
    val etag: String?,
    val lastModified: String?,
    val total: Long,
    val done: LongArray
) {
    /** True only if the server's file is still the one this partial download came from. */
    fun matches(url: String, total: Long, etag: String?, lastModified: String?, chunkCount: Int): Boolean =
        this.url == url && this.total == total && this.etag == etag &&
            this.lastModified == lastModified && done.size == chunkCount

    fun toJson(): String = JSONObject()
        .put("url", url)
        .put("etag", etag ?: JSONObject.NULL)
        .put("lastModified", lastModified ?: JSONObject.NULL)
        .put("total", total)
        .put("done", JSONArray().apply { done.forEach { put(it) } })
        .toString()

    companion object {
        fun read(file: File): DownloadState? = try {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("done")
            DownloadState(
                url = o.getString("url"),
                etag = optional(o, "etag"),
                lastModified = optional(o, "lastModified"),
                total = o.getLong("total"),
                done = LongArray(arr.length()) { arr.getLong(it) }
            )
        } catch (e: Exception) {
            null
        }

        private fun optional(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.getString(key)
    }
}
