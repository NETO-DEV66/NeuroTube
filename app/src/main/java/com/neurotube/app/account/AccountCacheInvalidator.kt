/*
 * Copyright (C) 2026 NeuroTube | A-EDev
 *
 * This file is part of NeuroTube (https://github.com/NETO-DEV66/NeuroTube).
 *
 * NeuroTube is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package com.neurotube.app.account

import com.neurotube.app.data.local.dao.CacheDao
import com.neurotube.app.data.local.dao.HomeFeedCacheDao
import com.neurotube.app.data.recommendation.music.graph.MusicGraphStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drops everything that was fetched *for* one account when the library moves to another.
 *
 * The library tables are scoped by `accountId`, so they switch by themselves. The caches are not:
 * a home feed, a subscription feed and a music graph crawled while account A was active describe
 * account A's interests, and serving them to account B is a correctness bug, not a stale cache.
 *
 * The download tables are deliberately left alone. Downloads are files on this device with their own
 * progress, not a per-account view of YouTube, so they stay shared.
 *
 * The clear runs off the caller's thread and is not awaited: switching account is a UI action, and
 * the caches being empty for a moment is better than blocking the switch on a delete of thousands of
 * rows. Every read path treats a miss as "fetch it".
 */
@Singleton
class AccountCacheInvalidator
    @Inject
    constructor(
        private val homeFeedCacheDao: HomeFeedCacheDao,
        private val cacheDao: CacheDao,
        private val musicGraph: MusicGraphStore,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun clearForAccountChange() {
            scope.launch { clearNow() }
        }

        /**
         * The clear itself, so a caller that must observe it (a test) can await it.
         *
         * Each cache is cleared independently: one table failing must not leave the others serving
         * the previous account's content, and a half-cleared state is worse than a logged failure.
         */
        internal suspend fun clearNow() {
            runCatching { homeFeedCacheDao.clearAll() }
            runCatching { cacheDao.clearSubscriptionFeed() }
            runCatching { cacheDao.clearMusicHomeCache() }
            runCatching { cacheDao.clearMusicHomeChips() }
            runCatching { musicGraph.clear() }
        }
    }
