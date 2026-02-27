package net.bobinski.portfolio.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface CurrentTimeProvider {
    fun localDate(): LocalDate
}

@OptIn(ExperimentalTime::class)
internal class SystemCurrentTimeProvider(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CurrentTimeProvider {
    override fun localDate(): LocalDate = Clock.System.now().toLocalDateTime(timeZone).date
}
