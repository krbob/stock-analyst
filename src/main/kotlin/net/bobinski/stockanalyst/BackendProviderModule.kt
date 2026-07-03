package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LoggingFormat
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.IOException

val BackendProviderModule = module {
    single<HttpClient>(createdAtStart = true) {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(get<Json>()) }
            defaultRequest { accept(ContentType.Application.Json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
            install(HttpRequestRetry) {
                maxRetries = 2
                retryIf { _, response ->
                    response.status.value == 429 || response.status.value >= 500
                }
                retryOnExceptionIf { _, cause ->
                    cause is IOException
                }
                exponentialDelay()
            }
            install(Logging) {
                level = LogLevel.INFO
                format = LoggingFormat.OkHttp
            }
        }
    } onClose { client ->
        client?.close()
    }

    single<BackendProvider> {
        BackendProvider(
            client = get(),
            backendUrl = System.getenv("BACKEND_URL") ?: "http://localhost:8081"
        )
    } onClose { provider ->
        provider?.close()
    }

    single<StockDataProvider> {
        get<BackendProvider>()
    }
}
