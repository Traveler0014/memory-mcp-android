package me.travon.memory.inference

import android.content.Context
import org.json.JSONObject

/**
 * A pure Kotlin BERT WordPiece tokenizer that reads from a HuggingFace tokenizer.json file.
 * No native libraries required — works on all Android architectures.
 */
class BertTokenizer(context: Context) {

    private val vocab: Map<String, Int>
    private val clsTokenId: Int
    private val sepTokenId: Int
    private val unkTokenId: Int
    private val padTokenId: Int
    private val maxLength: Int = 512

    init {
        val json = context.assets.open("tokenizer.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        
        // Parse vocabulary from tokenizer.json model.vocab
        val model = root.getJSONObject("model")
        val vocabJson = model.getJSONObject("vocab")
        val vocabMap = mutableMapOf<String, Int>()
        val keys = vocabJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            vocabMap[key] = vocabJson.getInt(key)
        }
        vocab = vocabMap
        
        clsTokenId = vocab["[CLS]"] ?: 101
        sepTokenId = vocab["[SEP]"] ?: 102
        unkTokenId = vocab["[UNK]"] ?: 100
        padTokenId = vocab["[PAD]"] ?: 0
    }

    data class TokenizerOutput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    fun encode(text: String): TokenizerOutput {
        val tokens = tokenize(text)
        
        // Add [CLS] and [SEP]
        val ids = mutableListOf<Long>()
        ids.add(clsTokenId.toLong())
        for (token in tokens) {
            ids.add((vocab[token] ?: unkTokenId).toLong())
        }
        ids.add(sepTokenId.toLong())
        
        // Truncate to maxLength
        val truncatedIds = if (ids.size > maxLength) ids.subList(0, maxLength) else ids
        
        val inputIds = truncatedIds.toLongArray()
        val attentionMask = LongArray(inputIds.size) { 1L }
        val tokenTypeIds = LongArray(inputIds.size) { 0L }
        
        return TokenizerOutput(inputIds, attentionMask, tokenTypeIds)
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        // Basic tokenization: split on whitespace and punctuation, then WordPiece
        val basicTokens = basicTokenize(text)
        for (token in basicTokens) {
            tokens.addAll(wordPieceTokenize(token))
        }
        return tokens
    }

    private fun basicTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                }
                isChinese(ch) -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                    result.add(ch.toString())
                }
                isPunctuation(ch) -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                    result.add(ch.toString())
                }
                else -> {
                    sb.append(ch.lowercaseChar())
                }
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }

    private fun wordPieceTokenize(token: String): List<String> {
        if (token.length > 200) return listOf("[UNK]")
        
        val subTokens = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found = false
            while (start < end) {
                val substr = if (start > 0) "##${token.substring(start, end)}" else token.substring(start, end)
                if (vocab.containsKey(substr)) {
                    subTokens.add(substr)
                    found = true
                    break
                }
                end--
            }
            if (!found) {
                subTokens.add("[UNK]")
                break
            }
            start = end
        }
        return subTokens
    }

    private fun isChinese(ch: Char): Boolean {
        val cp = ch.code
        return (cp in 0x4E00..0x9FFF) ||
                (cp in 0x3400..0x4DBF) ||
                (cp in 0x20000..0x2A6DF) ||
                (cp in 0x2A700..0x2B73F) ||
                (cp in 0x2B740..0x2B81F) ||
                (cp in 0x2B820..0x2CEAF) ||
                (cp in 0xF900..0xFAFF) ||
                (cp in 0x2F800..0x2FA1F)
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return Character.getType(ch).toByte().toInt().let {
            it == Character.CONNECTOR_PUNCTUATION.toInt() ||
            it == Character.DASH_PUNCTUATION.toInt() ||
            it == Character.END_PUNCTUATION.toInt() ||
            it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            it == Character.OTHER_PUNCTUATION.toInt() ||
            it == Character.START_PUNCTUATION.toInt()
        }
    }
}
