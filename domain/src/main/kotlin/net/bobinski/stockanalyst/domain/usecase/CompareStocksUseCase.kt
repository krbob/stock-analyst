package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.error.BackendDataException.Reason
import net.bobinski.stockanalyst.domain.model.CompareResult

class CompareStocksUseCase(
    private val getQuoteUseCase: GetQuoteUseCase
) {

    suspend operator fun invoke(
        symbols: List<String>,
        currency: String? = null
    ): List<CompareResult> = coroutineScope {
        symbols.map { symbol ->
            async {
                try {
                    CompareResult(symbol = symbol, data = getQuoteUseCase(symbol, currency))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackendDataException) {
                    if (e.reason == Reason.RATE_LIMITED) throw e
                    CompareResult(symbol = symbol, error = e.message ?: "Unknown error")
                } catch (e: Exception) {
                    CompareResult(symbol = symbol, error = e.message ?: "Unknown error")
                }
            }
        }.awaitAll()
    }
}
