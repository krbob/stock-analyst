package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class SingleValue(
    val date: LocalDate,
    val value: Double,
    val timestamp: Long? = null
)

@Serializable
data class BollingerValue(
    val date: LocalDate,
    val upper: Double,
    val middle: Double,
    val lower: Double,
    val timestamp: Long? = null
)

@Serializable
data class MacdValue(
    val date: LocalDate,
    val macd: Double,
    val signal: Double,
    val histogram: Double,
    val timestamp: Long? = null
)

@Serializable
data class Indicators(
    val sma50: List<SingleValue>? = null,
    val sma200: List<SingleValue>? = null,
    val ema50: List<SingleValue>? = null,
    val ema200: List<SingleValue>? = null,
    val bb: List<BollingerValue>? = null,
    val rsi: List<SingleValue>? = null,
    val macd: List<MacdValue>? = null,
)

fun Indicators.trimTo(cutoff: LocalDate): Indicators = Indicators(
    sma50 = sma50?.filter { it.date >= cutoff },
    sma200 = sma200?.filter { it.date >= cutoff },
    ema50 = ema50?.filter { it.date >= cutoff },
    ema200 = ema200?.filter { it.date >= cutoff },
    bb = bb?.filter { it.date >= cutoff },
    rsi = rsi?.filter { it.date >= cutoff },
    macd = macd?.filter { it.date >= cutoff },
)

fun Indicators.trimToRange(from: LocalDate, to: LocalDate): Indicators = Indicators(
    sma50 = sma50?.filter { it.date >= from && it.date <= to },
    sma200 = sma200?.filter { it.date >= from && it.date <= to },
    ema50 = ema50?.filter { it.date >= from && it.date <= to },
    ema200 = ema200?.filter { it.date >= from && it.date <= to },
    bb = bb?.filter { it.date >= from && it.date <= to },
    rsi = rsi?.filter { it.date >= from && it.date <= to },
    macd = macd?.filter { it.date >= from && it.date <= to },
)
