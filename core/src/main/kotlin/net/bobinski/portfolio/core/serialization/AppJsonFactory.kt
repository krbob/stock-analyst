package net.bobinski.portfolio.core.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

internal object AppJsonFactory {
    fun create(configure: JsonBuilder.() -> Unit = {}): Json = Json {
        ignoreUnknownKeys = true
        configure()
    }
}
