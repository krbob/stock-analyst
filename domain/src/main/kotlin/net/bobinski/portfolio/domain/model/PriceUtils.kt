package net.bobinski.portfolio.domain.model

import kotlinx.datetime.LocalDate

fun Collection<HistoricalPrice>.latestPrice(): Double =
    maxByOrNull { it.date }?.close ?: Double.NaN

fun Collection<HistoricalPrice>.priceFor(date: LocalDate): Double =
    filter { it.date <= date }.latestPrice()

fun Double.applyConversion(conversion: Double?): Double = conversion?.let { this * it } ?: this
