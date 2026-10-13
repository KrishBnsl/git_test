package com.gng.core

object InviteCodes {
    const val LENGTH = 6

    /** Accepts "abc 123", "ABC-123", "gng:abc123" and returns "ABC123", or null if it can't be a code. */
    fun normalize(input: String): String? {
        val cleaned = input.trim().uppercase().removePrefix("GNG:").filter { it.isLetterOrDigit() }
        return cleaned.takeIf { it.length == LENGTH }
    }
}
