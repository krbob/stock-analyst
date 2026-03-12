package net.bobinski.stockanalyst.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.SearchResult
import net.bobinski.stockanalyst.domain.usecase.SearchTickerUseCase
import org.koin.ktor.ext.inject
import java.util.concurrent.ConcurrentHashMap

private const val CACHE_TTL_MS = 5 * 60 * 1000L
private const val CACHE_MAX_SIZE = 1000

private data class CachedSearch(val results: List<SearchResult>, val timestamp: Long)

private val searchCache = ConcurrentHashMap<String, CachedSearch>()

private fun evictExpiredEntries() {
    val now = System.currentTimeMillis()
    searchCache.entries.removeIf { now - it.value.timestamp >= CACHE_TTL_MS }
}

fun Route.searchRoute() {
    val searchTickerUseCase: SearchTickerUseCase by inject()

    get("/search/{query}") {
        val query = call.parameters["query"]
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing query parameter.")

        if (query.length > 50) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Query too long.")
        }

        val key = query.lowercase()
        val now = System.currentTimeMillis()
        val cached = searchCache[key]
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            return@get call.respond(cached.results)
        }

        val results = try {
            searchTickerUseCase(query)
        } catch (e: BackendDataException) {
            return@get call.respondError(
                e.toHttpStatusCode(),
                e.message ?: "Error."
            )
        } catch (e: Exception) {
            return@get call.respondError(
                HttpStatusCode.InternalServerError,
                e.message ?: "Unexpected error occurred."
            )
        }

        if (searchCache.size >= CACHE_MAX_SIZE) {
            evictExpiredEntries()
            if (searchCache.size >= CACHE_MAX_SIZE) {
                searchCache.clear()
            }
        }

        searchCache[key] = CachedSearch(results, now)
        call.respond(results)
    }
}
