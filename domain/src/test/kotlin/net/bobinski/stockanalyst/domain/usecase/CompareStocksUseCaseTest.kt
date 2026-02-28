package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.Analysis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompareStocksUseCaseTest {

    private val analyzeStockUseCase = mockk<AnalyzeStockUseCase>()
    private val useCase = CompareStocksUseCase(analyzeStockUseCase)

    @Test
    fun `returns analyses for all symbols`() = runTest {
        coEvery { analyzeStockUseCase("AAPL", null) } returns testAnalysis("AAPL")
        coEvery { analyzeStockUseCase("MSFT", null) } returns testAnalysis("MSFT")

        val result = useCase(listOf("AAPL", "MSFT"))

        assertEquals(2, result.size)
        assertEquals("AAPL", result[0].symbol)
        assertEquals("MSFT", result[1].symbol)
    }

    @Test
    fun `throws when any symbol fails`() = runTest {
        coEvery { analyzeStockUseCase("AAPL", null) } returns testAnalysis("AAPL")
        coEvery { analyzeStockUseCase("INVALID", null) } throws
            BackendDataException.unknownSymbol("INVALID")

        assertThrows<BackendDataException> { useCase(listOf("AAPL", "INVALID")) }
    }

    @Test
    fun `passes conversion to underlying use case`() = runTest {
        coEvery { analyzeStockUseCase("AAPL", "eur=x") } returns testAnalysis("AAPL")

        useCase(listOf("AAPL"), "eur=x")

        coVerify { analyzeStockUseCase("AAPL", "eur=x") }
    }

    private fun testAnalysis(symbol: String) = Analysis(
        symbol = symbol,
        name = "Test",
        date = LocalDate(2024, 6, 15),
        lastPrice = 150.0,
        gain = Analysis.Gain(0.01, 0.02, 0.05, 0.1, 0.25),
        rsi = Analysis.Rsi(55.0, 60.0, 65.0),
        macd = Analysis.Macd(1.5, 1.2, 0.3),
        bollingerBands = Analysis.BollingerBands(200.0, 195.0, 190.0),
        movingAverages = Analysis.MovingAverages(193.0, 180.0, 194.0, 182.0),
        atr = 3.5,
        dividendYield = 0.005,
        dividendGrowth = 0.042,
        peRatio = 30.0f,
        pbRatio = 45.0f,
        eps = 6.5f,
        roe = 1.5f,
        marketCap = 3_000_000_000.0,
        recommendation = "buy",
        analystCount = 40,
        fiftyTwoWeekHigh = 210.0f,
        fiftyTwoWeekLow = 150.0f,
        beta = 1.2f,
        sector = "Technology",
        industry = "Consumer Electronics",
        earningsDate = "2024-07-25"
    )
}
