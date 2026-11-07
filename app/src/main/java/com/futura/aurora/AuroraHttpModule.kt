package com.futura.aurora

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

@Module
@InstallIn(SingletonComponent::class)
object AuroraHttpModule {

    /**
     * Ktor client for Aurora's source APIs. Requests carry a descriptive
     * User-Agent and no personal details.
     */
    @Provides
    @Singleton
    fun provideAuroraHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            // Null means no overall limit, so large downloads can finish.
            // Stalled connections still fail on the socket timeout.
            requestTimeoutMillis = null
            socketTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            headers.append(HttpHeaders.UserAgent, "Aurora/1.0 (Futura Audio)")
        }
    }
}
