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
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalhostProxyServer @Inject constructor(
    private val headerSpoofingInterceptor: HeaderSpoofingInterceptor
) {
    private var server: EmbeddedServer<*, *>? = null
    private val proxyPort = 8888

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                addInterceptor(headerSpoofingInterceptor)
                followRedirects(true)
            }
        }
    }

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = proxyPort, host = "127.0.0.1") {
            routing {
                get("/proxy") {
                    val targetUrlParam = call.request.queryParameters["url"]
                    if (targetUrlParam.isNullOrBlank()) {
                        call.respondText("Missing 'url' parameter", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    val targetUrl = URLDecoder.decode(targetUrlParam, "UTF-8")
                    val clientRange = call.request.headers[HttpHeaders.Range]

                    try {
                        val upstreamResponse = httpClient.get(targetUrl) {
                            clientRange?.let { header(HttpHeaders.Range, it) }
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
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    fun getProxyUrl(originalUrl: String): String {
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://127.0.0.1:$proxyPort/proxy?url=$encodedUrl"
    }
}
