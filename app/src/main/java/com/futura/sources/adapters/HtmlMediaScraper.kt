package com.futura.sources.adapters

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Shared scraping helpers for sources that have no public API.
 *
 * The rules are the same everywhere: resolve relative URLs, prefer a
 * `<video>`/`<source>` element, fall back to JSON-LD `VideoObject`, and read
 * the visible license text. When the license cannot be read, the caller
 * treats the item as non-commercial.
 */
object HtmlMediaScraper {

    /** One scraped media file, before it becomes a [com.futura.sources.SourceMediaItem]. */
    data class Scraped(
        val pageUrl: String,
        val title: String,
        val mediaUrl: String?,
        val thumbnailUrl: String?,
        val licenseText: String?
    )

    /** Parses a search-results page, following [linkSelector] to each item. */
    fun parseSearchPage(
        html: String,
        baseUrl: String,
        linkSelector: String,
        limit: Int
    ): List<Scraped> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(linkSelector).mapNotNull { node ->
            val href = node.absUrl("href").ifBlank { node.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val title = (node.attr("title").ifBlank { node.text() }).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Scraped(
                pageUrl = href,
                title = title,
                mediaUrl = null,
                thumbnailUrl = node.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() },
                licenseText = null
            )
        }.distinctBy { it.pageUrl }.take(limit)
    }

    /**
     * Extracts the media URL from a single asset page. Tries, in order:
     * a `<video src>`, a `<source src>`, then a JSON-LD `VideoObject`.
     */
    fun extractMediaUrl(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        doc.selectFirst("video[src]")?.let { video ->
            video.absUrl("src").takeIf { it.isNotBlank() }?.let { return it }
        }
        doc.selectFirst("source[src]")?.let { source ->
            source.absUrl("src").takeIf { it.isNotBlank() }?.let { return it }
        }
        return fromJsonLd(doc)
    }

    /** Reads a JSON-LD VideoObject's contentUrl, if the page has one. */
    private fun fromJsonLd(doc: Document): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val url = runCatching { contentUrlFrom(script.data()) }.getOrNull()
            if (url != null) return url
        }
        return null
    }

    private fun contentUrlFrom(json: String): String? {
        val trimmed = json.trim()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        val objects = when (root) {
            is JSONArray -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            is JSONObject -> listOf(root)
            else -> emptyList()
        }
        for (obj in objects) {
            val type = obj.optString("@type")
            if (type.equals("VideoObject", ignoreCase = true)) {
                obj.optString("contentUrl").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    /** Best-effort license text from a page's visible content. */
    fun extractLicenseText(html: String): String? {
        val doc = Jsoup.parse(html)
        val text = doc.text()
        val match = Regex("(CC0|CC BY[^.]{0,20}|Public Domain|Attribution[^.]{0,30})", RegexOption.IGNORE_CASE)
            .find(text)
        return match?.value
    }
}
