package net.bobinski.stockanalyst.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

interface CurrentTimeProvider {
    fun localDate(): LocalDate
    fun instant(): Instant = localDate().atStartOfDayIn(TimeZone.UTC)
}

internal class SystemCurrentTimeProvider(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CurrentTimeProvider {
    override fun localDate(): LocalDate = instant().toLocalDateTime(timeZone).date
    override fun instant(): Instant = Clock.System.now()
}
