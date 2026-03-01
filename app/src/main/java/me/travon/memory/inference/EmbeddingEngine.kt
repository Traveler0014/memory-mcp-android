package me.travon.memory.inference

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
    private var tokenizer: BertTokenizer? = null

    @get:Synchronized
    var isInitialized: Boolean = false
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        try {
            environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            
            // Copy model files from assets to internal storage so ONNX Runtime
            // can locate the external data file (model_quantized.onnx_data)
            val modelDir = java.io.File(context.filesDir, "model")
            modelDir.mkdirs()
            
            val modelFile = java.io.File(modelDir, "model_quantized.onnx")
            val dataFile = java.io.File(modelDir, "model_quantized.onnx_data")
            
            if (!modelFile.exists()) {
                context.assets.open("model_quantized.onnx").use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (!dataFile.exists()) {
                context.assets.open("model_quantized.onnx_data").use { input ->
                    dataFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            
            session = environment?.createSession(modelFile.absolutePath, options)

            // Pure Kotlin tokenizer — no native libs needed
            tokenizer = BertTokenizer(context)
            isInitialized = true
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingEngine", "Failed to initialize: ${e.message}", e)
            throw e
        }
    }

    suspend fun getEmbedding(text: String): FloatArray = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            initialize()
        }
        
        val env = environment!!
        val sess = session!!
        val tok = tokenizer!!

        // Preprocessing: trim and remove newlines
        val normalizedText = text.trim().replace("\\s+".toRegex(), " ")

        val encoding = tok.encode(normalizedText)

        val shape = longArrayOf(1, encoding.inputIds.size.toLong())
        
        val inputIdTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.inputIds), shape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.attentionMask), shape)
        val typeIdTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.tokenTypeIds), shape)

        val inputs = mapOf(
            "input_ids" to inputIdTensor,
            "attention_mask" to maskTensor,
            "token_type_ids" to typeIdTensor
        )

        try {
            val result = sess.run(inputs)
            
            // Output shape: [1, seq_len, hidden_size] — take CLS token (index 0)
            @Suppress("UNCHECKED_CAST")
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
        var dot = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
        }
        return dot
    }

    fun close() {
        session?.close()
        environment?.close()
    }
}
