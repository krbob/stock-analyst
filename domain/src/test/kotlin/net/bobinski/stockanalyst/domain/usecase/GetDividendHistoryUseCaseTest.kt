package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.DividendPayment
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetDividendHistoryUseCaseTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val stockDataProvider = mockk<StockDataProvider>()
    private val useCase = GetDividendHistoryUseCase(
        stockDataProvider = stockDataProvider,
        currentTimeProvider = timeProvider
    )

    @Test
    fun `returns dividend history for valid symbol`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getDividends("AAPL") } returns listOf(
            DividendPayment(LocalDate(2024, 2, 9), 0.24),
            DividendPayment(LocalDate(2024, 5, 10), 0.25)
        )

        val result = useCase("AAPL")

        assertEquals("AAPL", result.symbol)
        assertEquals("Apple Inc.", result.name)
        assertEquals(2, result.payments.size)
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
        coEvery { stockDataProvider.getDividends("INVALID") } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("INVALID") }

        assertTrue(exception.message!!.contains("Unknown symbol"))
    }

    @Test
    fun `calculates current yield from recent payments`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getDividends("AAPL") } returns listOf(
            DividendPayment(LocalDate(2023, 8, 11), 0.24),
            DividendPayment(LocalDate(2023, 11, 10), 0.24),
            DividendPayment(LocalDate(2024, 2, 9), 0.24),
            DividendPayment(LocalDate(2024, 5, 10), 0.25)
        )

        val result = useCase("AAPL")

        // 4 payments within last year from 2024-06-15: all 4
        // yield = (0.24 + 0.24 + 0.24 + 0.25) / 150.0 ≈ 0.006467
        assertTrue(result.summary.currentYield > 0)
    }

    @Test
    fun `returns zero yield when no recent payments`() = runTest {
        coEvery { stockDataProvider.getInfo("OLD") } returns basicInfo("Old Dividend")
        coEvery { stockDataProvider.getDividends("OLD") } returns listOf(
            DividendPayment(LocalDate(2020, 1, 15), 0.50)
        )

        val result = useCase("OLD")

        assertEquals(0.0, result.summary.currentYield)
    }

    @Test
    fun `estimates frequency from recent payment count`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getDividends("AAPL") } returns listOf(
            DividendPayment(LocalDate(2023, 8, 11), 0.24),
            DividendPayment(LocalDate(2023, 11, 10), 0.24),
            DividendPayment(LocalDate(2024, 2, 9), 0.24),
            DividendPayment(LocalDate(2024, 5, 10), 0.25)
        )

        val result = useCase("AAPL")

        assertEquals(4, result.summary.frequency)
    }

    @Test
    fun `handles empty dividend list`() = runTest {
        coEvery { stockDataProvider.getInfo("NODIV") } returns basicInfo("No Dividend Corp")
        coEvery { stockDataProvider.getDividends("NODIV") } returns emptyList()

        val result = useCase("NODIV")

        assertTrue(result.payments.isEmpty())
        assertEquals(0.0, result.summary.currentYield)
        assertEquals(0, result.summary.frequency)
        assertNull(result.summary.growth)
    }

    @Test
    fun `calculates growth between years`() = runTest {
        coEvery { stockDataProvider.getInfo("GROW") } returns basicInfo("Growing Corp")
        coEvery { stockDataProvider.getDividends("GROW") } returns listOf(
            // 2022 payments: 4 × 0.20 = 0.80
            DividendPayment(LocalDate(2022, 2, 1), 0.20),
            DividendPayment(LocalDate(2022, 5, 1), 0.20),
            DividendPayment(LocalDate(2022, 8, 1), 0.20),
            DividendPayment(LocalDate(2022, 11, 1), 0.20),
            // 2023 payments: 4 × 0.25 = 1.00
            DividendPayment(LocalDate(2023, 2, 1), 0.25),
            DividendPayment(LocalDate(2023, 5, 1), 0.25),
            DividendPayment(LocalDate(2023, 8, 1), 0.25),
            DividendPayment(LocalDate(2023, 11, 1), 0.25)
        )

        val result = useCase("GROW")

        // growth = (1.00 - 0.80) / 0.80 = 0.25
        assertEquals(0.25, result.summary.growth)
    }

    private fun basicInfo(name: String) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0f, pbRatio = 10.0f, eps = 5.0f, roe = 0.3f,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0f, fiftyTwoWeekLow = 120.0f, beta = 1.2f,
        sector = "Technology", industry = "Consumer Electronics", earningsDate = "2024-07-25",
        dividendRate = 1.0f, trailingAnnualDividendRate = 0.96f
    )
}
