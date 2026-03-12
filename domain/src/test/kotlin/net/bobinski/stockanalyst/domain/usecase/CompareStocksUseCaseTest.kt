package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.Quote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CompareStocksUseCaseTest {

    private val getQuoteUseCase = mockk<GetQuoteUseCase>()
    private val useCase = CompareStocksUseCase(getQuoteUseCase)

    @Test
    fun `returns results for all symbols`() = runTest {
        coEvery { getQuoteUseCase("AAPL", null) } returns testQuote("AAPL")
        coEvery { getQuoteUseCase("MSFT", null) } returns testQuote("MSFT")

        val result = useCase(listOf("AAPL", "MSFT"))

        assertEquals(2, result.size)
        assertEquals("AAPL", result[0].symbol)
        assertNotNull(result[0].data)
        assertNull(result[0].error)
        assertEquals("MSFT", result[1].symbol)
        assertNotNull(result[1].data)
    }

    @Test
    fun `returns partial results when one symbol fails`() = runTest {
        coEvery { getQuoteUseCase("AAPL", null) } returns testQuote("AAPL")
        coEvery { getQuoteUseCase("INVALID", null) } throws
            BackendDataException.unknownSymbol("INVALID")

        val result = useCase(listOf("AAPL", "INVALID"))

        assertEquals(2, result.size)
        assertNotNull(result[0].data)
        assertNull(result[0].error)
        assertNull(result[1].data)
        assertNotNull(result[1].error)
    }

    @Test
    fun `passes currency to underlying use case`() = runTest {
        coEvery { getQuoteUseCase("AAPL", "EUR") } returns testQuote("AAPL")

        useCase(listOf("AAPL"), "EUR")

        coVerify { getQuoteUseCase("AAPL", "EUR") }
    }

    private fun testQuote(symbol: String) = Quote(
        symbol = symbol,
        name = "Test",
        date = LocalDate(2024, 6, 15),
        lastPrice = 150.0,
        gain = Quote.Gain(0.01, 0.02, 0.05, 0.1, 0.15, 0.12, 0.25, 0.8),
        peRatio = 30.0,
        pbRatio = 45.0,
        eps = 6.5,
        roe = 1.5,
        marketCap = 3_000_000_000.0,
        beta = 1.2,
        dividendYield = 0.005,
        dividendGrowth = 0.042,
        fiftyTwoWeekHigh = 210.0,
        fiftyTwoWeekLow = 150.0,
        sector = "Technology",
        industry = "Consumer Electronics",
        earningsDate = LocalDate(2024, 7, 25),
        recommendation = "buy",
        analystCount = 40
    )
}
