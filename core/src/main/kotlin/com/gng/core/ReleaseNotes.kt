package com.gng.core

/** Turns the markdown on a GitHub release into short plain bullets for the "What's new" list. */
object ReleaseNotes {
    private val byUserIn = Regex("""\s+by\s+@[\w-]+\s+in\s+\S+$""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")

    fun bullets(body: String?, max: Int = 8): List<String> =
        body.orEmpty().lines()
            .map { it.trim() }
            .filter { it.startsWith("* ") || it.startsWith("- ") }
            .map { it.drop(2).replace(byUserIn, "").replace(link, "$1").replace("**", "").trim() }
            .filter { it.isNotEmpty() }
            .take(max)
}
