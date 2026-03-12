package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BasicInfo(
    val name: String?,
    val price: Double?,
    @SerialName("pe_ratio")
    val peRatio: Double?,
    @SerialName("pb_ratio")
    val pbRatio: Double?,
    val eps: Double?,
    val roe: Double?,
    @SerialName("market_cap")
    val marketCap: Double?,
    val recommendation: String?,
    @SerialName("analyst_count")
    val analystCount: Int?,
    @SerialName("fifty_two_week_high")
    val fiftyTwoWeekHigh: Double?,
    @SerialName("fifty_two_week_low")
    val fiftyTwoWeekLow: Double?,
    val beta: Double?,
    val sector: String?,
    val industry: String?,
    @SerialName("earnings_date")
    val earningsDate: LocalDate?,
    @SerialName("dividend_rate")
    val dividendRate: Double?,
    @SerialName("trailing_annual_dividend_rate")
    val trailingAnnualDividendRate: Double?,
    val currency: String? = null
)
