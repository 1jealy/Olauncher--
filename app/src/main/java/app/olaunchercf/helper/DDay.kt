package app.olaunchercf.helper

import app.olaunchercf.data.Prefs
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToLong

object DDay {
    private const val DATE_PATTERN = "yyyy-MM-dd"

    fun parse(date: String): Calendar? {
        if (date.isEmpty()) return null
        return try {
            val parsed = SimpleDateFormat(DATE_PATTERN, Locale.US).parse(date) ?: return null
            Calendar.getInstance().apply { time = parsed }
        } catch (_: Exception) {
            null
        }
    }

    fun format(year: Int, month: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)

    /** Days from today until [date]; negative once the date has passed. */
    fun daysUntil(date: Calendar): Long {
        val today = midnight(Calendar.getInstance())
        val target = midnight(date.clone() as Calendar)
        // rounding absorbs daylight saving time shifts
        return ((target.timeInMillis - today.timeInMillis) / 86_400_000.0).roundToLong()
    }

    private fun midnight(calendar: Calendar): Calendar = calendar.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** e.g. "Exam D-12", "D-Day", "D+3"; empty if no D-day is set. */
    fun label(prefs: Prefs): String {
        val date = parse(prefs.ddayDate) ?: return ""
        val days = daysUntil(date)
        val count = when {
            days > 0 -> "D-$days"
            days == 0L -> "D-Day"
            else -> "D+${-days}"
        }
        return listOf(prefs.ddayTitle.trim(), count).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
