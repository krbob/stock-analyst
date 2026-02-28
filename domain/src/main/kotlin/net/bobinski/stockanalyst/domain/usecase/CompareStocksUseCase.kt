package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.bobinski.stockanalyst.domain.model.Analysis

class CompareStocksUseCase(
    private val analyzeStockUseCase: AnalyzeStockUseCase
) {

    suspend operator fun invoke(
        symbols: List<String>,
        conversion: String? = null
    ): List<Analysis> = coroutineScope {
        symbols.map { symbol ->
            async { analyzeStockUseCase(symbol, conversion) }
        }.map { it.await() }
    }
}
