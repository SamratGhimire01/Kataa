package app.kataa.core.mind

import app.kataa.core.embed.RealModel
import kotlin.test.Test

/** Diagnoses the AI going wrong when the search model (ONNX Runtime) runs in the same process. Opt-in. */
class MindWithOnnxTest {
    @Test fun diag() {
        if (System.getenv("KATAA_DIAG") != "1") return
        val lib = System.getProperty("kataa.llmLib").orEmpty(); val gguf = System.getProperty("kataa.llmModel").orEmpty()
        LlamaNative.load(lib)
        val model = LlamaModel.open(gguf, threads = System.getenv("KATAA_DIAG_THREADS")?.toInt() ?: 4, fast = System.getenv("KATAA_DIAG_SLOW") == null)!!
        val mind = Mind(model)
        val notes = listOf("My blood group is O positive", "Ram owes me 500 rupees", "Call mom")
        fun ask(tag: String) { val t = System.nanoTime(); println("DIAG %-28s %5d ms %s".format(tag, (System.nanoTime() - t) / 1, mind.pickAnswer("blood group", notes).toString() + " " + (System.nanoTime() - t) / 1_000_000 + "ms")) }
        ask("before onnx")
        val emb = RealModel.embedder!!
        val other = Thread { emb.embed("hello world") }.apply { start(); join() }
        ask("after onnx on other thread")
        emb.embed("hello world")
        ask("after onnx on same thread")
        ask("again")
        val qs = listOf("glasses", "where did I park", "who owes me money", "blood group", "what is my bike number")
        val more = notes + listOf("My glasses are on the dining table", "I parked the bike on the 2nd floor", "my bike number is BA 12 PA 3456")
        for (round in 1..4) for (q in qs) {
            if (System.getenv("KATAA_DIAG_NOEMB") == null) repeat(3) { emb.embedQuery(q + it) }
            val list = more.shuffled(java.util.Random(round.toLong()))
            val raw = model.complete(System.getenv("KATAA_DIAG_SLOT")?.toInt() ?: 1, Mind.PICK_SYSTEM, "Question: $q\nNotes:\n" + list.take(5).mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n"), null, 40)
            println("DIAG raw ${raw?.replace("\n", "⏎")}")
            val t = System.nanoTime(); val p = mind.pickAnswer(q, list)
            println("DIAG r$round %-22s %5d ms %s".format(q, (System.nanoTime() - t) / 1_000_000, p))
        }
        model.close()
    }
}
