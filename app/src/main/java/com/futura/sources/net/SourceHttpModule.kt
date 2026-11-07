package com.futura.sources.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import javax.inject.Singleton

/**
 * The shared HTTP client for every media source. Requests carry a
 * descriptive User-Agent and no personal details, and the socket timeout
 * stops a stalled provider from hanging a search.
 */
@Module
@InstallIn(SingletonComponent::class)
object SourceHttpModule {

    @Provides
    @Singleton
    @SourceHttpClient
    fun provideSourceHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = null
            socketTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            headers.append(HttpHeaders.UserAgent, USER_AGENT)
        }
    }

    const val USER_AGENT = "MediaAggregator/1.0 (contact@example.com)"
}
