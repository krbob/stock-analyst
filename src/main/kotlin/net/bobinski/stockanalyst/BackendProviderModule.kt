package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
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
                configureBackendTimeouts()
            }
            install(HttpRequestRetry) {
                configureBackendTransportRetries()
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

internal fun HttpRequestRetryConfig.configureBackendTransportRetries() {
    maxRetries = BackendHttpBudget.MAX_TRANSPORT_RETRIES
    retryIf { _, _ -> false }
    retryOnExceptionIf { _, cause -> cause is IOException }
    constantDelay(BackendHttpBudget.RETRY_DELAY_MILLIS, 0, false)
}

internal fun HttpTimeoutConfig.configureBackendTimeouts() {
    requestTimeoutMillis = BackendHttpBudget.REQUEST_TIMEOUT_MILLIS
    connectTimeoutMillis = BackendHttpBudget.CONNECT_TIMEOUT_MILLIS
    socketTimeoutMillis = BackendHttpBudget.SOCKET_TIMEOUT_MILLIS
}

internal object BackendHttpBudget {
    const val REQUEST_TIMEOUT_MILLIS = 6_000L
    const val CONNECT_TIMEOUT_MILLIS = 2_000L
    const val SOCKET_TIMEOUT_MILLIS = 6_000L
    const val MAX_TRANSPORT_RETRIES = 2
    const val RETRY_DELAY_MILLIS = 250L
    const val MAX_TOTAL_ELAPSED_MILLIS =
        REQUEST_TIMEOUT_MILLIS * (MAX_TRANSPORT_RETRIES + 1) +
            RETRY_DELAY_MILLIS * MAX_TRANSPORT_RETRIES
}
