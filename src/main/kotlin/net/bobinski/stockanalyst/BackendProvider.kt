package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.SearchResult
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

internal class BackendProvider(
    private val client: HttpClient,
    private val backendUrl: String
) : StockDataProvider, AutoCloseable {

    private val logger = LoggerFactory.getLogger(BackendProvider::class.java)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesce(key: String, block: suspend () -> T): T {
        var created: Deferred<Any?>? = null
        val deferred = inFlight.computeIfAbsent(key) {
            workScope.async { block() }.also { created = it }
        }
        if (created === deferred) {
            deferred.invokeOnCompletion {
                inFlight.remove(key, deferred)
            }
        }
        return deferred.await() as T
    }

    override suspend fun getHistory(
        symbol: String,
        period: StockDataProvider.Period,
        interval: StockDataProvider.Interval
    ): Collection<HistoricalPrice> = coalesce("history:$symbol:${period.value}:${interval.value}") {
        val encodedSymbol = symbol.encodeURLPath()
        val response = try {
            client.get("$backendUrl/history/$encodedSymbol/${period.value}") {
                url.parameters.append("interval", interval.value)
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch history for {} ({})", symbol, period.value, e)
            throw BackendDataException.backendError(symbol)
        }
        if (response.status.value >= 500) {
            logger.error("Backend returned {} for history of {} ({})", response.status, symbol, period.value)
            throw BackendDataException.backendError(symbol)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Backend returned {} for history of {} ({})", response.status, symbol, period.value)
            return@coalesce emptyList()
        }
        try {
            response.body()
        } catch (e: Exception) {
            logger.error("Failed to deserialize history for {} ({})", symbol, period.value, e)
            throw BackendDataException.backendError(symbol)
        }
    }

    override suspend fun search(query: String): List<SearchResult> = coalesce("search:$query") {
        val response = try {
            client.get("$backendUrl/search/${query.encodeURLPath()}")
        } catch (e: Exception) {
            logger.error("Failed to fetch search results for {}", query, e)
            throw BackendDataException.backendError(query)
        }
        if (response.status.value >= 500) {
            logger.error("Backend returned {} for search query: {}", response.status, query)
            throw BackendDataException.backendError(query)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Backend returned {} for search query: {}", response.status, query)
            return@coalesce emptyList()
        }
        try {
            response.body()
        } catch (e: Exception) {
            logger.error("Failed to deserialize search results for {}", query, e)
            throw BackendDataException.backendError(query)
        }
    }

    override fun resolveConversionSymbol(from: String, to: String): String =
        if (from.equals("USD", ignoreCase = true)) "${to.uppercase()}=X"
        else "${from.uppercase()}${to.uppercase()}=X"

    override suspend fun getInfo(symbol: String): BasicInfo? = coalesce("info:$symbol") {
        val encodedSymbol = symbol.encodeURLPath()
        val response = try {
            client.get("$backendUrl/info/$encodedSymbol")
        } catch (e: Exception) {
            logger.error("Failed to fetch info for {}", symbol, e)
            throw BackendDataException.backendError(symbol)
        }
        if (response.status.value >= 500) {
            logger.error("Backend returned {} for info of {}", response.status, symbol)
            throw BackendDataException.backendError(symbol)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Backend returned {} for info of {}", response.status, symbol)
            return@coalesce null
        }
        try {
            response.body()
        } catch (e: Exception) {
            logger.error("Failed to deserialize info for {}", symbol, e)
            throw BackendDataException.backendError(symbol)
        }
    }

    override fun close() {
        workScope.cancel()
    }
}
