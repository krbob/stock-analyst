package net.bobinski.stockanalyst

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

internal class RuntimeMetrics : AutoCloseable {
    private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val gcMetrics = JvmGcMetrics()

    init {
        ClassLoaderMetrics().bindTo(registry)
        JvmMemoryMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
        FileDescriptorMetrics().bindTo(registry)
        gcMetrics.bindTo(registry)
    }

    fun scrape(): String = registry.scrape()

    override fun close() {
        gcMetrics.close()
        registry.close()
    }
}
