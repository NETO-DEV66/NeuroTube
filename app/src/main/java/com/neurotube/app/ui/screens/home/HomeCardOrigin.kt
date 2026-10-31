package com.neurotube.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.neurotube.app.R
import com.neurotube.app.data.model.Video
import com.neurotube.app.data.recommendation.NeighbourChannel

/**
 * Why a card is on Home, named on its Details page. [detail] is the seed video's title, the search,
 * or the viewer's channels a neighbour channel sits next to.
 */
data class HomeCardOrigin(
    val kind: Kind,
    val detail: String? = null,
) {
    enum class Kind { SUBSCRIPTION, RELATED, SEARCH, CHANNEL_MEMORY, NEIGHBOUR, YOUTUBE_HOME }

    companion object {
        val subscription = HomeCardOrigin(Kind.SUBSCRIPTION)
        val channelMemory = HomeCardOrigin(Kind.CHANNEL_MEMORY)

        /** A pick from the signed-in account's own YouTube Home; it has no seed or query to name. */
        val youtubeHome = HomeCardOrigin(Kind.YOUTUBE_HOME)

        fun related(seedTitle: String?) = HomeCardOrigin(Kind.RELATED, seedTitle)

        fun search(query: String?) = HomeCardOrigin(Kind.SEARCH, query)

        fun neighbour(pointers: String?) = HomeCardOrigin(Kind.NEIGHBOUR, pointers)
    }
}

@Composable
internal fun HomeCardOrigin.label(): String =
    when (kind) {
        HomeCardOrigin.Kind.SUBSCRIPTION -> {
            stringResource(R.string.home_origin_subscription)
        }

        HomeCardOrigin.Kind.RELATED -> {
            detail?.let { stringResource(R.string.home_origin_related, it) }
                ?: stringResource(R.string.home_origin_related_watched)
        }

        HomeCardOrigin.Kind.SEARCH -> {
            detail?.let { stringResource(R.string.home_origin_search, it) }
                ?: stringResource(R.string.home_origin_search_unnamed)
        }

        HomeCardOrigin.Kind.CHANNEL_MEMORY -> {
            stringResource(R.string.home_origin_channel_memory)
        }

        HomeCardOrigin.Kind.NEIGHBOUR -> {
            detail?.let { stringResource(R.string.home_origin_neighbour, it) }
                ?: stringResource(R.string.home_origin_neighbour_unnamed)
        }

        HomeCardOrigin.Kind.YOUTUBE_HOME -> {
            stringResource(R.string.home_origin_youtube_home)
        }
    }

/** The search each video came from; a video several searches returned keeps the first. */
internal fun queriesById(searches: List<Pair<String, List<Video>>>): Map<String, String> =
    buildMap { searches.forEach { (query, videos) -> videos.forEach { putIfAbsent(it.id, query) } } }

/**
 * The origin of each of [videos]: the lane [sourceOf] names, or, for a card no lane claims, the
 * seed or search that found it, or a followed channel.
 */
internal fun pageOrigins(
    videos: List<Video>,
    sourceOf: (String) -> FeedSource?,
    seedTitles: Map<String, String>,
    queries: Map<String, String>,
    followed: Set<String>,
    neighbourPointers: Map<String, String> = emptyMap(),
): Map<String, HomeCardOrigin> =
    videos
        .mapNotNull { video ->
            val origin =
                when (sourceOf(video.id)) {
                    FeedSource.RELATED -> {
                        HomeCardOrigin.related(seedTitles[video.id])
                    }

                    FeedSource.DISCOVERY -> {
                        HomeCardOrigin.search(queries[video.id])
                    }

                    FeedSource.CHANNEL_MEMORY -> {
                        HomeCardOrigin.channelMemory
                    }

                    FeedSource.SUBS -> {
                        HomeCardOrigin.subscription
                    }

                    FeedSource.NEIGHBOURS -> {
                        HomeCardOrigin.neighbour(neighbourPointers[video.channelId])
                    }

                    FeedSource.YOUTUBE_HOME -> {
                        HomeCardOrigin.youtubeHome
                    }

                    null -> {
                        when {
                            video.id in seedTitles -> HomeCardOrigin.related(seedTitles[video.id])
                            video.id in queries -> HomeCardOrigin.search(queries.getValue(video.id))
                            video.channelId in followed -> HomeCardOrigin.subscription
                            else -> null
                        }
                    }
                }
            origin?.let { video.id to it }
        }.toMap()

/** What each neighbour's card says it is like: one or two of the viewer's channels pointing to it. */
internal fun neighbourPointerTexts(
    neighbours: List<NeighbourChannel>,
    pair: (String, String) -> String,
): Map<String, String> =
    neighbours
        .mapNotNull { neighbour ->
            val names = neighbour.pointerNames
            when {
                names.isEmpty() -> null
                names.size == 1 -> neighbour.channelId to names[0]
                else -> neighbour.channelId to pair(names[0], names[1])
            }
        }.toMap()
