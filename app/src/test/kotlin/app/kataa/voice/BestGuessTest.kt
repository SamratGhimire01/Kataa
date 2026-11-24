package app.kataa.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** The recognizer's five guesses: a later one wins only when it is made of much more likely words. */
class BestGuessTest {
    @Test fun keepsTheTopGuessByDefault() {
        assertEquals("remind me to call mom", AndroidSpeechEngine.best(listOf("remind me to call mom", "remind me to call tom")))
    }

    @Test fun aLikelierGuessWins() {
        assertEquals("my keys are in the drawer", AndroidSpeechEngine.best(listOf("mike ease are in the drawer", "my keys are in the drawer")))
    }

    @Test fun theFifthGuessCounts() {
        val guesses = listOf("pie wallet is under bed", "pie wallet is under bad", "pie wallet is under bid", "pie wallet is under bud", "my wallet is under bed")
        assertEquals("my wallet is under bed", AndroidSpeechEngine.best(guesses))
    }

    @Test fun nothingHeard() = assertEquals("", AndroidSpeechEngine.best(emptyList()))
}
