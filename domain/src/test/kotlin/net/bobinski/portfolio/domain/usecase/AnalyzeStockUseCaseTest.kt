package net.bobinski.portfolio.domain.usecase

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import net.bobinski.portfolio.core.time.MutableCurrentTimeProvider
import net.bobinski.portfolio.domain.error.BackendDataException
import net.bobinski.portfolio.domain.model.BasicInfo
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.provider.StockDataProvider
import net.bobinski.portfolio.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnalyzeStockUseCaseTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val stockDataProvider = mockk<StockDataProvider>()
    private val calculateGain = CalculateGain(timeProvider)
    private val calculateYield = CalculateYield(timeProvider)
    private val useCase = AnalyzeStockUseCase(
        stockDataProvider = stockDataProvider,
        currentTimeProvider = timeProvider,
        calculateGain = calculateGain,
        calculateYield = calculateYield
    )

    @Test
    fun `returns analysis for valid symbol`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y) } returns priceHistory(500)

        val result = useCase("AAPL")

        assertEquals("AAPL", result.symbol)
        assertEquals("Apple Inc.", result.name)
        assertEquals(LocalDate(2024, 6, 15), result.date)
        assertTrue(result.lastPrice > 0)
    }

    @Test
    fun `throws BackendDataException for unknown symbol`() = runTest {
        coEvery { stockDataProvider.getInfo("INVALID") } returns BasicInfo(
            name = null, peRatio = null, pbRatio = null, eps = null, roe = null, marketCap = null
        )
        coEvery { stockDataProvider.getHistory("INVALID", any()) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("INVALID") }

        assertTrue(exception.message!!.contains("Unknown symbol"))
        assertEquals(BackendDataException.Reason.NOT_FOUND, exception.reason)
    }

    @Test
    fun `throws BackendDataException when history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("EMPTY") } returns basicInfo("Empty Stock")
        coEvery { stockDataProvider.getHistory("EMPTY", any()) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("EMPTY") }

        assertTrue(exception.message!!.contains("Missing history"))
        assertEquals(BackendDataException.Reason.NOT_FOUND, exception.reason)
    }

    @Test
    fun `falls back to shorter periods when data is insufficient`() = runTest {
        coEvery { stockDataProvider.getInfo("LOW") } returns basicInfo("Low Data Stock")
        coEvery { stockDataProvider.getHistory("LOW", Period._5y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LOW", Period._2y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LOW", Period._1y) } returns priceHistory(400)

        val result = useCase("LOW")

        assertEquals("LOW", result.symbol)
        assertTrue(result.lastPrice > 0)
    }

    @Test
    fun `sets NaN values when data is lacking`() = runTest {
        coEvery { stockDataProvider.getInfo("LACK") } returns basicInfo("Lacking Stock")
        coEvery { stockDataProvider.getHistory("LACK", Period._5y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LACK", Period._2y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LACK", Period._1y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LACK", Period._1d) } returns listOf(singlePrice())

        val result = useCase("LACK")

        assertTrue(result.gain.daily.isNaN())
        assertTrue(result.gain.weekly.isNaN())
        assertTrue(result.rsi.daily.isNaN())
        assertTrue(result.dividendYield.isNaN())
    }

    @Test
    fun `includes conversion name as separate field`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getInfo("eur=x") } returns basicInfo("EUR/USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("eur=x", Period._5y) } returns priceHistory(500)

        val result = useCase("AAPL", "eur=x")

        assertEquals("Apple Inc.", result.name)
        assertEquals("EUR/USD", result.conversionName)
    }

    @Test
    fun `converts eps and marketCap when conversion is provided`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns BasicInfo(
            name = "Apple Inc.", peRatio = 25.0f, pbRatio = 10.0f, eps = 6.0f, roe = 0.3f,
            marketCap = 1_000_000.0
        )
        coEvery { stockDataProvider.getInfo("eur=x") } returns basicInfo("EUR/USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("eur=x", Period._5y) } returns priceHistory(500)

        val result = useCase("AAPL", "eur=x")

        assertTrue(result.eps!! != 6.0f, "EPS should be converted")
        assertTrue(result.marketCap!! != 1_000_000.0, "Market cap should be converted")
        assertEquals(25.0f, result.peRatio, "PE ratio should not be converted")
    }

    private fun basicInfo(name: String) = BasicInfo(
        name = name, peRatio = 25.0f, pbRatio = 10.0f, eps = 5.0f, roe = 0.3f,
        marketCap = 1_000_000.0
    )

    private fun priceHistory(days: Int): List<HistoricalPrice> = (0 until days).map { i ->
        val date = LocalDate(2024, 6, 15).let { d ->
            val jd = java.time.LocalDate.of(d.year, d.month.number, d.day).minusDays(i.toLong())
            LocalDate(jd.year, jd.monthValue, jd.dayOfMonth)
        }
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
