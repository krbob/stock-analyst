package net.bobinski.portfolio

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LoggingFormat
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.bobinski.portfolio.domain.provider.StockDataProvider
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.nio.file.Files
import java.nio.file.Paths

val BackendProviderModule = module {
    single<HttpClient>(createdAtStart = true) {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(get<Json>()) }
            defaultRequest { accept(ContentType.Application.Json) }
            install(HttpCache) {
                val cacheDir = Files.createDirectories(
                    Paths.get(System.getProperty("java.io.tmpdir"), "portfolio-cache")
                ).toFile()
                publicStorage(FileStorage(cacheDir))
            }
            install(Logging) {
                level = LogLevel.INFO
                format = LoggingFormat.OkHttp
            }
        }
    } onClose { client ->
        client?.close()
    }

    single<StockDataProvider> {
        BackendProvider(
            client = get(),
            backendUrl = System.getenv("BACKEND_URL") ?: "http://localhost:7776"
        )
    }
}
