package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class Price(
    val symbol: String,
    val name: String,
    val conversionName: String? = null,
    val date: LocalDate,
    val lastPrice: Double,
    val gain: Gain
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
        )
    )

    private fun Double.round(decimals: Int): Double =
        "%.${decimals}f".format(Locale.ROOT, this).toDouble()

    private fun Double?.nanToNull(): Double? = if (this?.isNaN() == true) null else this
}
