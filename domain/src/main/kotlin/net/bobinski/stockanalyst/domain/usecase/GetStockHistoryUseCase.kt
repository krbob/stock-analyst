package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.StockHistory
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period

class GetStockHistoryUseCase(
    private val stockDataProvider: StockDataProvider
) {

    suspend operator fun invoke(symbol: String, period: Period): StockHistory =
        coroutineScope {
            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, period) }

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            val history = historyDeferred.await()
            if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

            StockHistory(
                symbol = symbol,
                name = name,
                period = period.value,
                prices = history.sortedBy { it.date }
            )
        }
}
