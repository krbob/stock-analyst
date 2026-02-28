package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

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
        val daily: Double,
        val weekly: Double,
        val monthly: Double,
        val quarterly: Double,
        val yearly: Double
    )

    fun roundValues() = copy(
        lastPrice = lastPrice.round(2),
        gain = Gain(
            daily = gain.daily.round(3),
            weekly = gain.weekly.round(3),
            monthly = gain.monthly.round(3),
            quarterly = gain.quarterly.round(3),
            yearly = gain.yearly.round(3)
        )
    )

    private fun Double.round(decimals: Int): Double =
        "%.${decimals}f".format(java.util.Locale.ROOT, this).toDouble()
}
