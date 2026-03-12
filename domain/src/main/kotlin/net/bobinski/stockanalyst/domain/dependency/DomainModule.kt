package net.bobinski.stockanalyst.domain.dependency

import net.bobinski.stockanalyst.domain.usecase.CalculateGain
import net.bobinski.stockanalyst.domain.usecase.CalculateYield
import net.bobinski.stockanalyst.domain.usecase.CompareStocksUseCase
import net.bobinski.stockanalyst.domain.usecase.GetQuoteUseCase
import net.bobinski.stockanalyst.domain.usecase.GetStockHistoryUseCase
import net.bobinski.stockanalyst.domain.usecase.SearchTickerUseCase
import org.koin.dsl.module

val DomainModule = module {
    single { CalculateGain(currentTimeProvider = get()) }
    single { CalculateYield(currentTimeProvider = get()) }
    single {
        GetQuoteUseCase(
            stockDataProvider = get(),
            currentTimeProvider = get(),
            calculateGain = get(),
            calculateYield = get()
        )
    }
    single { CompareStocksUseCase(getQuoteUseCase = get()) }
    single { GetStockHistoryUseCase(stockDataProvider = get(), currentTimeProvider = get()) }
    single { SearchTickerUseCase(stockDataProvider = get()) }
}
