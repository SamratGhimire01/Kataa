package app.kataa.core

import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import app.kataa.core.assistant.ReplyText
import app.kataa.core.embed.RealModel
import app.kataa.core.mind.LlamaModel
import app.kataa.core.mind.LlamaNative
import app.kataa.core.mind.Mind
import app.kataa.core.model.GeoPoint
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * A day of real use, end to end, printed as a table (sentence -> reply, kind, ms), with and without the AI.
 * Opt-in: KATAA_SIM=1 ./gradlew :core:jvmTest --tests '*SimulationTest*'. Output: core/build/sim-*.txt
 */
class SimulationTest {
    private val tz = TimeZone.of("Asia/Kathmandu")

    private val script = listOf(
        // passwords and secrets
        "My Facebook password is Samrat 001", "facebook password", "what is my facebook password",
        "Write the password of wifi sunflower123", "remember the wifi password is sunflower123", "wifi password",
        "Save my Instagram password samrat@123", "my gmail password is Kathmandu#45", "change my facebook password to Samrat 002",
        "facebook password", "what's the password for wifi", "my atm pin is 4455", "atm pin", "netflix password is nf2026",
        "show me all my passwords",
        // objects
        "My keys are in the blue drawer", "I kept my passport in the top shelf of the almirah", "where is my passport",
        "where did I keep my keys", "I parked the bike on the 2nd floor near the lift", "where did I park",
        "the charger is in my office bag", "where's my charger", "my glasses are on the dining table", "glasses",
        // reminders, times
        "remind me to call mom at 6 30 pm", "remind me to pay rent after 20 days at 2 pm", "remind me to take medicine after dinner",
        "call dad", "tomorrow morning", "remind me to submit the assignment on Friday at 10", "remind me in 10 minutes to check the rice",
        "wake me up at 5 am", "remind me every day at 8 pm to take vitamins", "remind me to pay electricity bill on 5th december",
        "remind me tonight to lock the door", "remind me next week to renew the license", "set an alarm for 7",
        "I have to buy eggs tomorrow", "don't forget to water the plants this evening",
        // places
        "remind me to buy milk at Big Mart", "remind me to buy medicine at any pharmacy", "I'm at Big Mart",
        "remind me to withdraw money when I reach the atm", "remind me to call mom when I get home", "this is home",
        "remind me to buy eggs at Bhatbhateni", "save this place as office", "remind me when I reach office to send the report",
        // questions / misc
        "what do I have to do today", "what are my reminders", "did I take my medicine today", "I took my medicine",
        "did I take my medicine today", "undo", "ok thanks", "hello", "what is my mom's birthday", "mom's birthday is 12 march",
        "when is mom's birthday", "my bike number is BA 12 PA 3456", "what is my bike number", "Ram owes me 500 rupees",
        "who owes me money", "my blood group is O positive", "blood group",
        // messy / misheard
        "call more", "give me the facebook passport", "ugh where did i leave the stupid charger again",
        "I have to submit the assignment the day after tomorrow and my bike is parked near the gate",
        "uh remind me uh to um call didi at like 7", "remind me to call to mom at 9", "remind to buy milk",
    )

    @Test fun day() {
        if (System.getenv("KATAA_SIM") != "1") return
        val lib = System.getProperty("kataa.llmLib").orEmpty()
        val gguf = System.getProperty("kataa.llmModel").orEmpty()
        run("rules", null)
        if (lib.isNotEmpty() && File(gguf).exists()) {
            LlamaNative.load(lib)
            LlamaModel.open(gguf, threads = 4, fast = false)?.use { run("ai", Mind(it).also { m -> m.warmUp(); m.budgetMs = 0 }) }
        }
    }

    private fun run(name: String, mind: Mind?) {
        val clock = FakeClock(LocalDateTime(2026, 11, 24, 10, 45).toInstant(tz).toEpochMilliseconds())
        val core = RealModel.embedder?.takeIf { System.getenv("KATAA_SIM_NOEMBED") == null }?.let { newCore(clock, it, minAnswerSimilarity = 0.62) } ?: newCore(clock)
        File("../app/src/main/assets/places/nepal.tsv.gz").takeIf { it.exists() }?.let { f ->
            GZIPInputStream(f.inputStream()).bufferedReader().useLines { core.places.importPlacesPack("sim", it) }
        }
        core.assistant.mind = mind
        val home = GeoPoint(27.7237, 85.3312, 10.0)
        var last: Reply? = null
        val out = StringBuilder()
        for (s in script) {
            val at = if (s.startsWith("I'm at") || s.startsWith("this is") || s.startsWith("save this place")) home else null
            val ctx = AssistantContext(clock.now, tz, location = at, privateUnlocked = false)
            val t0 = System.nanoTime()
            val awaiting = last?.takeIf { it is Reply.NeedsTime || it is Reply.NeedsPlace }
            val r = if (awaiting != null) core.assistant.followUp(awaiting, s, ctx) else core.assistant.handle(s, ctx)
            core.processPending()
            val ms = (System.nanoTime() - t0) / 1_000_000
            last = r
            out.append("%-6d %-22s %-75s -> %s\n".format(ms, r::class.simpleName, "\"$s\"", ReplyText.of(r).replace("\n", " ")))
            clock.now += 60_000
        }
        File("build/sim-$name.txt").writeText(out.toString())
        println(out)
    }
}
