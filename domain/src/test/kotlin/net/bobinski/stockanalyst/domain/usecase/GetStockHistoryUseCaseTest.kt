package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.MarketDataSource
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetStockHistoryUseCaseTest {

    private val stockDataProvider = mockk<StockDataProvider>()
    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val useCase = GetStockHistoryUseCase(stockDataProvider = stockDataProvider, currentTimeProvider = timeProvider)

    @Test
    fun `returns stock history for valid symbol and period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 2), 185.0),
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period._1y)

        assertEquals("AAPL", result.symbol)
        assertEquals("Apple Inc.", result.name)
        assertEquals("1y", result.period)
        assertEquals(2, result.prices.size)
        assertEquals(MarketDataSource.YAHOO_FINANCE, result.provenance.source)
        assertEquals(DataStatus.FRESH, result.provenance.status)
        assertEquals(LocalDate(2024, 1, 2), result.provenance.coverageFrom)
        assertEquals(LocalDate(2024, 6, 15), result.provenance.coverageTo)
    }

    @Test
    fun `returns prices sorted by date ascending`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0),
            historicalPrice(LocalDate(2024, 6, 1), 200.0),
            historicalPrice(LocalDate(2024, 6, 10), 205.0)
        )

        val result = useCase("AAPL", Period._1mo)

        assertEquals(LocalDate(2024, 6, 1), result.prices[0].date)
        assertEquals(LocalDate(2024, 6, 10), result.prices[1].date)
        assertEquals(LocalDate(2024, 6, 15), result.prices[2].date)
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
        coEvery { stockDataProvider.getHistory("INVALID", Period._1y, Interval.DAILY) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("INVALID", Period._1y) }
        assertTrue(exception.message!!.contains("Unknown symbol"))
    }

    @Test
    fun `throws BackendDataException when history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("NODATA") } returns basicInfo("No Data Corp")
        coEvery { stockDataProvider.getHistory("NODATA", Period._1y, Interval.DAILY) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("NODATA", Period._1y) }
        assertTrue(exception.message!!.contains("Missing history"))
    }

    @Test
    fun `uses correct period value in response`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5d, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 10), 205.0)
        )

        val result = useCase("AAPL", Period._5d)

        assertEquals("5d", result.period)
    }

    @Test
    fun `propagates BackendDataException from provider`() = runTest {
        coEvery { stockDataProvider.getInfo("FAIL") } returns basicInfo("Fail Corp")
        coEvery { stockDataProvider.getHistory("FAIL", Period._1y, Interval.DAILY) } throws
            BackendDataException.backendError("FAIL")

        assertThrows<BackendDataException> { useCase("FAIL", Period._1y) }
    }

    @Test
    fun `uses daily interval when explicitly overridden for 5y period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period._5y, Interval.DAILY)

        assertEquals("5y", result.period)
        assertEquals(1, result.prices.size)
    }

    @Test
    fun `uses weekly interval for 5y period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.WEEKLY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period._5y)

        assertEquals("5y", result.period)
        assertEquals(1, result.prices.size)
    }

    @Test
    fun `uses monthly interval for max period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period.max, Interval.MONTHLY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period.max)

        assertEquals("max", result.period)
        assertEquals(1, result.prices.size)
    }

    @Test
    fun `uses intraday interval when specified`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1d, Interval._5m) } returns listOf(
            intradayPrice(LocalDate(2024, 6, 15), 210.0, 1718451000L),
            intradayPrice(LocalDate(2024, 6, 15), 211.0, 1718451300L)
        )

        val result = useCase("AAPL", Period._1d, Interval._5m)

        assertEquals("1d", result.period)
        assertEquals("5m", result.interval)
        assertEquals(2, result.prices.size)
        assertEquals(1718451000L, result.prices[0].timestamp)
        assertEquals(1718451300L, result.prices[1].timestamp)
    }

    @Test
    fun `sorts intraday prices by timestamp`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1d, Interval._5m) } returns listOf(
            intradayPrice(LocalDate(2024, 6, 15), 212.0, 1718451600L),
            intradayPrice(LocalDate(2024, 6, 15), 210.0, 1718451000L),
            intradayPrice(LocalDate(2024, 6, 15), 211.0, 1718451300L)
        )

        val result = useCase("AAPL", Period._1d, Interval._5m)

        assertEquals(1718451000L, result.prices[0].timestamp)
        assertEquals(1718451300L, result.prices[1].timestamp)
        assertEquals(1718451600L, result.prices[2].timestamp)
    }

    @Test
    fun `converts prices when currency is specified`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 2), 100.0),
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 1), 0.9),
            historicalPrice(LocalDate(2024, 6, 14), 0.95)
        )

        val result = useCase("AAPL", Period._1y, currency = "EUR")

        assertEquals("EUR", result.currency)
        assertEquals(90.0, result.prices[0].close, 0.01) // 100 * 0.9
        assertEquals(190.0, result.prices[1].close, 0.01) // 200 * 0.95
    }

    @Test
    fun `skips conversion when currency matches native`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )

        val result = useCase("AAPL", Period._1y, currency = "USD")

        assertEquals("USD", result.currency)
        assertEquals(200.0, result.prices[0].close)
    }

    @Test
    fun `returns native currency when no currency specified`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )

        val result = useCase("AAPL", Period._1y)

        assertEquals("USD", result.currency)
        assertEquals(200.0, result.prices[0].close)
    }

    @Test
    fun `keeps yfinance repaired GBX candles and dividends in major GBP units`() = runTest {
        coEvery { stockDataProvider.getInfo("LSEG.L") } returns basicInfo(
            "London Stock Exchange Group",
            currency = "GBX"
        )
        coEvery { stockDataProvider.getHistory("LSEG.L", Period._1y, Interval.DAILY) } returns listOf(
            HistoricalPrice(
                date = LocalDate(2024, 6, 15),
                open = 89.07,
                close = 89.08,
                low = 89.06,
                high = 89.09,
                volume = 1_000_000L,
                dividend = 1.03
            )
        )

        val result = useCase("LSEG.L", Period._1y)

        val price = result.prices.single()
        assertEquals("GBP", result.currency)
        assertEquals(89.07, price.open)
        assertEquals(89.08, price.close)
        assertEquals(89.06, price.low)
        assertEquals(89.09, price.high)
        assertEquals(1.03, price.dividend)
    }

    @Test
    fun `throws when requested currency cannot be resolved without native currency`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = null)
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )

        val exception = assertThrows<BackendDataException> {
            useCase("AAPL", Period._1y, currency = "EUR")
        }

        assertEquals(BackendDataException.Reason.INSUFFICIENT_DATA, exception.reason)
        assertTrue(exception.message!!.contains("Currency conversion is unavailable"))
    }

    @Test
    fun `trims history to conversion overlap when conversion starts later`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2023, 6, 15), 150.0),
            historicalPrice(LocalDate(2024, 1, 2), 100.0),
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 1), 0.9),
            historicalPrice(LocalDate(2024, 6, 14), 0.95)
        )

        val result = useCase("AAPL", Period._1y, currency = "EUR")

        // Price from 2023-06-15 should be excluded (before conversion start)
        assertEquals(2, result.prices.size)
        assertEquals(LocalDate(2024, 1, 2), result.prices[0].date)
        assertEquals(DataStatus.PARTIAL, result.provenance.status)
    }

    @Test
    fun `throws when no overlap between stock and conversion history`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2023, 1, 2), 100.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 1), 0.9)
        )

        val exception = assertThrows<BackendDataException> {
            useCase("AAPL", Period._1y, currency = "EUR")
        }
        assertEquals(BackendDataException.Reason.INSUFFICIENT_DATA, exception.reason)
    }

    @Test
    fun `throws when conversion history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "XYZ") } returns "XYZ=X"
        coEvery { stockDataProvider.getHistory("XYZ=X", Period._1y) } returns emptyList()

        assertThrows<BackendDataException> { useCase("AAPL", Period._1y, currency = "XYZ") }
    }

    @Test
    fun `injects daily dividends into weekly bars`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.WEEKLY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 7), 200.0),
            historicalPrice(LocalDate(2024, 6, 14), 210.0)
        )
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 3), 198.0),
            historicalPrice(LocalDate(2024, 6, 10), 205.0, dividend = 0.25),
            historicalPrice(LocalDate(2024, 6, 14), 210.0)
        )

        val result = useCase("AAPL", Period._5y, dividends = true)

        assertEquals(0.0, result.prices[0].dividend) // week ending 6/7 — no dividend
        assertEquals(0.25, result.prices[1].dividend, 0.001) // week ending 6/14 — includes 6/10 dividend
    }

    @Test
    fun `does not carry dividends from FX-trimmed weekly bars into bounded range`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo, Interval.WEEKLY) } returns listOf(
            historicalPrice(LocalDate(2024, 5, 17), 100.0),
            historicalPrice(LocalDate(2024, 5, 24), 101.0),
            historicalPrice(LocalDate(2024, 5, 31), 102.0),
            historicalPrice(LocalDate(2024, 6, 7), 103.0),
            historicalPrice(LocalDate(2024, 6, 14), 104.0)
        )
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 5, 22), 100.0, dividend = 0.5),
            historicalPrice(LocalDate(2024, 5, 30), 101.0, dividend = 0.25),
            historicalPrice(LocalDate(2024, 6, 5), 102.0, dividend = 0.1)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1mo) } returns listOf(
            historicalPrice(LocalDate(2024, 5, 28), 2.0),
            historicalPrice(LocalDate(2024, 6, 6), 3.0)
        )

        val result = useCase(
            symbol = "AAPL",
            period = Period._1y,
            interval = Interval.WEEKLY,
            currency = "EUR",
            dividends = true,
            requestedFrom = LocalDate(2024, 5, 31),
            requestedTo = LocalDate(2024, 6, 14)
        )

        assertEquals(
            listOf(LocalDate(2024, 5, 31), LocalDate(2024, 6, 7), LocalDate(2024, 6, 14)),
            result.prices.map { it.date }
        )
        assertEquals(0.5, result.prices[0].dividend, 0.001)
        assertEquals(0.3, result.prices[1].dividend, 0.001)
        assertEquals(0.0, result.prices[2].dividend, 0.001)
        assertEquals(DataStatus.FRESH, result.provenance.status)
    }

    @Test
    fun `does not double-count dividends already present in weekly bars`() = runTest {
        // yfinance now reports the dividend on the period-start bar (6/15) itself. Injecting the
        // same payout from daily data would add a second copy on the next bar (6/22).
        coEvery { stockDataProvider.getInfo("VWRD.L") } returns basicInfo("Vanguard FTSE All-World")
        coEvery { stockDataProvider.getHistory("VWRD.L", Period._5y, Interval.WEEKLY) } returns listOf(
            historicalPrice(LocalDate(2026, 6, 8), 100.0),
            historicalPrice(LocalDate(2026, 6, 15), 101.0, dividend = 0.9055),
            historicalPrice(LocalDate(2026, 6, 22), 102.0)
        )
        coEvery { stockDataProvider.getHistory("VWRD.L", Period._5y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2026, 6, 18), 101.5, dividend = 0.9055)
        )

        val result = useCase("VWRD.L", Period._5y, dividends = true)

        assertEquals(1, result.prices.count { it.dividend > 0 })
        assertEquals(0.9055, result.prices.first { it.date == LocalDate(2026, 6, 15) }.dividend, 0.001)
        assertEquals(0.0, result.prices.first { it.date == LocalDate(2026, 6, 22) }.dividend, 0.001)
    }

    @Test
    fun `selects minimal fetch period and trims to requested range`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period.ytd, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2023, 12, 31), 180.0),
            historicalPrice(LocalDate(2024, 1, 2), 185.0),
            historicalPrice(LocalDate(2024, 3, 1), 195.0),
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase(
            symbol = "AAPL",
            period = Period._1mo,
            requestedFrom = LocalDate(2024, 1, 2),
            requestedTo = LocalDate(2024, 3, 1)
        )

        assertEquals("ytd", result.period)
        assertEquals(LocalDate(2024, 1, 2), result.requestedFrom)
        assertEquals(LocalDate(2024, 3, 1), result.requestedTo)
        assertEquals(listOf(LocalDate(2024, 1, 2), LocalDate(2024, 3, 1)), result.prices.map { it.date })
        assertEquals(DataStatus.FRESH, result.provenance.status)
    }

    @Test
    fun `trims converted history to requested range`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 2), 100.0),
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1mo) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 1), 0.9),
            historicalPrice(LocalDate(2024, 6, 14), 0.95)
        )

        val result = useCase(
            symbol = "AAPL",
            period = Period._1y,
            currency = "EUR",
            requestedFrom = LocalDate(2024, 6, 1),
            requestedTo = LocalDate(2024, 6, 15)
        )

        assertEquals(1, result.prices.size)
        assertEquals(LocalDate(2024, 6, 15), result.prices.single().date)
        assertEquals(190.0, result.prices.single().close, 0.01)
    }

    @Test
    fun `keeps fresh status when conversion only trims prices before requested range`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5d, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 5, 31), 99.0),
            historicalPrice(LocalDate(2024, 6, 10), 100.0),
            historicalPrice(LocalDate(2024, 6, 15), 105.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._5d) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 9), 0.9),
            historicalPrice(LocalDate(2024, 6, 15), 0.95)
        )

        val result = useCase(
            symbol = "AAPL",
            period = Period._1y,
            currency = "EUR",
            requestedFrom = LocalDate(2024, 6, 10),
            requestedTo = LocalDate(2024, 6, 15)
        )

        assertEquals(listOf(LocalDate(2024, 6, 10), LocalDate(2024, 6, 15)), result.prices.map { it.date })
        assertEquals(DataStatus.FRESH, result.provenance.status)
    }

    @Test
    fun `keeps partial status when conversion trims a price inside requested range`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5d, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 5, 31), 99.0),
            historicalPrice(LocalDate(2024, 6, 10), 100.0),
            historicalPrice(LocalDate(2024, 6, 15), 105.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._5d) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 11), 0.9),
            historicalPrice(LocalDate(2024, 6, 15), 0.95)
        )

        val result = useCase(
            symbol = "AAPL",
            period = Period._1y,
            currency = "EUR",
            requestedFrom = LocalDate(2024, 6, 10),
            requestedTo = LocalDate(2024, 6, 15)
        )

        assertEquals(listOf(LocalDate(2024, 6, 15)), result.prices.map { it.date })
        assertEquals(DataStatus.PARTIAL, result.provenance.status)
    }

    @Test
    fun `keeps partial status when conversion trims indicator warmup prices`() = runTest {
        val historyStart = LocalDate(2024, 5, 29)
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo, Interval.DAILY) } returns
            (0..17).map { offset ->
                historicalPrice(historyStart.plus(offset, DateTimeUnit.DAY), 100.0 + offset)
            }
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1mo) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 5), 0.9),
            historicalPrice(LocalDate(2024, 6, 15), 0.95)
        )

        val result = useCase(
            symbol = "AAPL",
            period = Period._1y,
            indicators = setOf("rsi"),
            currency = "EUR",
            requestedFrom = LocalDate(2024, 6, 14),
            requestedTo = LocalDate(2024, 6, 15)
        )

        assertEquals(listOf(LocalDate(2024, 6, 14), LocalDate(2024, 6, 15)), result.prices.map { it.date })
        assertTrue(result.indicators?.rsi?.isEmpty() == true)
        assertEquals(DataStatus.PARTIAL, result.provenance.status)
    }

    @Test
    fun `keeps fresh status when conversion trim ends before RSI lookback boundary`() = runTest {
        val historyStart = LocalDate(2024, 5, 31)
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo, Interval.DAILY) } returns
            (0..15).map { offset ->
                historicalPrice(historyStart.plus(offset, DateTimeUnit.DAY), 100.0 + offset)
            }
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1mo) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 2), 0.9),
            historicalPrice(LocalDate(2024, 6, 15), 0.95)
        )

        val result = useCase(
            symbol = "AAPL",
            period = Period._1y,
            indicators = setOf("rsi"),
            currency = "EUR",
            requestedFrom = LocalDate(2024, 6, 15),
            requestedTo = LocalDate(2024, 6, 15)
        )

        assertEquals(1, result.prices.size)
        assertEquals(DataStatus.FRESH, result.provenance.status)
    }

    private fun basicInfo(name: String, currency: String? = null) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0, pbRatio = 10.0, eps = 5.0, roe = 0.3,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0, fiftyTwoWeekLow = 120.0, beta = 1.2,
        sector = "Technology", industry = "Consumer Electronics",
        earningsDate = LocalDate(2024, 7, 25),
        dividendRate = 1.0, trailingAnnualDividendRate = 0.96, currency = currency
    )

    private fun historicalPrice(date: LocalDate, close: Double, dividend: Double = 0.0) = HistoricalPrice(
        date = date, open = close - 1, close = close,
        low = close - 2, high = close + 1, volume = 1_000_000L,
        dividend = dividend
    )

    private fun intradayPrice(date: LocalDate, close: Double, timestamp: Long) = HistoricalPrice(
        date = date, open = close - 1, close = close,
        low = close - 2, high = close + 1, volume = 1_000_000L,
        dividend = 0.0, timestamp = timestamp
    )
}
