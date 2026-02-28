package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `sets NaN gains when data is lacking`() = runTest {
        coEvery { stockDataProvider.getInfo("LACK") } returns basicInfo("Lacking Stock")
        coEvery { stockDataProvider.getHistory("LACK", Period._1y) } returns listOf(singlePrice())

        val result = useCase("LACK")

        assertTrue(result.gain.daily.isNaN())
        assertTrue(result.gain.weekly.isNaN())
        assertTrue(result.gain.monthly.isNaN())
        assertTrue(result.gain.quarterly.isNaN())
        assertTrue(result.gain.yearly.isNaN())
    }

    @Test
    fun `supports conversion parameter`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getInfo("eur=x") } returns basicInfo("EUR/USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(200)
        coEvery { stockDataProvider.getHistory("eur=x", Period._1y) } returns priceHistory(200)

        val result = useCase("AAPL", "eur=x")

        assertEquals("EUR/USD", result.conversionName)
    }

    @Test
    fun `throws when conversion symbol info has no name`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getInfo("invalid=x") } returns BasicInfo(
            name = null, price = null, peRatio = null, pbRatio = null, eps = null, roe = null,
            marketCap = null, recommendation = null, analystCount = null,
            fiftyTwoWeekHigh = null, fiftyTwoWeekLow = null, beta = null, sector = null,
            industry = null, earningsDate = null, dividendRate = null,
            trailingAnnualDividendRate = null
        )
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(200)
        coEvery { stockDataProvider.getHistory("invalid=x", Period._1y) } returns priceHistory(200)

        val result = useCase("AAPL", "invalid=x")

        assertEquals("AAPL", result.symbol)
        assertTrue(result.conversionName == null)
    }

    private fun basicInfo(name: String) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0f, pbRatio = 10.0f, eps = 5.0f, roe = 0.3f,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0f, fiftyTwoWeekLow = 120.0f, beta = 1.2f,
        sector = "Technology", industry = "Consumer Electronics", earningsDate = "2024-07-25",
        dividendRate = 1.0f, trailingAnnualDividendRate = 0.96f
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
