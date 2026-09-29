package net.bobinski.stockanalyst

import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.HistoryPartialReason
import net.bobinski.stockanalyst.domain.model.LatestIndicators
import net.bobinski.stockanalyst.domain.model.Quote
import net.bobinski.stockanalyst.domain.model.StockHistory
import java.util.Locale
import java.util.concurrent.atomic.LongAdder

internal enum class MarketDataOperation { QUOTE, HISTORY, INDICATORS }
internal enum class MarketDataPartialReason { EMPTY_RANGE, FX_COVERAGE, OTHER }

internal data class MarketDataObservation(
    val operation: MarketDataOperation,
    val status: DataStatus,
    val historical: Boolean,
    val ageSeconds: Double?,
    val partialReason: MarketDataPartialReason? = null
) {
    companion object {
        fun from(body: Any): MarketDataObservation? {
            val (operation, provenance) = when (body) {
                is Quote -> MarketDataOperation.QUOTE to body.provenance
                is StockHistory -> MarketDataOperation.HISTORY to body.provenance
                is LatestIndicators -> MarketDataOperation.INDICATORS to body.provenance
                else -> return null
            }
            val today = provenance.retrievedAt.toLocalDateTime(TimeZone.UTC).date
            val historical = body is StockHistory && body.requestedTo?.let { it < today } == true
            val observationTime = provenance.marketTimestamp
                ?: provenance.marketDate?.atStartOfDayIn(TimeZone.UTC)
            return MarketDataObservation(
                operation = operation,
                // A younger instrument can have incomplete long-term analytics and a fresh price.
                status = if (body is Quote) provenance.priceStatus ?: provenance.status else provenance.status,
                historical = historical,
                ageSeconds = observationTime?.let {
                    (provenance.retrievedAt - it).inWholeMilliseconds.coerceAtLeast(0) / 1000.0
                },
                partialReason = when ((body as? StockHistory)?.partialReason) {
                    HistoryPartialReason.EMPTY_RANGE -> MarketDataPartialReason.EMPTY_RANGE
                    HistoryPartialReason.FX_COVERAGE -> MarketDataPartialReason.FX_COVERAGE
                    null -> null
                }
            )
        }
    }
}

internal class MarketDataMetrics {
    private data class Key(val operation: MarketDataOperation, val historical: Boolean, val status: DataStatus)
    private val responses = MarketDataOperation.entries.flatMap { operation ->
        listOf(false, true).flatMap { historical ->
            DataStatus.entries.map { status -> Key(operation, historical, status) to LongAdder() }
        }
    }.toMap()
    private val ages = MarketDataOperation.entries.associateWith { AgeHistogram() }
    private data class PartialKey(
        val operation: MarketDataOperation,
        val historical: Boolean,
        val reason: MarketDataPartialReason
    )
    private val partialResponses = MarketDataOperation.entries.flatMap { operation ->
        listOf(false, true).flatMap { historical ->
            MarketDataPartialReason.entries.map { reason -> PartialKey(operation, historical, reason) to LongAdder() }
        }
    }.toMap()

    fun record(observation: MarketDataObservation) {
        responses.getValue(Key(observation.operation, observation.historical, observation.status)).increment()
        if (observation.status == DataStatus.PARTIAL) {
            val reason = observation.partialReason ?: MarketDataPartialReason.OTHER
            partialResponses.getValue(PartialKey(observation.operation, observation.historical, reason)).increment()
        }
        if (!observation.historical) {
            observation.ageSeconds?.let { ages.getValue(observation.operation).record(it) }
        }
    }

    fun scrape(): String = buildString {
        appendLine("# HELP stock_analyst_market_data_responses_total Successful responses by data quality; quotes use priceStatus when available.")
        appendLine("# TYPE stock_analyst_market_data_responses_total counter")
        responses.forEach { (key, count) ->
            val scope = if (key.historical) "historical" else "live"
            appendLine("stock_analyst_market_data_responses_total{operation=\"${key.operation.name.lowercase()}\",scope=\"$scope\",status=\"${key.status}\"} ${count.sum()}")
        }
        appendLine("# HELP stock_analyst_market_data_partial_responses_total Partial successful responses by cause; empty_range does not imply a market holiday.")
        appendLine("# TYPE stock_analyst_market_data_partial_responses_total counter")
        partialResponses.forEach { (key, count) ->
            val scope = if (key.historical) "historical" else "live"
            appendLine("stock_analyst_market_data_partial_responses_total{operation=\"${key.operation.name.lowercase()}\",scope=\"$scope\",reason=\"${key.reason.name.lowercase()}\"} ${count.sum()}")
        }
        appendLine("# HELP stock_analyst_market_data_age_seconds Age of live data at response time; date-only observations use UTC midnight. Archival requests are excluded.")
        appendLine("# TYPE stock_analyst_market_data_age_seconds histogram")
        ages.forEach { (operation, histogram) ->
            val label = "operation=\"${operation.name.lowercase()}\""
            AGE_BUCKETS.forEachIndexed { index, limit ->
                appendLine("stock_analyst_market_data_age_seconds_bucket{$label,le=\"$limit\"} ${histogram.buckets[index].sum()}")
            }
            appendLine("stock_analyst_market_data_age_seconds_bucket{$label,le=\"+Inf\"} ${histogram.count.sum()}")
            appendLine("stock_analyst_market_data_age_seconds_count{$label} ${histogram.count.sum()}")
            appendLine("stock_analyst_market_data_age_seconds_sum{$label} ${String.format(Locale.ROOT, "%.3f", histogram.millis.sum() / 1000.0)}")
        }
    }

    private class AgeHistogram {
        val buckets = AGE_BUCKETS.map { LongAdder() }
        val count = LongAdder()
        val millis = LongAdder()

        fun record(seconds: Double) {
            count.increment()
            millis.add((seconds * 1000).toLong())
            AGE_BUCKETS.forEachIndexed { index, limit ->
                if (seconds <= limit) buckets[index].increment()
            }
        }
    }
}

private val AGE_BUCKETS = listOf(60, 3600, 86400, 172800, 345600, 864000, 3456000)
