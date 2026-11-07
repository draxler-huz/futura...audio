package com.futura.aurora

import java.net.URLEncoder

/** One provider of search results. Implementations must return only licensed items. */
interface AuroraSource {
    val name: String

    suspend fun search(query: String, kind: AuroraKind, limit: Int): List<AuroraMediaItem>
}

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** Encodes one URL path segment. Spaces become %20, not +. */
internal fun encodePathSegment(value: String): String = urlEncode(value).replace("+", "%20")
