package com.futura.aurora

import org.json.JSONArray

enum class AuroraKind { AUDIO, VIDEO }

/**
 * Licenses that Aurora will show. Non-commercial (NC) and no-derivatives (ND)
 * licenses are never represented here, so those items are dropped at parse time.
 */
enum class AuroraLicense(val label: String, val requiresAttribution: Boolean) {
    CC0("CC0", requiresAttribution = false),
    PUBLIC_DOMAIN("Public Domain", requiresAttribution = false),
    CC_BY("CC-BY", requiresAttribution = true),
    CC_BY_SA("CC-BY-SA", requiresAttribution = true);

    companion object {
        /** From a Creative Commons license URL, or null for NC, ND, or unknown licenses. */
        fun fromCreativeCommonsUrl(url: String): AuroraLicense? {
            val u = url.lowercase()
            return when {
                "publicdomain/zero" in u -> CC0
                "publicdomain/mark" in u -> PUBLIC_DOMAIN
                "/licenses/by-sa/" in u -> CC_BY_SA
                "/licenses/by/" in u -> CC_BY
                else -> null
            }
        }

        /** From a human-readable label such as "CC BY-SA 4.0", or null for NC, ND, or unknown licenses. */
        fun fromLabel(label: String): AuroraLicense? {
            val s = label.uppercase()
            if (Regex("\\b(NC|ND)\\b").containsMatchIn(s)) return null
            return when {
                s.startsWith("CC0") -> CC0
                s.startsWith("PUBLIC DOMAIN") || s == "PD" -> PUBLIC_DOMAIN
                s.contains("BY-SA") -> CC_BY_SA
                Regex("\\bBY\\b").containsMatchIn(s) -> CC_BY
                else -> null
            }
        }
    }
}

/**
 * One search result from any source. [id] is stable across searches
 * ("<source>:<native id>"). [downloadUrl] is only set for files the source
 * offers for download directly.
 */
data class AuroraMediaItem(
    val id: String,
    val title: String,
    val creator: String?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
    val previewUrl: String?,
    val downloadUrl: String?,
    val license: AuroraLicense,
    val sourceName: String,
    val kind: AuroraKind,
    val sourcePageUrl: String
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
    }

    /** Text for the attribution popup. Only meaningful when the license requires attribution. */
    fun attributionText(): String = buildString {
        append('"').append(title).append('"')
        if (!creator.isNullOrBlank()) append(" by ").append(creator)
        append(" is licensed under ").append(license.label)
        append(". Source: ").append(sourceName)
        append(" (").append(sourcePageUrl).append(").")
    }
}

internal fun firstString(value: Any?): String? = when (value) {
    is String -> value.takeIf { it.isNotBlank() }
    is JSONArray -> if (value.length() > 0) value.optString(0).takeIf { it.isNotBlank() } else null
    else -> null
}
