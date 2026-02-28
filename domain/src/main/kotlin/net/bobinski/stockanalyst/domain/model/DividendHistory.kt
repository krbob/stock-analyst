package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class DividendPayment(
    val date: LocalDate,
    val amount: Double
)

@Serializable
data class DividendHistory(
    val symbol: String,
    val name: String,
    val payments: List<DividendPayment>,
    val summary: Summary
) {

    @Serializable
    data class Summary(
        val currentYield: Double,
        val growth: Double?,
        val frequency: Int
    )

    fun roundValues() = copy(
        payments = payments.map { it.copy(amount = it.amount.round(4)) },
        summary = summary.copy(
            currentYield = summary.currentYield.round(3),
            growth = summary.growth?.round(3)
        )
    )

    private fun Double.round(decimals: Int): Double =
        "%.${decimals}f".format(java.util.Locale.ROOT, this).toDouble()
}
