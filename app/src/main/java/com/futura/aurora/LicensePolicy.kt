package com.futura.aurora

/**
 * Which licenses a search shows. COMMERCIAL is the default. STRICT keeps
 * only items that need no attribution, and it shows fewer results.
 */
enum class LicensePolicy {
    COMMERCIAL,
    STRICT;

    fun allows(license: AuroraLicense): Boolean = when (this) {
        COMMERCIAL -> true
        STRICT -> license == AuroraLicense.CC0 || license == AuroraLicense.PUBLIC_DOMAIN
    }
}
