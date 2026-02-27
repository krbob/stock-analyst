package net.bobinski.portfolio.domain.dependency

import net.bobinski.portfolio.domain.usecase.AnalyzeStockUseCase
import net.bobinski.portfolio.domain.usecase.CalculateGain
import net.bobinski.portfolio.domain.usecase.CalculateYield
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
}
