package com.aiforum.tier0

import com.aiforum.github.GhAuthor
import com.aiforum.web.AuthorLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: author-id display mapping. A GitHub author (ingested PR comment, "gh:<login>") renders as @login
 * with the login's own initials; everyone else (persona / owner / system) renders verbatim. The stored id
 * stays raw on the data-author hook — this only governs the visible name + monogram.
 */
@Tag("tier0")
class AuthorLabelTest {

    @Test
    fun `GhAuthor builds and round-trips a gh login id`() {
        assertEquals("gh:octocat", GhAuthor.id("octocat"))
        assertTrue(GhAuthor.isGitHub("gh:octocat"))
        assertEquals("octocat", GhAuthor.login("gh:octocat"))
        assertFalse(GhAuthor.isGitHub("Sol"))
        assertEquals("Sol", GhAuthor.login("Sol"), "a non-gh id is returned unchanged")
    }

    @Test
    fun `a github author renders as @login with the login's initials`() {
        val id = GhAuthor.id("octocat")
        assertEquals("@octocat", AuthorLabel.display(id))
        assertEquals("OC", AuthorLabel.monogram(id))
    }

    @Test
    fun `a persona renders verbatim with its name's initials`() {
        assertEquals("Sol", AuthorLabel.display("Sol"))
        assertEquals("SO", AuthorLabel.monogram("Sol"))
    }

    @Test
    fun `owner and system are unchanged`() {
        assertEquals("owner", AuthorLabel.display("owner"))
        assertEquals("OW", AuthorLabel.monogram("owner"))
        assertEquals("system", AuthorLabel.display("system"))
    }
}
