package com.futura.sources.adapters

import com.futura.sources.net.HostRateLimiter
import com.futura.sources.net.SourceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.URLEncoder

/**
 * Shared helpers for source adapters: a rate-limited GET, and URL encoding
 * that is safe inside query strings and path segments.
 */
abstract class BaseSourceAdapter(
    @SourceHttpClient protected val client: HttpClient,
    protected val limiter: HostRateLimiter
) {
    /** Performs a GET against [host], waiting on the host's rate limit first. */
    protected suspend fun get(url: String, host: String, accept: String = "application/json"): String {
        limiter.acquire(host)
        return client.get(url) {
            header(HttpHeaders.Accept, accept)
        }.bodyAsText()
    }

    protected fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Encodes one path segment; spaces become %20, not +. */
    protected fun encPath(value: String): String = enc(value).replace("+", "%20")
}
