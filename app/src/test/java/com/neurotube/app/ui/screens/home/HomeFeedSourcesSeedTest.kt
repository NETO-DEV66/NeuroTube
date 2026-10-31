/*
 * Copyright (C) 2025-2026 NeuroTube | A-EDev
 *
 * This file is part of NeuroTube (https://github.com/NETO-DEV66/NeuroTube).
 */

package com.neurotube.app.ui.screens.home

import com.google.common.truth.Truth.assertThat
import com.neurotube.app.data.local.LikedVideosRepository
import com.neurotube.app.data.local.VideoHistoryEntry
import com.neurotube.app.data.local.ViewHistory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HomeFeedSourcesSeedTest {
    private fun entry(id: String) =
        VideoHistoryEntry(videoId = id, position = 590, duration = 600, timestamp = 1_000L, title = "Video $id", thumbnailUrl = "")

    @Test
    fun `a disliked video is never a related seed however much of it was watched`() =
        runTest {
            val viewHistory: ViewHistory =
                mockk {
                    coEvery { getRecentVideoHistory(any(), any()) } returns
                        listOf(entry("kept"), entry("disliked"))
                }
            val liked: LikedVideosRepository = mockk { coEvery { dislikedVideoIds() } returns setOf("disliked") }
            val sources = HomeFeedSources(mockk(), mockk(), viewHistory, liked, mockk(), mockk(relaxed = true), mockk(relaxed = true))

            assertThat(sources.historySeedInputs().map { it.id }).containsExactly("kept")
        }
}
