package app.kataa.core.nlu

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Times as speech recognizers actually write them (24 Nov 2026 phone test: everything came out 9 AM). */
class SpokenTimesTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val now = LocalDateTime(2026, 11, 24, 7, 0).toInstant(tz).toEpochMilliseconds()

    private fun at(s: String) = TimeParser.parse(Text.normalize(s), now, tz)?.fireAt?.toLocalDateTime(tz)?.toString()

    @Test fun spokenTimes() {
        val cases = mapOf(
            "remind me to call mom at 2 p.m." to "2026-11-24T14:00",
            "remind me to call mom at two pm" to "2026-11-24T14:00",
            "remind me tomorrow at two" to "2026-11-25T14:00",
            "two thirty pm" to "2026-11-24T14:30",
            "remind me to call mom at 6 30 pm" to "2026-11-24T18:30",
            "remind me half past 4 tomorrow" to "2026-11-25T16:30",
            "remind me to pay rent after 20 days at 2 pm" to "2026-12-14T14:00",
            "remind me to call mom 20 days later at 2 pm" to "2026-12-14T14:00",
            "remind me in 20 days at 2 in the afternoon" to "2026-12-14T14:00",
            "remind me after 2 weeks at 9 in the morning" to "2026-12-08T09:00",
            "remind me in 3 days at 10 am to pay rent" to "2026-11-27T10:00",
            "remind me to call mom tomorrow evening at 7" to "2026-11-25T19:00",
            "remind me to call mom at 7 tonight" to "2026-11-24T19:00",
            "remind me to call mom in the evening" to "2026-11-24T18:00",
            "remind me to call mom after lunch tomorrow" to "2026-11-25T14:00",
            "take medicine after dinner" to "2026-11-24T21:00",
            "remind me to call mom on December 5th at 3:15 pm" to "2026-12-05T15:15",
            "remind me to call mom at 11 a.m. tomorrow" to "2026-11-25T11:00",
        )
        for ((said, expected) in cases) assertEquals(expected, at(said), said)
    }
}
