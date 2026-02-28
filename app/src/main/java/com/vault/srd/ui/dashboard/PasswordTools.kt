package com.vault.srd.ui.dashboard

import kotlin.random.Random

enum class PasswordStrengthLabel {
    WEAK,
    FAIR,
    STRONG,
    VERY_STRONG
}

data class PasswordStrengthResult(
    val score: Int,
    val label: PasswordStrengthLabel
)

object PasswordTools {
    private val commonWords = listOf(
        "password", "qwerty", "admin", "letmein", "welcome", "vault", "secret"
    )
    private val keyboardWalks = listOf(
        "123456", "qwerty", "asdfgh", "zxcvbn", "098765", "poiuyt"
    )

    fun evaluate(password: String): PasswordStrengthResult {
        if (password.isBlank()) return PasswordStrengthResult(0, PasswordStrengthLabel.WEAK)
        val lower = password.lowercase()
        var score = 0

        when {
            password.length >= 16 -> score += 3
            password.length >= 12 -> score += 2
            password.length >= 8 -> score += 1
        }
        if (password.any { it.isLowerCase() }) score += 1
        if (password.any { it.isUpperCase() }) score += 1
        if (password.any { it.isDigit() }) score += 1
        if (password.any { !it.isLetterOrDigit() }) score += 1
        if (password.toSet().size <= (password.length / 2)) score -= 1
        if (commonWords.any { lower.contains(it) }) score -= 2
        if (keyboardWalks.any { lower.contains(it) }) score -= 2
        if (Regex("(.)\\1{2,}").containsMatchIn(password)) score -= 1

        val normalized = score.coerceIn(0, 8)
        val label = when {
            password.length < 12 -> PasswordStrengthLabel.WEAK
            password.length >= 16 && normalized <= 4 -> PasswordStrengthLabel.STRONG
            normalized <= 2 -> PasswordStrengthLabel.WEAK
            normalized <= 4 -> PasswordStrengthLabel.FAIR
            normalized <= 6 -> PasswordStrengthLabel.STRONG
            else -> PasswordStrengthLabel.VERY_STRONG
        }
        return PasswordStrengthResult(normalized, label)
    }

    fun generate(
        length: Int,
        includeUppercase: Boolean,
        includeLowercase: Boolean,
        includeDigits: Boolean,
        includeSymbols: Boolean,
        excludeAmbiguous: Boolean
    ): String {
        val effectiveLength = length.coerceIn(8, 64)
        val upper = if (excludeAmbiguous) "ABCDEFGHJKLMNPQRSTUVWXYZ" else "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lower = if (excludeAmbiguous) "abcdefghjkmnpqrstuvwxyz" else "abcdefghijklmnopqrstuvwxyz"
        val digits = if (excludeAmbiguous) "23456789" else "0123456789"
        val symbols = "#$%&*+-_=!?@"

        val pools = buildList {
            if (includeUppercase) add(upper)
            if (includeLowercase) add(lower)
            if (includeDigits) add(digits)
            if (includeSymbols) add(symbols)
        }.ifEmpty { listOf(upper, lower, digits) }

        val allChars = pools.joinToString("")
        val out = StringBuilder(effectiveLength)

        // Guarantee at least one char from each selected pool.
        pools.forEach { pool ->
            out.append(pool[Random.nextInt(pool.length)])
        }
        while (out.length < effectiveLength) {
            out.append(allChars[Random.nextInt(allChars.length)])
        }

        // Shuffle result.
        return out.toString().toCharArray().apply { shuffle() }.concatToString()
    }

    private fun CharArray.shuffle() {
        for (i in indices.reversed()) {
            val j = Random.nextInt(i + 1)
            val tmp = this[i]
            this[i] = this[j]
            this[j] = tmp
        }
    }
}
