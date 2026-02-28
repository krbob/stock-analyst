package net.bobinski.portfolio.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BasicInfo(
    val name: String?,
    val price: Double?,
    @SerialName("pe_ratio")
    val peRatio: Float?,
    @SerialName("pb_ratio")
    val pbRatio: Float?,
    val eps: Float?,
    val roe: Float?,
    @SerialName("market_cap")
    val marketCap: Double?,
    val recommendation: String?,
    @SerialName("analyst_count")
    val analystCount: Int?,
    @SerialName("fifty_two_week_high")
    val fiftyTwoWeekHigh: Float?,
    @SerialName("fifty_two_week_low")
    val fiftyTwoWeekLow: Float?,
    val beta: Float?,
    val sector: String?,
    val industry: String?,
    @SerialName("earnings_date")
    val earningsDate: String?,
    @SerialName("dividend_rate")
    val dividendRate: Float?,
    @SerialName("trailing_annual_dividend_rate")
    val trailingAnnualDividendRate: Float?
)
