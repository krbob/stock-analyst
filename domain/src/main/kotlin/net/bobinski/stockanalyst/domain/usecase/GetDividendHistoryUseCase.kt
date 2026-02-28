package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.DividendHistory
import net.bobinski.stockanalyst.domain.model.DividendPayment
import net.bobinski.stockanalyst.domain.provider.StockDataProvider

class GetDividendHistoryUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {

    suspend operator fun invoke(symbol: String): DividendHistory = coroutineScope {
        val infoDeferred = async { stockDataProvider.getInfo(symbol) }
        val dividendsDeferred = async { stockDataProvider.getDividends(symbol) }

        val info = infoDeferred.await()
        val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

        val payments = dividendsDeferred.await()

        val oneYearAgo = currentTimeProvider.localDate().minus(1, DateTimeUnit.YEAR)
        val recentPayments = payments.filter { it.date >= oneYearAgo }
        val price = info.price ?: 0.0

        val currentYield = if (price > 0 && recentPayments.isNotEmpty()) {
            recentPayments.sumOf { it.amount } / price
        } else 0.0

        val growth = calculateGrowth(payments)
        val frequency = estimateFrequency(payments)

        DividendHistory(
            symbol = symbol,
            name = name,
            payments = payments,
            summary = DividendHistory.Summary(
                currentYield = currentYield,
                growth = growth,
                frequency = frequency
            )
        ).roundValues()
    }

    private fun calculateGrowth(payments: List<DividendPayment>): Double? {
        val currentYear = currentTimeProvider.localDate().year
        val thisYear = payments.filter { it.date.year == currentYear - 1 }
        val prevYear = payments.filter { it.date.year == currentYear - 2 }
        if (thisYear.isEmpty() || prevYear.isEmpty()) return null
        val thisTotal = thisYear.sumOf { it.amount }
        val prevTotal = prevYear.sumOf { it.amount }
        if (prevTotal == 0.0) return null
        return (thisTotal - prevTotal) / prevTotal
    }

    private fun estimateFrequency(payments: List<DividendPayment>): Int {
        val oneYearAgo = currentTimeProvider.localDate().minus(1, DateTimeUnit.YEAR)
        return payments.count { it.date >= oneYearAgo }
    }
}
