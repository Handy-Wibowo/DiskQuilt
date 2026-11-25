package dev.handywibowo.diskquilt.ui

import java.util.concurrent.TimeUnit

/** Relative age as a unit + value, so the UI can render it in the device language. */
data class Ago(val value: Long, val unit: Unit) {
    enum class Unit { JUST_NOW, MINUTES, HOURS, DAYS, MONTHS }
}

object RelativeTime {

    fun ago(then: Long, now: Long): Ago? {
        if (then <= 0) return null
        val delta = (now - then).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
        val hours = TimeUnit.MILLISECONDS.toHours(delta)
        val days = TimeUnit.MILLISECONDS.toDays(delta)
        return when {
            minutes < 1 -> Ago(0, Ago.Unit.JUST_NOW)
            minutes < 60 -> Ago(minutes, Ago.Unit.MINUTES)
            hours < 24 -> Ago(hours, Ago.Unit.HOURS)
            days < 30 -> Ago(days, Ago.Unit.DAYS)
            else -> Ago(days / 30, Ago.Unit.MONTHS)
        }
    }
}
