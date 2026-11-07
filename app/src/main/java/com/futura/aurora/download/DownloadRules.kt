package com.futura.aurora.download

import java.net.URI

/** A download that must not be retried, such as a file over the size limit. */
class DownloadRejected(message: String) : Exception(message)

object DownloadRules {
    const val MAX_BYTES = 1L * 1024 * 1024 * 1024

    /** Only HTTPS links on the hosts Aurora sources use. Redirects are followed by the client. */
    private val allowedHosts = listOf("archive.org", "upload.wikimedia.org")

    fun isAllowed(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.startsWith('.')

    /** A file name from the item title and the link's extension. Never contains a path separator. */
    fun fileNameFor(title: String, url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
            ?: "bin"
        val base = title.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(80).ifBlank { "aurora" }
        return "$base.$ext"
    }
}
