package net.bobinski.stockanalyst

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import org.slf4j.event.Level
import java.util.UUID

fun Application.configureMonitoring() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify(REQUEST_ID_PATTERN::matches)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        filter { call -> call.request.path() !in PROBE_PATHS }
    }
}

private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val PROBE_PATHS = setOf("/health", "/healthz", "/readyz")
