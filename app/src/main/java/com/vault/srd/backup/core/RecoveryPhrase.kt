package com.vault.srd.backup.core

import java.security.SecureRandom

/**
 * Deterministic 2048-word list generator and recovery phrase helper.
 * The list is generated from fixed syllables to avoid bundling large assets.
 */
object RecoveryPhrase {
    private val WORDS: List<String> by lazy { buildWords() }

    fun generate(wordCount: Int = 12): String {
        val random = SecureRandom()
        val words = ArrayList<String>(wordCount)
        repeat(wordCount) {
            words.add(WORDS[random.nextInt(WORDS.size)])
        }
        return words.joinToString(" ")
    }

    fun normalize(phrase: String): String {
        return phrase
            .lowercase()
            .trim()
            .split(Regex("\\s+"))
            .joinToString(" ")
    }

    private fun buildWords(): List<String> {
        val onsets = listOf(
            "b", "c", "d", "f", "g", "h", "j", "k",
            "l", "m", "n", "p", "r", "s", "t", "v",
            "z", "br", "cr", "dr", "fr", "gr", "kr", "pr",
            "tr", "vr", "bl", "cl", "fl", "gl", "pl", "sl"
        )
        val vowels = listOf("a", "e", "i", "o", "u", "y", "ae", "ai")
        val endings = listOf("", "n", "m", "l", "r", "s", "t", "k")
        val words = ArrayList<String>(onsets.size * vowels.size * endings.size)
        for (onset in onsets) {
            for (vowel in vowels) {
                for (ending in endings) {
                    words.add(onset + vowel + ending)
                }
            }
        }
        if (words.size != 2048) {
            throw IllegalStateException("Recovery phrase word list must contain 2048 words")
        }
        return words
    }
}
