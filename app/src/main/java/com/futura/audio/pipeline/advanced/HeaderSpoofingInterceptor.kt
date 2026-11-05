package com.futura.audio.pipeline.advanced

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeaderSpoofingInterceptor @Inject constructor() : Interceptor {
    var defaultUserAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    var defaultReferer: String = "https://www.google.com"

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val modifiedRequest = originalRequest.newBuilder()
            .header("User-Agent", defaultUserAgent)
            .header("Referer", defaultReferer)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .build()
        return chain.proceed(modifiedRequest)
    }
}
