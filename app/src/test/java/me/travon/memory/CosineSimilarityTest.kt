package me.travon.memory

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class CosineSimilarityTest {

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        require(v1.size == v2.size)
        var dot = 0f
        var norm1Sq = 0f
        var norm2Sq = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1Sq += v1[i] * v1[i]
            norm2Sq += v2[i] * v2[i]
        }
        val norm1 = Math.sqrt(norm1Sq.toDouble()).toFloat()
        val norm2 = Math.sqrt(norm2Sq.toDouble()).toFloat()
        return if (norm1 > 0 && norm2 > 0) {
            dot / (norm1 * norm2)
        } else {
            0f
        }
    }

    @Test
    fun testCosineSimilarity_identicalVectors() {
        val v1 = floatArrayOf(1f, 2f, 3f)
        val v2 = floatArrayOf(1f, 2f, 3f)
        val result = cosineSimilarity(v1, v2)
        assertEquals(1.0f, result, 0.0001f)
    }

    @Test
    fun testCosineSimilarity_orthogonalVectors() {
        val v1 = floatArrayOf(1f, 0f)
        val v2 = floatArrayOf(0f, 1f)
        val result = cosineSimilarity(v1, v2)
        assertEquals(0.0f, result, 0.0001f)
    }

    @Test
    fun testCosineSimilarity_oppositeVectors() {
        val v1 = floatArrayOf(1f, 1f)
        val v2 = floatArrayOf(-1f, -1f)
        val result = cosineSimilarity(v1, v2)
        assertEquals(-1.0f, result, 0.0001f)
    }
}
