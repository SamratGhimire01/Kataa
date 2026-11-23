package app.kataa.core.util

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.Uuid

/** Time source, injectable so tests can control "now". */
fun interface TimeSource {
    fun nowMs(): Long

    companion object {
        @OptIn(ExperimentalTime::class)
        val System: TimeSource = TimeSource { Clock.System.now().toEpochMilliseconds() }
    }
}

fun interface IdGenerator {
    fun newUuid(): String

    companion object {
        val Random: IdGenerator = IdGenerator { Uuid.random().toString() }
    }
}

const val DAY_MS: Long = 24L * 60 * 60 * 1000
