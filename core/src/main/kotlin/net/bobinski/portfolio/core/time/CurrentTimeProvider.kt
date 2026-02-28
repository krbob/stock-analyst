package net.bobinski.portfolio.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

interface CurrentTimeProvider {
    fun localDate(): LocalDate
}

internal class SystemCurrentTimeProvider(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CurrentTimeProvider {
    override fun localDate(): LocalDate = Clock.System.now().toLocalDateTime(timeZone).date
}
