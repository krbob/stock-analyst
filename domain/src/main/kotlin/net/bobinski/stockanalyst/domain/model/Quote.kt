package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class Quote(
    val symbol: String,
    val name: String,
    val currency: String? = null,
    val date: LocalDate,
    val lastPrice: Double,
    val gain: Gain,
    val peRatio: Double?,
    val pbRatio: Double?,
    val eps: Double?,
    val roe: Double?,
    val marketCap: Double?,
    val beta: Double?,
    val dividendYield: Double?,
    val dividendGrowth: Double?,
    val fiftyTwoWeekHigh: Double?,
    val fiftyTwoWeekLow: Double?,
    val sector: String?,
    val industry: String?,
    val earningsDate: LocalDate?,
    val recommendation: String?,
    val analystCount: Int?
) {

    @Serializable
    data class Gain(
        val daily: Double?,
        val weekly: Double?,
        val monthly: Double?,
        val quarterly: Double?,
        val halfYearly: Double?,
        val ytd: Double?,
        val yearly: Double?,
        val fiveYear: Double?
    )

    fun roundValues() = copy(
        lastPrice = lastPrice.round(2),
        gain = Gain(
            daily = gain.daily.nanToNull()?.round(3),
            weekly = gain.weekly.nanToNull()?.round(3),
            monthly = gain.monthly.nanToNull()?.round(3),
            quarterly = gain.quarterly.nanToNull()?.round(3),
            halfYearly = gain.halfYearly.nanToNull()?.round(3),
            ytd = gain.ytd.nanToNull()?.round(3),
            yearly = gain.yearly.nanToNull()?.round(3),
            fiveYear = gain.fiveYear.nanToNull()?.round(3)
        ),
        dividendYield = dividendYield.nanToNull()?.round(3),
        dividendGrowth = dividendGrowth.nanToNull()?.round(3),
        peRatio = peRatio?.round(2),
        pbRatio = pbRatio?.round(2),
        eps = eps?.round(2),
        roe = roe?.round(2),
        fiftyTwoWeekHigh = fiftyTwoWeekHigh?.round(2),
        fiftyTwoWeekLow = fiftyTwoWeekLow?.round(2),
        beta = beta?.round(2)
    )

    private fun Double?.nanToNull(): Double? = if (this?.isNaN() == true) null else this

    private fun Double.round(decimals: Int): Double =
        "%.${decimals}f".format(Locale.ROOT, this).toDouble()
}
