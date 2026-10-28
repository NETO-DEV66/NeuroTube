package com.neurotube.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A saved session has to come back whole. The visitor identity is the half that is easy to drop and
 * expensive to lose: InnerTube validates the request context against it, so a cookie restored
 * without one is refused and the app looks signed out after every restart.
 *
 * The file now holds a list plus the active id. The single-account shape an older build wrote must
 * still load, or upgrading would sign the user out.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class YouTubeAccountCodecTest {
    private fun account(
        name: String = "Test Channel",
        email: String? = "test@example.com",
        cookie: String = "SAPISID=mno; SID=abc",
    ) = YouTubeAccount(
        name = name,
        email = email,
        channelHandle = "@test",
        thumbnailUrl = "https://example.com/a.jpg",
        cookieHeader = cookie,
        visitorData = "CgtWSVNJVE9S",
        dataSyncId = "ABC123",
    )

    @Test
    fun `a session round trips with its visitor identity`() {
        val stored = StoredAccounts(listOf(account()), account().identity())

        assertThat(YouTubeAccountCodec.decode(YouTubeAccountCodec.encode(stored))).isEqualTo(stored)
    }

    @Test
    fun `absent fields stay absent rather than becoming empty strings`() {
        val account =
            YouTubeAccount(
                name = "Test Channel",
                email = null,
                channelHandle = null,
                thumbnailUrl = null,
                cookieHeader = "SAPISID=mno",
            )

        val decoded = YouTubeAccountCodec.decode(YouTubeAccountCodec.encode(StoredAccounts(listOf(account), account.identity())))

        assertThat(decoded.accounts).containsExactly(account)
        assertThat(decoded.accounts.single().visitorData).isNull()
        assertThat(decoded.accounts.single().dataSyncId).isNull()
        assertThat(decoded.accounts.single().email).isNull()
    }

    @Test
    fun `a payload written before the visitor identity existed still loads`() {
        val legacy = """{"name":"Old","cookie":"SAPISID=mno"}""".toByteArray(Charsets.UTF_8)

        val decoded = YouTubeAccountCodec.decode(legacy)

        // The single-account shape becomes a one-entry list whose only account is active.
        assertThat(decoded.accounts.single().name).isEqualTo("Old")
        assertThat(decoded.accounts.single().cookieHeader).isEqualTo("SAPISID=mno")
        assertThat(decoded.accounts.single().visitorData).isNull()
        assertThat(decoded.accounts.single().dataSyncId).isNull()
        assertThat(decoded.activeId).isEqualTo(decoded.accounts.single().identity())
    }

    @Test
    fun `every account and the active id survive a round trip`() {
        val first = account(name = "First", email = "first@example.com")
        val second = account(name = "Second", email = "second@example.com")
        val third = account(name = "Third", email = null)
        val stored = StoredAccounts(listOf(first, second, third), activeId = second.identity())

        val decoded = YouTubeAccountCodec.decode(YouTubeAccountCodec.encode(stored))

        assertThat(decoded.accounts).containsExactly(first, second, third).inOrder()
        assertThat(decoded.activeId).isEqualTo(second.identity())
        assertThat(decoded.active()).isEqualTo(second)
    }

    @Test
    fun `an empty list keeps no active account`() {
        val decoded = YouTubeAccountCodec.decode(YouTubeAccountCodec.encode(StoredAccounts(emptyList(), null)))

        assertThat(decoded.accounts).isEmpty()
        assertThat(decoded.activeId).isNull()
        assertThat(decoded.active()).isNull()
    }

    @Test
    fun `an account with a blank name is dropped rather than crashing the decode`() {
        val json =
            """{"active_id":"x","accounts":[{"name":"Good","cookie":"SID=a"},{"cookie":"SID=b"}]}"""
                .toByteArray(Charsets.UTF_8)

        val decoded = runCatching { YouTubeAccountCodec.decode(json) }.getOrElse { StoredAccounts(emptyList(), null) }

        // A malformed entry must not take the whole file down; what decodes is kept.
        assertThat(decoded.accounts.map { it.name }).contains("Good")
    }
}
