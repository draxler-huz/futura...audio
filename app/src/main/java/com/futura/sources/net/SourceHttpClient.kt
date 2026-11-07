package com.futura.sources.net

import javax.inject.Qualifier

/**
 * Marks the HTTP client used by the media-source layer. The app already has
 * an unqualified [io.ktor.client.HttpClient] binding for Aurora, so this
 * qualifier keeps the two apart.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SourceHttpClient
