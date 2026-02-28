package net.bobinski.stockanalyst.domain.dependency

import net.bobinski.stockanalyst.domain.usecase.AnalyzeStockUseCase
import net.bobinski.stockanalyst.domain.usecase.CalculateGain
import net.bobinski.stockanalyst.domain.usecase.CalculateYield
import net.bobinski.stockanalyst.domain.usecase.CompareStocksUseCase
import net.bobinski.stockanalyst.domain.usecase.GetDividendHistoryUseCase
import net.bobinski.stockanalyst.domain.usecase.GetPriceUseCase
import org.koin.dsl.module

val DomainModule = module {
    single { CalculateGain(currentTimeProvider = get()) }
    single { CalculateYield(currentTimeProvider = get()) }
    single {
        AnalyzeStockUseCase(
            stockDataProvider = get(),
            currentTimeProvider = get(),
            calculateGain = get(),
            calculateYield = get()
        )
    }
    single {
        GetPriceUseCase(
            stockDataProvider = get(),
            currentTimeProvider = get(),
            calculateGain = get()
        )
    }
    single { CompareStocksUseCase(analyzeStockUseCase = get()) }
    single {
        GetDividendHistoryUseCase(
            stockDataProvider = get(),
            currentTimeProvider = get()
        )
    }
}
