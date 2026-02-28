package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.DividendPayment
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

internal class BackendProvider(
    private val client: HttpClient,
    private val backendUrl: String
) : StockDataProvider {

    private val logger = LoggerFactory.getLogger(BackendProvider::class.java)
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesce(key: String, block: suspend () -> T): T {
        val deferred = CompletableDeferred<Any?>()
        val existing = inFlight.putIfAbsent(key, deferred)
        if (existing != null) {
            return existing.await() as T
        }
        return try {
            val result = block()
            deferred.complete(result)
            result
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    override suspend fun getHistory(
        symbol: String,
        period: StockDataProvider.Period
    ): Collection<HistoricalPrice> = coalesce("history:$symbol:${period.value}") {
        val response = client.get("$backendUrl/history/$symbol/${period.value}")
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

    override suspend fun getDividends(symbol: String): List<DividendPayment> = coalesce("dividends:$symbol") {
        val response = client.get("$backendUrl/dividends/$symbol")
        if (response.status.value >= 500) {
            logger.error("Backend returned {} for dividends of {}", response.status, symbol)
            throw BackendDataException.backendError(symbol)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Backend returned {} for dividends of {}", response.status, symbol)
            return@coalesce emptyList()
        }
        try {
            response.body()
        } catch (e: Exception) {
            logger.error("Failed to deserialize dividends for {}", symbol, e)
            throw BackendDataException.backendError(symbol)
        }
    }

    override suspend fun getInfo(symbol: String): BasicInfo? = coalesce("info:$symbol") {
        val response = client.get("$backendUrl/info/$symbol")
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
}
