package com.gng.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {
    @Test
    fun cleansGitHubGeneratedNotes() {
        val body = """
            ## What's Changed
            * Make migration 0002 safe to re-run by @KrishBnsl in https://github.com/KrishBnsl/git_test/pull/3
            * Add a live map and **group settings** by @KrishBnsl in https://github.com/KrishBnsl/git_test/pull/4

            **Full Changelog**: https://github.com/KrishBnsl/git_test/compare/v0.2.0...v0.2.1
        """.trimIndent()
        assertEquals(
            listOf("Make migration 0002 safe to re-run", "Add a live map and group settings"),
            ReleaseNotes.bullets(body),
        )
    }

    @Test
    fun handlesHandWrittenNotesAndLinks() {
        assertEquals(listOf("Fixes the [bug]"), ReleaseNotes.bullets("- Fixes the [bug]"))
        assertEquals(listOf("See the guide"), ReleaseNotes.bullets("- See [the guide](https://example.com)"))
    }

    @Test
    fun emptyAndMissingBodies() {
        assertEquals(emptyList(), ReleaseNotes.bullets(null))
        assertEquals(emptyList(), ReleaseNotes.bullets(""))
        assertEquals(emptyList(), ReleaseNotes.bullets("Just a sentence, no bullets."))
    }

    @Test
    fun capsTheListLength() {
        val body = (1..20).joinToString("\n") { "* Change $it" }
        assertEquals(8, ReleaseNotes.bullets(body).size)
        assertEquals(3, ReleaseNotes.bullets(body, max = 3).size)
    }
}
