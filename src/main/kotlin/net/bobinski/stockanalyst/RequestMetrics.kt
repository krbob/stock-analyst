package net.bobinski.stockanalyst

import io.ktor.http.HttpMethod
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

internal val ObservabilityModule = module {
    single { RequestMetricsRegistry() }
}

internal class RequestMetricsConfig {
    lateinit var registry: RequestMetricsRegistry
}

internal val RequestMetricsPlugin = createApplicationPlugin(
    name = "RequestMetrics",
    createConfiguration = ::RequestMetricsConfig
) {
    val registry = pluginConfig.registry

    onCall { call ->
        if (call.request.path() !in EXCLUDED_PATHS) {
            call.attributes.put(REQUEST_STARTED_AT_NANOS, System.nanoTime())
        }
    }
    on(ResponseSent) { call ->
        val startedAt = call.attributes.getOrNull(REQUEST_STARTED_AT_NANOS) ?: return@on
        if (call.attributes.contains(METRICS_RECORDED)) return@on
        call.attributes.put(METRICS_RECORDED, true)
        registry.record(
            method = call.request.httpMethod.metricLabel(),
            route = call.request.path().normalizedRoute(),
            status = call.response.status()?.value ?: 500,
            durationNanos = (System.nanoTime() - startedAt).coerceAtLeast(0)
        )
    }
}

internal class RequestMetricsRegistry {
    private val samples = ConcurrentHashMap<MetricKey, MetricSample>()

    fun record(method: String, route: String, status: Int, durationNanos: Long) {
        samples.computeIfAbsent(MetricKey(method, route, status)) { MetricSample() }
            .record(durationNanos)
    }

    fun scrape(): String = buildString {
        appendLine("# HELP stock_analyst_http_requests_total Completed API requests.")
        appendLine("# TYPE stock_analyst_http_requests_total counter")
        orderedSamples().forEach { (key, sample) ->
            append("stock_analyst_http_requests_total")
            append(key.labels())
            append(' ')
            appendLine(sample.count.sum())
        }
        appendLine("# HELP stock_analyst_http_request_duration_seconds API request latency.")
        appendLine("# TYPE stock_analyst_http_request_duration_seconds histogram")
        orderedSamples().forEach { (key, sample) ->
            DURATION_BUCKETS.forEachIndexed { index, bucket ->
                append("stock_analyst_http_request_duration_seconds_bucket")
                append(key.labels(extra = "le=\"${bucket.label}\""))
                append(' ')
                appendLine(sample.buckets[index].sum())
            }
            append("stock_analyst_http_request_duration_seconds_bucket")
            append(key.labels(extra = "le=\"+Inf\""))
            append(' ')
            appendLine(sample.count.sum())
            append("stock_analyst_http_request_duration_seconds_sum")
            append(key.labels())
            append(' ')
            appendLine(String.format(Locale.ROOT, "%.9f", sample.totalNanos.sum() / NANOS_PER_SECOND))
            append("stock_analyst_http_request_duration_seconds_count")
            append(key.labels())
            append(' ')
            appendLine(sample.count.sum())
        }
    }

    private fun orderedSamples(): List<Map.Entry<MetricKey, MetricSample>> =
        samples.entries.sortedWith(
            compareBy<Map.Entry<MetricKey, MetricSample>>(
                { it.key.route },
                { it.key.method },
                { it.key.status }
            )
        )
}

private data class MetricKey(
    val method: String,
    val route: String,
    val status: Int
) {
    fun labels(extra: String? = null): String = buildString {
        append("{method=\"")
        append(method)
        append("\",route=\"")
        append(route)
        append("\",status=\"")
        append(status)
        append('"')
        if (extra != null) {
            append(',')
            append(extra)
        }
        append('}')
    }
}

private class MetricSample {
    val count = LongAdder()
    val totalNanos = LongAdder()
    val buckets = List(DURATION_BUCKETS.size) { LongAdder() }

    fun record(durationNanos: Long) {
        count.increment()
        totalNanos.add(durationNanos)
        DURATION_BUCKETS.forEachIndexed { index, bucket ->
            if (durationNanos <= bucket.nanos) buckets[index].increment()
        }
    }
}

private fun HttpMethod.metricLabel(): String = when (this) {
    HttpMethod.Get -> "GET"
    HttpMethod.Post -> "POST"
    HttpMethod.Put -> "PUT"
    HttpMethod.Patch -> "PATCH"
    HttpMethod.Delete -> "DELETE"
    HttpMethod.Options -> "OPTIONS"
    HttpMethod.Head -> "HEAD"
    else -> "OTHER"
}

private fun String.normalizedRoute(): String = when {
    this == "/compare" || this == "/v1/compare" -> "/v1/compare"
    QUOTE_ROUTE.matches(this) -> "/v1/quote/{stock}"
    HISTORY_ROUTE.matches(this) -> "/v1/history/{stock}"
    INDICATORS_ROUTE.matches(this) -> "/v1/indicators/{stock}"
    SEARCH_ROUTE.matches(this) -> "/v1/search/{query}"
    else -> "unmatched"
}

private data class DurationBucket(val label: String, val nanos: Long)

private const val NANOS_PER_SECOND = 1_000_000_000.0
private val REQUEST_STARTED_AT_NANOS = AttributeKey<Long>("request-metrics-started-at")
private val METRICS_RECORDED = AttributeKey<Boolean>("request-metrics-recorded")
private val EXCLUDED_PATHS = setOf("/health", "/healthz", "/readyz", "/metrics", "/openapi/v1.json")
private val QUOTE_ROUTE = Regex("^/(?:v1/)?quote/[^/]+$")
private val HISTORY_ROUTE = Regex("^/(?:v1/)?history/[^/]+$")
private val INDICATORS_ROUTE = Regex("^/(?:v1/)?indicators/[^/]+$")
private val SEARCH_ROUTE = Regex("^/(?:v1/)?search/[^/]+$")
private val DURATION_BUCKETS = listOf(
    DurationBucket("0.01", 10_000_000),
    DurationBucket("0.05", 50_000_000),
    DurationBucket("0.1", 100_000_000),
    DurationBucket("0.25", 250_000_000),
    DurationBucket("0.5", 500_000_000),
    DurationBucket("1", 1_000_000_000),
    DurationBucket("2.5", 2_500_000_000),
    DurationBucket("5", 5_000_000_000),
    DurationBucket("10", 10_000_000_000),
    DurationBucket("30", 30_000_000_000)
)
