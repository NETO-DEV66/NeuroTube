package com.neurotube.app.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The automatic import runs once per account. That decision is made from a remembered string, so the
 * string has to be stable for one person, different between two, and not the person's address:
 * preferences are not encrypted at rest.
 */
class AccountImportIdentityTest {
    private fun account(
        name: String = "Someone",
        email: String? = null,
        channelHandle: String? = null,
    ) = YouTubeAccount(
        name = name,
        email = email,
        channelHandle = channelHandle,
        thumbnailUrl = null,
        cookieHeader = "SID=secret",
    )

    @Test
    fun `the same account yields the same id`() {
        val account = account(email = "a@example.com")

        assertThat(account.identity()).isEqualTo(account.identity())
    }

    @Test
    fun `two accounts do not share an id`() {
        val first = account(email = "a@example.com")
        val second = account(email = "b@example.com")

        assertThat(first.identity()).isNotEqualTo(second.identity())
    }

    @Test
    fun `the id does not carry the address`() {
        val account = account(email = "a@example.com")

        assertThat(account.identity()).doesNotContain("a@example.com")
    }

    @Test
    fun `a blank email falls back to the channel handle`() {
        val byEmail = account(email = "", channelHandle = "@a")
        val byHandle = account(email = null, channelHandle = "@a")

        assertThat(byEmail.identity()).isEqualTo(byHandle.identity())
    }

    @Test
    fun `an account with neither still gets an id of its own`() {
        val first = account(name = "Someone")
        val second = account(name = "Someone Else")

        assertThat(first.identity()).isNotEmpty()
        assertThat(first.identity()).isNotEqualTo(second.identity())
    }
}
