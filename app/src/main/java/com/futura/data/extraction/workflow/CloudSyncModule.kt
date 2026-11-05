// app/src/main/java/com/futura/data/extraction/workflow/CloudSyncModule.kt
package com.futura.data.extraction.workflow

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the default [NoOpCloudSyncProvider] to [CloudSyncProvider], so
 * [F20CloudSyncHook] can be injected. Replace this binding when a real
 * provider exists.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudSyncModule {
    @Binds
    abstract fun bindCloudSyncProvider(impl: NoOpCloudSyncProvider): CloudSyncProvider
}
