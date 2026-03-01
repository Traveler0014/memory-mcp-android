package me.travon.memory.inference

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.math.sqrt

class EmbeddingEngine(private val context: Context) {

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null

    init {
        // Initialization can be called proactively
    }

    fun initialize() {
        if (environment != null && session != null && tokenizer != null) return

        environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        
        val modelBytes = context.assets.open("model_quantized.onnx").readBytes()
        if (modelBytes.isNotEmpty()) {
            session = environment?.createSession(modelBytes, options)
        }

        context.assets.open("tokenizer.json").use { inputStream ->
            tokenizer = HuggingFaceTokenizer.newInstance(inputStream, mapOf("padding" to "true", "truncation" to "true", "maxLength" to "512"))
        }
    }

    suspend fun getEmbedding(text: String): FloatArray = withContext(Dispatchers.Default) {
        val env = environment ?: throw IllegalStateException("Environment not initialized")
        val sess = session ?: throw IllegalStateException("Session not initialized")
        val tok = tokenizer ?: throw IllegalStateException("Tokenizer not initialized")

        // Preprocessing: trim and remove newlines
        val normalizedText = text.trim().replace("\\s+".toRegex(), " ")

        val encoding = tok.encode(normalizedText)
        val tokenIds = encoding.ids
        val attentionMask = encoding.attentionMask
        val tokenTypeIds = encoding.typeIds

        val shape = longArrayOf(1, tokenIds.size.toLong())
        
        val inputIdTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
        val typeIdTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)

        // The inputs depend on the exact ONNX export. Usually bge-small requires these.
        val inputs = mapOf(
            "input_ids" to inputIdTensor,
            "attention_mask" to maskTensor,
            "token_type_ids" to typeIdTensor
        )

        try {
            val result = sess.run(inputs)
            
            // Depending on the export, it might be the cls token (index 0) of the last_hidden_state
            // Let's assume the standard pooling: sentence embedding is the 0-th token output
            val outputTensor = result[0].value as Array<Array<FloatArray>>
            val embedding = outputTensor[0][0]

            return@withContext normalize(embedding)
        } finally {
            inputIdTensor.close()
            maskTensor.close()
            typeIdTensor.close()
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vector) {
            sumSq += v * v
        }
        val norm = sqrt(sumSq)
        if (norm > 0) {
            for (i in vector.indices) {
                vector[i] = vector[i] / norm
            }
        }
        return vector
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        require(v1.size == v2.size) { "Vectors must have the same dimension" }
        // Since embeddings are normalized, dot product = cosine similarity
        var dot = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
        }
        return dot
    }

    fun close() {
        session?.close()
        environment?.close()
        tokenizer?.close()
    }
}
