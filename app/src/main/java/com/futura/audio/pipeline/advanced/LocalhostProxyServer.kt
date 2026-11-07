package com.futura.audio.pipeline.advanced

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class LocalhostProxyServer @Inject constructor(
    private val headerSpoofingInterceptor: HeaderSpoofingInterceptor
) {
    private var server: EmbeddedServer<*, *>? = null
    private val proxyPort = 8888
    private val startMutex = Mutex()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                addInterceptor(headerSpoofingInterceptor)
                followRedirects(true)
            }
        }
    }

    /**
     * Starts the loopback server and returns only once the socket is bound.
     * Safe to call repeatedly and from several coroutines; the first call wins.
     */
    suspend fun start() {
        startMutex.withLock {
            val running = server
            if (running != null) {
                // Already up: await the connector so a caller arriving while the
                // first bind is in flight does not race the socket.
                running.engine.resolvedConnectors()
                return@withLock
            }
            val started = embeddedServer(CIO, port = proxyPort, host = "127.0.0.1") {
                routing {
                    get("/proxy") {
                        // Ktor already percent-decodes query values. Decoding again
                        // corrupts signed links (%2B -> '+', %2F -> '/'), which made
                        // proxied streams fail upstream. Use the value as-is.
                        val targetUrl = call.request.queryParameters["url"]
                        if (targetUrl.isNullOrBlank()) {
                            call.respondText("Missing 'url' parameter", status = HttpStatusCode.BadRequest)
                            return@get
                        }
                        val clientRange = call.request.headers[HttpHeaders.Range]

                        try {
                            val upstreamResponse = httpClient.get(targetUrl) {
                                clientRange?.let { header(HttpHeaders.Range, it) }
                            }
                            // Forward the range headers so seeking works; without
                            // Content-Range a 206 looks like a whole body.
                            upstreamResponse.headers[HttpHeaders.ContentRange]?.let {
                                call.response.header(HttpHeaders.ContentRange, it)
                            }
                            upstreamResponse.headers[HttpHeaders.AcceptRanges]?.let {
                                call.response.header(HttpHeaders.AcceptRanges, it)
                            }
                            call.respondBytesWriter(
                                status = upstreamResponse.status,
                                contentType = upstreamResponse.contentType() ?: ContentType.Application.OctetStream,
                                contentLength = upstreamResponse.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                            ) {
                                upstreamResponse.bodyAsChannel().copyTo(this)
                            }
                        } catch (e: Exception) {
                            call.respondText(
                                "Proxy error: ${e.localizedMessage}",
                                status = HttpStatusCode.InternalServerError
                            )
                        }
                    }
                }
            }.start(wait = false)
            server = started
            // The bind happens off-thread; await the connector so callers can play
            // immediately without racing the socket into existence.
            started.engine.resolvedConnectors()
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    /** True when [url] points at this proxy, so the server must be running to serve it. */
    fun isProxyUrl(url: String): Boolean =
        url.startsWith("http://127.0.0.1:$proxyPort/") || url.startsWith("http://localhost:$proxyPort/")

    fun getProxyUrl(originalUrl: String): String {
        // Encode once, without '+', matching how Ktor decodes the value on the way in.
        val encodedUrl = originalUrl.encodeURLParameter()
        return "http://127.0.0.1:$proxyPort/proxy?url=$encodedUrl"
    }
}
