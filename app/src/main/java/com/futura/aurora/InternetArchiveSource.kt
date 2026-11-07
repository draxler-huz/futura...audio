package com.futura.aurora

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Internet Archive Advanced Search. No key needed. Only items whose
 * licenseurl is a Creative Commons or public-domain license are kept.
 */
@Singleton
class InternetArchiveSource @Inject constructor(
    private val client: HttpClient,
    private val limiter: HostRateLimiter
) : AuroraSource {

    override val name = "Internet Archive"

    override suspend fun search(query: String, kind: AuroraKind, limit: Int): List<AuroraMediaItem> {
        val mediaType = if (kind == AuroraKind.AUDIO) "audio" else "movies"
        val q = "($query) AND mediatype:$mediaType AND licenseurl:*creativecommons.org*"
        val url = "https://archive.org/advancedsearch.php?q=${urlEncode(q)}" +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl" +
            "&rows=$limit&page=1&output=json"

        limiter.await(HOST)
        val docs = JSONObject(client.get(url).bodyAsText())
            .getJSONObject("response")
            .getJSONArray("docs")
        return (0 until docs.length()).mapNotNull { parse(docs.getJSONObject(it), kind) }
    }

    private fun parse(doc: JSONObject, kind: AuroraKind): AuroraMediaItem? {
        val identifier = doc.optString("identifier").takeIf { it.isNotBlank() } ?: return null
        val license = AuroraLicense.fromCreativeCommonsUrl(doc.optString("licenseurl")) ?: return null
        return AuroraMediaItem(
            id = "archive:$identifier",
            title = doc.optString("title").ifBlank { identifier },
            creator = firstString(doc.opt("creator")),
            durationSec = null,
            thumbnailUrl = "https://archive.org/services/img/$identifier",
            previewUrl = null,
            downloadUrl = null,
            license = license,
            sourceName = name,
            kind = kind,
            sourcePageUrl = "https://archive.org/details/$identifier"
        )
    }

    private companion object {
        const val HOST = "archive.org"
    }
}
