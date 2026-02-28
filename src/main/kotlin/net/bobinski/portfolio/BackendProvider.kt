package net.bobinski.portfolio

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import net.bobinski.portfolio.domain.error.BackendDataException
import net.bobinski.portfolio.domain.model.BasicInfo
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.provider.StockDataProvider
import org.slf4j.LoggerFactory

internal class BackendProvider(
    private val client: HttpClient,
    private val backendUrl: String
) : StockDataProvider {

    private val logger = LoggerFactory.getLogger(BackendProvider::class.java)

    override suspend fun getHistory(
        symbol: String,
        period: StockDataProvider.Period
    ): Collection<HistoricalPrice> {
        val response = client.get("$backendUrl/history/$symbol/${period.value}")
        if (response.status.value >= 500) {
            logger.error("Backend returned {} for history of {} ({})", response.status, symbol, period.value)
            throw BackendDataException.backendError(symbol)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Backend returned {} for history of {} ({})", response.status, symbol, period.value)
            return emptyList()
        }
        return try {
            response.body()
        } catch (e: Exception) {
            logger.error("Failed to deserialize history for {} ({})", symbol, period.value, e)
            throw BackendDataException.backendError(symbol)
        }
    }

    override suspend fun getInfo(symbol: String): BasicInfo? {
        val response = client.get("$backendUrl/info/$symbol")
        if (response.status.value >= 500) {
            logger.error("Backend returned {} for info of {}", response.status, symbol)
            throw BackendDataException.backendError(symbol)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Backend returned {} for info of {}", response.status, symbol)
            return null
        }
        return try {
            response.body()
        } catch (e: Exception) {
            logger.error("Failed to deserialize info for {}", symbol, e)
            throw BackendDataException.backendError(symbol)
        }
    }
}
