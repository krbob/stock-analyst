package net.bobinski.portfolio.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

class MutableCurrentTimeProvider(private var date: LocalDate) : CurrentTimeProvider {
    override fun localDate(): LocalDate = date

    fun setDate(newDate: LocalDate) {
        date = newDate
    }
}

fun fixedTimeProvider(year: Int, month: Int = 6, day: Int = 15): MutableCurrentTimeProvider =
    MutableCurrentTimeProvider(LocalDate(year, month, day))
