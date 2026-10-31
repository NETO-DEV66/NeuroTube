package com.neurotube.app.innertube.pages

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main site's own pages — the account's playlists and its watch history — are read from live
 * captures, because they are served by the web client rather than the music client and render their
 * rows as lockups, the shape that the music library's readers walk straight past.
 */
class MainSiteFeedPageTest {
    private fun page(name: String): MainSiteFeedPage {
        val text =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("music/$name")) {
                "missing fixture $name"
            }.bufferedReader().readText()
        return Json.parseToJsonElement(text).toMainSiteFeedPage()
    }

    @Test
    fun `the playlists page yields one playlist per lockup row`() {
        val playlists = page("account_playlists.json").playlists

        assertEquals(4, playlists.size)
        assertTrue(playlists.all { it.id.isNotBlank() && it.title.isNotBlank() })
        assertEquals("PLU90_7drIu_Y", playlists.first().id)
    }

    @Test
    fun `the playlists page carries its next token`() {
        assertEquals("NEXT_PAGE", page("account_playlists.json").continuation)
    }

    @Test
    fun `the history page yields one video per lockup row`() {
        val videos = page("account_history.json").videos

        assertEquals(4, videos.size)
        assertTrue("a history row must keep its video id", videos.all { it.id.isNotBlank() })
        assertEquals("pvSdeU13hKc", videos.first().id)
        assertTrue("a history row must keep its title", videos.all { it.title.isNotBlank() })
    }

    @Test
    fun `the history page carries its next token`() {
        assertEquals("NEXT_PAGE", page("account_history.json").continuation)
    }

    @Test
    fun `a playlist row that repeats is only kept once`() {
        val once = page("account_playlists.json").playlists
        assertNotNull(once.firstOrNull())
        assertEquals(once.map { it.id }.distinct(), once.map { it.id })
    }

    @Test
    fun `the account's own home feed yields one video per grid row`() {
        val page = page("account_home_feed.json")

        assertEquals(4, page.videos.size)
        assertTrue("a home row must keep its video id", page.videos.all { it.id.isNotBlank() })
        assertEquals("pvSdeU13hKc", page.videos.first().id)
        assertTrue("a home row must keep its title", page.videos.all { it.title.isNotBlank() })
    }

    @Test
    fun `the account's own home feed carries its next token`() {
        assertEquals("NEXT_PAGE", page("account_home_feed.json").continuation)
    }

    @Test
    fun `a home row keeps the age it was uploaded, so the freshness filter can read it`() {
        val first = page("account_home_feed.json").videos.first()

        assertTrue("an upload date is what the freshness filter reads", first.uploadDate.isNotBlank())
        assertTrue("and a timestamp backs it up", first.timestamp > 0L)
    }
}
