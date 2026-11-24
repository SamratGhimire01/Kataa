package app.kataa.core.nlu

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentDatesTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val now = LocalDateTime(2027, 1, 15, 10, 30).toInstant(tz).toEpochMilliseconds()
    private fun due(text: String, dayFirst: Boolean = true) = DocumentDates.find(text, now, tz, dayFirst)?.date

    @Test fun commonFormats() {
        assertEquals(LocalDate(2027, 2, 5), due("NEA Electricity Bill\nBill date: 01/01/2027\nDue date: 05/02/2027\nAmount Rs 2,340"))
        assertEquals(LocalDate(2027, 5, 2), due("Due date: 05/02/2027", dayFirst = false))
        assertEquals(LocalDate(2027, 8, 12), due("Policy valid until 12 Aug 2027"))
        assertEquals(LocalDate(2027, 8, 12), due("Valid till August 12, 2027"))
        assertEquals(LocalDate(2027, 8, 12), due("Expiry: 2027-08-12"))
        assertEquals(LocalDate(2027, 3, 1), due("Please pay by 1st March"))
        assertEquals(LocalDate(2029, 8, 31), due("VALID THRU 08/29"))
        assertEquals(LocalDate(2027, 1, 20), due("Assignment deadline: 20 Jan"))
    }

    @Test fun ignoresDatesWithoutDeadlineWordsAndPastDates() {
        assertNull(due("Invoice date 12/03/2027. Thank you for shopping."))
        assertNull(due("Date of birth: 14/03/1995"))
        assertNull(due("Due date: 01/01/2026")) // already past
        // Picks the soonest upcoming deadline.
        assertEquals(LocalDate(2027, 2, 1), due("Renew by 01/03/2027. Payment due 01/02/2027"))
    }

    @Test fun remindsAFewDaysBeforeAtNine() {
        val d = DocumentDates.find("Due date: 05/02/2027", now, tz)!!
        val at = Instant.fromEpochMilliseconds(d.remindAtMs).toLocalDateTime(tz)
        assertEquals(LocalDateTime(2027, 2, 2, 9, 0), at)
        assertEquals("Due 5 Feb 2027", DocumentDates.describe(d))
        // Due tomorrow: remind on the day itself at 9.
        val soon = DocumentDates.find("Due date: 16/01/2027", now, tz)!!
        assertEquals(LocalDateTime(2027, 1, 16, 9, 0), Instant.fromEpochMilliseconds(soon.remindAtMs).toLocalDateTime(tz))
    }
}
