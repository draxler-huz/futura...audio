// app/src/main/java/com/futura/data/extraction/F22ExtractionModule.kt
package com.futura.data.extraction

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * F22 — Hilt bindings for the extraction subsystem.
 *
 * Two bindings today, both to their no-op defaults so the module
 * compiles and runs without external dependencies:
 *
 *   • [CloudSyncProvider]  → [NoOpCloudSyncProvider]     (F20)
 *   • [StemSeparationHook] → [NoOpStemSeparator]         (F11)
 *
 * **When a real implementation lands**, replace the binding:
 *
 *     // F11 — real stems model via the ML bridge
 *     @Binds @Singleton
 *     abstract fun bindStemSeparationHook(
 *         impl: MlBridgeStemSeparator
 *     ): StemSeparationHook
 *
 *     // F20 — WebDAV provider
 *     @Binds @Singleton
 *     abstract fun bindCloudSyncProvider(
 *         impl: WebDavCloudSyncProvider
 *     ): CloudSyncProvider
 *
 * One line each. Every consumer of the interface (`F21MediaExtractionViewModel`)
 * picks up the new binding without changes.
 *
 * The `NoOp*` classes remain in the codebase as test doubles — they are
 * bound only when no real implementation is provided.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class F22ExtractionModule {

    @Binds
    @Singleton
    abstract fun bindCloudSyncProvider(
        impl: NoOpCloudSyncProvider
    ): CloudSyncProvider

    @Binds
    @Singleton
    abstract fun bindStemSeparationHook(
        impl: NoOpStemSeparator
    ): StemSeparationHook
}
