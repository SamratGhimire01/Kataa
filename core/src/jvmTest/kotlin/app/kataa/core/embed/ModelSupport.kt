package app.kataa.core.embed

import java.io.File

/** The real model, if scripts/fetch-model.sh has run (CI runs it). Tests that need it are skipped otherwise. */
object RealModel {
    val dir: File? = System.getProperty("kataa.modelDir")?.let(::File)?.takeIf { File(it, "model_quantized.onnx").exists() }
    val embedder: BertEmbedder? by lazy { dir?.let { Models.bgeSmall(it) } }
}
