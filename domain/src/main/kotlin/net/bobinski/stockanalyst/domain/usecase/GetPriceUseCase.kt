package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.Price
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.model.latestPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period

class GetPriceUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider,
    private val calculateGain: CalculateGain
) {

    suspend operator fun invoke(symbol: String, conversion: String? = null): Price =
        coroutineScope {
            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, Period._1y) }
            val convInfoDeferred = conversion?.let { async { stockDataProvider.getInfo(it) } }

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            val history = historyDeferred.await()
            if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

            val conversionInfo = convInfoDeferred?.await()
            val conversionHistory = conversion?.let {
                stockDataProvider.getHistory(it, Period._1y).also { convHistory ->
                    val stockMinDate = history.minOf { p -> p.date }
                    val convMinDate = convHistory.minOfOrNull { p -> p.date }
                    if (convMinDate == null || convMinDate > stockMinDate) {
                        throw BackendDataException.insufficientConversion(it)
                    }
                }
            }

            val lacking = history.size <= 1

            Price(
                symbol = symbol,
                name = name,
                conversionName = conversionInfo?.name,
                date = currentTimeProvider.localDate(),
                lastPrice = (info.price ?: CalculateLastPrice(history, null))
                    .applyConversion(conversionInfo?.price),
                gain = Price.Gain(
                    daily = Double.NaN,
                    weekly = Double.NaN,
                    monthly = Double.NaN,
                    quarterly = Double.NaN,
                    yearly = Double.NaN
                ).takeIf { lacking } ?: Price.Gain(
                    daily = calculateGain.daily(history, conversionHistory),
                    weekly = calculateGain.weekly(history, conversionHistory),
                    monthly = calculateGain.monthly(history, conversionHistory),
                    quarterly = calculateGain.quarterly(history, conversionHistory),
                    yearly = calculateGain.yearly(history, conversionHistory)
                )
            ).roundValues()
        }
}
