package com.gng.core

object InviteCodes {
    const val LENGTH = 6

    /** The web page invite links and QR codes point at; it opens gng (or offers the download). */
    const val LINK_BASE = "https://krishbnsl.github.io/gng/join/"

    /** Accepts "abc 123", "ABC-123", "gng:abc123" and returns "ABC123", or null if it can't be a code. */
    fun normalize(input: String): String? {
        val cleaned = input.trim().uppercase().removePrefix("GNG:").filter { it.isLetterOrDigit() }
        return cleaned.takeIf { it.length == LENGTH }
    }

    /** The link (and QR code contents) for joining with [code]. */
    fun link(code: String): String = "$LINK_BASE?c=$code"

    /**
     * The code in anything that might hold one: an invite link (https://…/join/?c=ABC123),
     * the app link (gng://join/ABC123), or a plain code. Null if there isn't one.
     */
    fun fromScan(text: String): String? {
        val t = text.trim()
        Regex("""[?&]c=([A-Za-z0-9-]+)""").find(t)?.let { return normalize(it.groupValues[1]) }
        Regex("""^gng://join/([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE).find(t)?.let { return normalize(it.groupValues[1]) }
        if ("://" in t) return null // some other link: not ours
        return normalize(t)
    }
}
