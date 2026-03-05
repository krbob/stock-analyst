package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.model.SearchResult
import net.bobinski.stockanalyst.domain.provider.StockDataProvider

class SearchTickerUseCase(
    private val stockDataProvider: StockDataProvider
) {
    suspend operator fun invoke(query: String): List<SearchResult> =
        stockDataProvider.search(query)
}
