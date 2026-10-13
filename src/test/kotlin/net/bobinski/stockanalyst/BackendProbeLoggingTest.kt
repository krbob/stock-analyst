package net.bobinski.stockanalyst

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

class BackendProbeLoggingTest {

    @Test
    fun `successful probes are silent while other HTTP requests remain logged`() = runTest {
        withProbe({ respond("ok") }) { client, provider, events ->
            assertTrue(provider.isReady())
            client.get("http://backend.test/health?probe=1")
            assertTrue(events.isEmpty())

            client.get("http://backend.test/health/details")
            assertTrue(events.any { it.formattedMessage.contains("/health/details") })
        }
    }

    @Test
    fun `non-success status produces one warning without HTTP client noise`() = runTest {
        withProbe({ respond("busy", HttpStatusCode.ServiceUnavailable) }) { _, provider, events ->
            assertFalse(provider.isReady())
            assertEquals(Level.WARN, events.single().level)
            assertTrue(events.single().formattedMessage.contains("503"))
        }
    }

    @Test
    fun `transport failure retains its warning and exception`() = runTest {
        withProbe({ throw IOException("connection refused") }) { _, provider, events ->
            assertFalse(provider.isReady())
            assertEquals(Level.WARN, events.single().level)
            assertEquals("connection refused", events.single().throwableProxy.message)
        }
    }

    @Test
    fun `readiness deadline produces a warning`() = runTest {
        withProbe({ delay(2_000); respond("ok") }) { _, provider, events ->
            assertFalse(provider.isReady())
            assertEquals(Level.WARN, events.single().level)
            assertTrue(events.single().formattedMessage.contains("timed out after 1000 ms"))
        }
    }

    @Test
    fun `caller cancellation propagates without an availability warning`() = runTest {
        withProbe({ throw CancellationException("cancelled") }) { _, provider, events ->
            assertThrows<CancellationException> { provider.isReady() }
            assertTrue(events.isEmpty())
        }
    }

    private suspend fun TestScope.withProbe(
        handler: suspend MockRequestHandleScope.() -> HttpResponseData,
        check: suspend (HttpClient, BackendProvider, List<ILoggingEvent>) -> Unit
    ) {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val loggers = listOf(
            LoggerFactory.getLogger(BackendProvider::class.java) as Logger,
            LoggerFactory.getLogger("io.ktor.client.HttpClient") as Logger
        )
        loggers.forEach { it.addAppender(appender) }
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { handler() }
        })
        val client = HttpClient(engine) {
            install(Logging) { configureBackendLogging() }
        }
        val provider = BackendProvider(client, "http://backend.test")
        try {
            check(client, provider, appender.list)
        } finally {
            provider.close()
            client.close()
            loggers.forEach { it.detachAppender(appender) }
            appender.stop()
        }
    }
}
