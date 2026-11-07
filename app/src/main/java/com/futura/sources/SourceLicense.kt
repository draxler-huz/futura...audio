package com.futura.sources

/**
 * The licenses the aggregator understands. [commercialUseAllowed] drives the
 * commercial-only filter; [requiresAttribution] drives the "Copy attribution"
 * action. NC and ND variants are not represented, so such items are dropped
 * when they are parsed.
 */
enum class SourceLicense(
    val label: String,
    val requiresAttribution: Boolean,
    val commercialUseAllowed: Boolean
) {
    CC0("CC0", requiresAttribution = false, commercialUseAllowed = true),
    PUBLIC_DOMAIN("Public Domain", requiresAttribution = false, commercialUseAllowed = true),
    CC_BY("CC-BY", requiresAttribution = true, commercialUseAllowed = true),
    CC_BY_SA("CC-BY-SA", requiresAttribution = true, commercialUseAllowed = true),
    CC_BY_ND("CC-BY-ND", requiresAttribution = true, commercialUseAllowed = true),

    /** A source's own permissive license (e.g. Pexels, Jamendo). */
    SOURCE_LICENSE("Source License", requiresAttribution = false, commercialUseAllowed = true),

    /** Free for personal/educational use only; not for redistribution. */
    NON_COMMERCIAL("Non-commercial", requiresAttribution = true, commercialUseAllowed = false),

    /** Live broadcast; playable but not redistributable. */
    RADIO_STREAM("Radio (streaming)", requiresAttribution = false, commercialUseAllowed = false),

    /** Metadata/link-out only; nothing is streamed or downloaded. */
    METADATA_ONLY("Metadata only", requiresAttribution = false, commercialUseAllowed = false),

    /** Unknown or missing license. Treated as the most restrictive. */
    UNKNOWN("Unknown", requiresAttribution = true, commercialUseAllowed = false);

    companion object {
        /**
         * Maps a Creative Commons license URL to a license, or null for NC,
         * ND, or unrecognised URLs.
         */
        fun fromCreativeCommonsUrl(url: String?): SourceLicense? {
            if (url.isNullOrBlank()) return null
            val u = url.lowercase()
            return when {
                "publicdomain/zero" in u -> CC0
                "publicdomain/mark" in u -> PUBLIC_DOMAIN
                "/licenses/by-sa/" in u -> CC_BY_SA
                "/licenses/by-nd/" in u -> CC_BY_ND
                "/licenses/by/" in u -> CC_BY
                else -> null
            }
        }

        /**
         * Maps a human-readable license label such as "CC BY-SA 4.0" to a
         * license, or null for NC, ND, or unknown labels.
         */
        fun fromLabel(label: String?): SourceLicense? {
            if (label.isNullOrBlank()) return null
            val s = label.uppercase()
            if (Regex("\\b(NC|NON-?COMMERCIAL)\\b").containsMatchIn(s)) return NON_COMMERCIAL
            if (Regex("\\b(ND|NO-?DERIV)").containsMatchIn(s)) return CC_BY_ND
            return when {
                s.startsWith("CC0") || s.contains("ZERO") -> CC0
                s.startsWith("PUBLIC DOMAIN") || s == "PD" -> PUBLIC_DOMAIN
                s.contains("BY-SA") -> CC_BY_SA
                Regex("\\bBY\\b").containsMatchIn(s) -> CC_BY
                else -> null
            }
        }
    }
}
