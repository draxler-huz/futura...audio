package com.futura.sources

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The licence → commercial-use mapping is a compliance gate, so it is pinned here. */
class SourceLicenseTest {

    @Test
    fun `creative commons url maps to the right licence`() {
        assertThat(SourceLicense.fromCreativeCommonsUrl("http://creativecommons.org/publicdomain/zero/1.0/"))
            .isEqualTo(SourceLicense.CC0)
        assertThat(SourceLicense.fromCreativeCommonsUrl("http://creativecommons.org/licenses/by/4.0/"))
            .isEqualTo(SourceLicense.CC_BY)
        assertThat(SourceLicense.fromCreativeCommonsUrl("http://creativecommons.org/licenses/by-sa/4.0/"))
            .isEqualTo(SourceLicense.CC_BY_SA)
        assertThat(SourceLicense.fromCreativeCommonsUrl("http://creativecommons.org/licenses/by-nc/4.0/"))
            .isNull()
        assertThat(SourceLicense.fromCreativeCommonsUrl(null)).isNull()
    }

    @Test
    fun `non-commercial labels never allow commercial use`() {
        val nc = SourceLicense.fromLabel("CC BY-NC 4.0")
        assertThat(nc).isEqualTo(SourceLicense.NON_COMMERCIAL)
        assertThat(nc!!.commercialUseAllowed).isFalse()
    }

    @Test
    fun `attribution-required licences are flagged`() {
        assertThat(SourceLicense.CC_BY.requiresAttribution).isTrue()
        assertThat(SourceLicense.CC_BY_SA.requiresAttribution).isTrue()
        assertThat(SourceLicense.CC0.requiresAttribution).isFalse()
        assertThat(SourceLicense.PUBLIC_DOMAIN.requiresAttribution).isFalse()
    }

    @Test
    fun `unknown licence is the most restrictive`() {
        assertThat(SourceLicense.UNKNOWN.commercialUseAllowed).isFalse()
        assertThat(SourceLicense.RADIO_STREAM.commercialUseAllowed).isFalse()
        assertThat(SourceLicense.METADATA_ONLY.commercialUseAllowed).isFalse()
    }
}
