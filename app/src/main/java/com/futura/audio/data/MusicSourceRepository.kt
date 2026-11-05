package com.futura.audio.data

import com.futura.audio.domain.*
import com.futura.audio.pipeline.compliant.Category1Factory
import com.futura.audio.pipeline.advanced.Category2Factory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicSourceRepository @Inject constructor(
    private val category1Factory: Category1Factory,
    private val category2Factory: Category2Factory
) {
    suspend fun fetchAndResolve(url: String, category: PipelineCategory): List<MediaItem> {
        return when (category) {
            PipelineCategory.COMPLIANT_STANDARD -> category1Factory.resolve(url)
            PipelineCategory.ADVANCED_EXTRACTION -> category2Factory.resolve(url)
        }
    }
}
