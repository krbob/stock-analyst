package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class Analysis(
    val symbol: String,
    val name: String,
    val conversionName: String? = null,
    val date: LocalDate,
    val lastPrice: Double,
    val gain: Gain,
    val rsi: Rsi,
    val macd: Macd,
    val bollingerBands: BollingerBands,
    val movingAverages: MovingAverages,
    val atr: Double?,
    val dividendYield: Double?,
    val dividendGrowth: Double?,
    val peRatio: Float?,
    val pbRatio: Float?,
    val eps: Float?,
    val roe: Float?,
    val marketCap: Double?,
    val recommendation: String?,
    val analystCount: Int?,
    val fiftyTwoWeekHigh: Float?,
    val fiftyTwoWeekLow: Float?,
    val beta: Float?,
    val sector: String?,
    val industry: String?,
    val earningsDate: String?
) {

    @Serializable
    data class Gain(
        val daily: Double?,
        val weekly: Double?,
        val monthly: Double?,
        val quarterly: Double?,
        val yearly: Double?
    )

    @Serializable
    data class Rsi(
        val daily: Double?,
        val weekly: Double?,
        val monthly: Double?
    )

    @Serializable
    data class Macd(
        val macd: Double?,
        val signal: Double?,
        val histogram: Double?
    )

    @Serializable
    data class BollingerBands(
        val upper: Double?,
        val middle: Double?,
        val lower: Double?
    )

    @Serializable
    data class MovingAverages(
        val sma50: Double?,
        val sma200: Double?,
        val ema50: Double?,
        val ema200: Double?
    )

    fun roundValues() = copy(
        lastPrice = lastPrice.round(2),
        gain = Gain(
            daily = gain.daily.nanToNull()?.round(3),
            weekly = gain.weekly.nanToNull()?.round(3),
            monthly = gain.monthly.nanToNull()?.round(3),
            quarterly = gain.quarterly.nanToNull()?.round(3),
            yearly = gain.yearly.nanToNull()?.round(3)
        ),
        rsi = Rsi(
            daily = rsi.daily.nanToNull()?.round(2),
            weekly = rsi.weekly.nanToNull()?.round(2),
            monthly = rsi.monthly.nanToNull()?.round(2)
        ),
        macd = Macd(
            macd = macd.macd.nanToNull()?.round(2),
            signal = macd.signal.nanToNull()?.round(2),
            histogram = macd.histogram.nanToNull()?.round(2)
        ),
        bollingerBands = BollingerBands(
            upper = bollingerBands.upper.nanToNull()?.round(2),
            middle = bollingerBands.middle.nanToNull()?.round(2),
            lower = bollingerBands.lower.nanToNull()?.round(2)
        ),
        movingAverages = MovingAverages(
            sma50 = movingAverages.sma50.nanToNull()?.round(2),
            sma200 = movingAverages.sma200.nanToNull()?.round(2),
            ema50 = movingAverages.ema50.nanToNull()?.round(2),
            ema200 = movingAverages.ema200.nanToNull()?.round(2)
        ),
        atr = atr.nanToNull()?.round(2),
        dividendYield = dividendYield.nanToNull()?.round(3),
        dividendGrowth = dividendGrowth?.round(3),
        peRatio = peRatio?.round(2),
        pbRatio = pbRatio?.round(2),
        eps = eps?.round(2),
        roe = roe?.round(2),
        fiftyTwoWeekHigh = fiftyTwoWeekHigh?.round(2),
        fiftyTwoWeekLow = fiftyTwoWeekLow?.round(2),
        beta = beta?.round(2)
    )

    private fun Double?.nanToNull(): Double? = if (this?.isNaN() == true) null else this

    private fun Double.round(decimals: Int): Double {
        return "%.${decimals}f".format(Locale.ROOT, this).toDouble()
    }

    private fun Float.round(decimals: Int): Float {
        return "%.${decimals}f".format(Locale.ROOT, this).toFloat()
    }
}
