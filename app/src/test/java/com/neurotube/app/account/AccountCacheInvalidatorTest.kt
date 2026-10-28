package com.neurotube.app.account

import com.neurotube.app.data.local.dao.CacheDao
import com.neurotube.app.data.local.dao.HomeFeedCacheDao
import com.neurotube.app.data.recommendation.music.graph.MusicGraphStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Switching account must not leave the previous owner's feed on screen. The library tables are
 * scoped by account, but these caches are not, so a missed clear here shows account A's home feed
 * and music recommendations to account B.
 */
class AccountCacheInvalidatorTest {
    private val homeFeedCacheDao: HomeFeedCacheDao = mockk(relaxed = true)
    private val cacheDao: CacheDao = mockk(relaxed = true)
    private val musicGraph: MusicGraphStore = mockk(relaxed = true)

    @After
    fun teardown() = unmockkAll()

    @Test
    fun `an account change drops every cache that was built for the previous owner`() =
        runTest {
            AccountCacheInvalidator(homeFeedCacheDao, cacheDao, musicGraph).clearNow()

            coVerify { homeFeedCacheDao.clearAll() }
            coVerify { cacheDao.clearSubscriptionFeed() }
            coVerify { cacheDao.clearMusicHomeCache() }
            coVerify { cacheDao.clearMusicHomeChips() }
            coVerify { musicGraph.clear() }
        }

    @Test
    fun `a failing cache does not stop the rest of the clear`() =
        runTest {
            coEvery { homeFeedCacheDao.clearAll() } throws IllegalStateException("db closed")

            AccountCacheInvalidator(homeFeedCacheDao, cacheDao, musicGraph).clearNow()

            coVerify { cacheDao.clearSubscriptionFeed() }
            coVerify { musicGraph.clear() }
        }
}
