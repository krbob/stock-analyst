package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetPriceUseCaseTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val stockDataProvider = mockk<StockDataProvider>()
    private val calculateGain = CalculateGain(timeProvider)
    private val useCase = GetPriceUseCase(
        stockDataProvider = stockDataProvider,
        currentTimeProvider = timeProvider,
        calculateGain = calculateGain
    )

    @Test
    fun `returns price for valid symbol`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(200)

        val result = useCase("AAPL")

        assertEquals("AAPL", result.symbol)
        assertEquals("Apple Inc.", result.name)
        assertEquals("USD", result.currency)
        assertEquals(LocalDate(2024, 6, 15), result.date)
        assertTrue(result.lastPrice > 0)
    }

    @Test
    fun `throws BackendDataException for unknown symbol`() = runTest {
        coEvery { stockDataProvider.getInfo("INVALID") } returns BasicInfo(
            name = null, price = null, peRatio = null, pbRatio = null, eps = null, roe = null,
            marketCap = null, recommendation = null, analystCount = null,
            fiftyTwoWeekHigh = null, fiftyTwoWeekLow = null, beta = null, sector = null,
            industry = null, earningsDate = null, dividendRate = null,
            trailingAnnualDividendRate = null
        )
        coEvery { stockDataProvider.getHistory("INVALID", Period._1y) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("INVALID") }

        assertTrue(exception.message!!.contains("Unknown symbol"))
    }

    @Test
    fun `throws BackendDataException when history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("EMPTY") } returns basicInfo("Empty Stock")
        coEvery { stockDataProvider.getHistory("EMPTY", Period._1y) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("EMPTY") }

        assertTrue(exception.message!!.contains("Missing history"))
    }

    @Test
    fun `sets null gains when data is lacking`() = runTest {
        coEvery { stockDataProvider.getInfo("LACK") } returns basicInfo("Lacking Stock")
        coEvery { stockDataProvider.getHistory("LACK", Period._1y) } returns listOf(singlePrice())

        val result = useCase("LACK")

        assertNull(result.gain.daily)
        assertNull(result.gain.weekly)
        assertNull(result.gain.monthly)
        assertNull(result.gain.quarterly)
        assertNull(result.gain.halfYearly)
        assertNull(result.gain.ytd)
        assertNull(result.gain.yearly)
        assertNull(result.gain.fiveYear)
    }

    @Test
    fun `converts to target currency`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        every { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getInfo("EUR=X") } returns basicInfo("EUR/USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(200)
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns priceHistory(200)

        val result = useCase("AAPL", "EUR")

        assertEquals("EUR", result.currency)
    }

    @Test
    fun `trims history to conversion overlap when conversion starts later`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } returns basicInfo("USD/PLN")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(200)
        coEvery { stockDataProvider.getHistory("PLN=X", Period._1y) } returns priceHistory(190)

        val result = useCase("AAPL", "PLN")

        assertEquals("AAPL", result.symbol)
        assertEquals("PLN", result.currency)
        assertTrue(result.lastPrice > 0)
    }

    @Test
    fun `skips conversion when target matches native currency`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(200)

        val result = useCase("AAPL", "USD")

        assertEquals("USD", result.currency)
        assertEquals("AAPL", result.symbol)
    }

    private fun basicInfo(name: String) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0f, pbRatio = 10.0f, eps = 5.0f, roe = 0.3f,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0f, fiftyTwoWeekLow = 120.0f, beta = 1.2f,
        sector = "Technology", industry = "Consumer Electronics", earningsDate = "2024-07-25",
        dividendRate = 1.0f, trailingAnnualDividendRate = 0.96f, currency = "USD"
    )

    private fun priceHistory(days: Int): List<HistoricalPrice> = (0 until days).map { i ->
        val date = LocalDate(2024, 6, 15).minus(i, DateTimeUnit.DAY)
        HistoricalPrice(
            date = date,
            open = 100.0 + i * 0.1,
            close = 100.0 + i * 0.1,
            low = 99.0 + i * 0.1,
            high = 101.0 + i * 0.1,
            volume = 1000L,
            dividend = 0.0
        )
    }

    private fun singlePrice() = HistoricalPrice(
        date = LocalDate(2024, 6, 15),
        open = 100.0, close = 100.0, low = 99.0, high = 101.0,
        volume = 1000L, dividend = 0.0
    )
}
