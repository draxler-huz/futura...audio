package com.futura.sources

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The registry decides which sources get queried, so the gating rules are
 * pinned here: a source is queried only when it is wanted and, if it needs a
 * key, when that key is configured.
 */
class SourceRegistryTest {

    private val registry = SourceRegistry()
    private val keys = setOf("JAMENDO_CLIENT_ID", "PEXELS_API_KEY", "PIXABAY_API_KEY")

    @Test
    fun `key-based source turns on once its key is configured`() {
        val jamendo = registry.byId("jamendo")!!
        assertThat(jamendo.enabled).isTrue()
        assertThat(jamendo.isUsable(configuredKeys = emptySet())).isFalse()
        assertThat(jamendo.isUsable(configuredKeys = keys)).isTrue()
    }

    @Test
    fun `key-based source without its key reports needs-key`() {
        val status = registry.statusFor(overrides = emptyMap(), configuredKeys = emptySet())
        assertThat(status["jamendo"]).isEqualTo(SourceStatus.NEEDS_KEY)
        assertThat(status["pexels"]).isEqualTo(SourceStatus.NEEDS_KEY)
    }

    @Test
    fun `configured keys move sources from needs-key to on`() {
        val status = registry.statusFor(overrides = emptyMap(), configuredKeys = keys)
        assertThat(status["jamendo"]).isEqualTo(SourceStatus.ON)
        assertThat(status["pexels"]).isEqualTo(SourceStatus.ON)
        assertThat(status["pixabay"]).isEqualTo(SourceStatus.ON)
    }

    @Test
    fun `keyless sources are on without any keys`() {
        val status = registry.statusFor(overrides = emptyMap(), configuredKeys = emptySet())
        assertThat(status["audius"]).isEqualTo(SourceStatus.ON)
        assertThat(status["archive_video"]).isEqualTo(SourceStatus.ON)
    }

    @Test
    fun `a user turning a source off keeps it off even with the key present`() {
        val off = mapOf("jamendo" to false)
        val status = registry.statusFor(overrides = off, configuredKeys = keys)
        assertThat(status["jamendo"]).isEqualTo(SourceStatus.OFF)
        assertThat(registry.enabledSources(off, keys).map { it.id })
            .doesNotContain("jamendo")
    }

    @Test
    fun `a user can turn on a source that is off by default`() {
        val on = mapOf("fma" to true)
        // fma still needs its key, so it is not queried without one.
        assertThat(registry.enabledSources(on, emptySet()).map { it.id })
            .doesNotContain("fma")
        assertThat(registry.enabledSources(on, setOf("FMA_API_KEY")).map { it.id })
            .contains("fma")
    }

    @Test
    fun `enabledSources never returns a source missing its key`() {
        val ids = registry.enabledSources(emptyMap(), emptySet()).map { it.id }
        assertThat(ids).doesNotContain("jamendo")
        assertThat(ids).doesNotContain("pexels")
        assertThat(ids).doesNotContain("pixabay")
        assertThat(ids).contains("audius")
    }

    @Test
    fun `every key-requiring source names the key it needs`() {
        registry.all
            .filter { it.requiresKey }
            .forEach { assertThat(it.keyName).isNotNull() }
    }
}
