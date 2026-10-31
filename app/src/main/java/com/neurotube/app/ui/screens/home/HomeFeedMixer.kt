package com.neurotube.app.ui.screens.home

import com.neurotube.app.data.model.Video
import kotlinx.coroutines.flow.map

internal enum class FeedSource {
    SUBS,
    RELATED,
    DISCOVERY,

    /** New uploads from channels the viewer keeps watching without subscribing; see ChannelMemory. */
    CHANNEL_MEMORY,

    /** Uploads of channels several of the viewer's channels sit next to; see NeighbourChannels (#907). */
    NEIGHBOURS,

    /**
     * YouTube's own recommendations for the signed-in account, read from its `FEwhat_to_watch` page.
     * Empty while signed out: it is the only lane the app does not rank itself, so the order the
     * server returned is kept.
     */
    YOUTUBE_HOME,
}

internal data class FeedCandidate(
    val video: Video,
    val source: FeedSource,
)

internal data class FeedMixResult(
    val items: List<FeedCandidate>,
    val sourceCounts: Map<FeedSource, Int>,
) {
    val videos: List<Video> get() = items.map { it.video }
}

internal fun homeFeedQuotas(
    remaining: Int,
    subCount: Int,
    totalInteractions: Int,
    hasNeighbours: Boolean = false,
    hasYouTubeHome: Boolean = false,
): Map<FeedSource, Int> {
    val slots = remaining.coerceAtLeast(0)
    if (slots == 0) {
        return FeedSource.entries.associateWith { 0 }
    }

    val subs =
        when {
            subCount <= 0 -> 0
            totalInteractions > 50 -> (slots * 0.40).toInt()
            else -> (slots * 0.35).toInt()
        }.coerceAtLeast(0)
    val related =
        when {
            subCount <= 0 -> (slots * 0.35).toInt()
            totalInteractions > 50 -> (slots * 0.25).toInt()
            else -> (slots * 0.30).toInt()
        }.coerceAtLeast(0)
    val discovery =
        when {
            subCount <= 0 -> (slots * 0.45).toInt()
            else -> (slots * 0.25).toInt()
        }.coerceAtLeast(0)
    // YouTube's own picks take their share out of the discovery lane, not on top of it: the account's
    // recommendations are what the generic exploration was standing in for. The rest of the page is
    // untouched, so a signed-in viewer's feed keeps the same shape with a better discovery lane.
    val youtubeHome = if (hasYouTubeHome) (discovery * YOUTUBE_HOME_SHARE).toInt().coerceIn(0, discovery) else 0
    val discoveryRemaining = discovery - youtubeHome
    // The remainder, about an eighth of the page: the share the retired trending lane used to get.
    val remainder = (slots - subs - related - discovery).coerceAtLeast(0)
    // Neighbours share it once the viewer has channels of their own to point at them.
    val neighbours = if (hasNeighbours && totalInteractions > 50) minOf(remainder / 2, (slots * 0.10).toInt()) else 0

    return mapOf(
        FeedSource.SUBS to subs,
        FeedSource.RELATED to related,
        FeedSource.DISCOVERY to discoveryRemaining,
        FeedSource.YOUTUBE_HOME to youtubeHome,
        FeedSource.CHANNEL_MEMORY to remainder - neighbours,
        FeedSource.NEIGHBOURS to neighbours,
    )
}

/** The part of the discovery lane the account's own Home feed takes once a viewer is signed in. */
private const val YOUTUBE_HOME_SHARE = 0.6

internal fun addUniqueVideo(
    video: Video?,
    targetList: MutableList<Video>,
    channelCounts: MutableMap<String, Int>,
    usedVideoIds: MutableSet<String>,
    maxPerChannel: Int = 2,
): Boolean {
    if (video == null) return false

    val hasChannel = video.channelId.isNotBlank()
    val count = channelCounts[video.channelId] ?: 0
    if (hasChannel && count >= maxPerChannel) return false
    if (!usedVideoIds.add(video.id)) return false
    targetList.add(video)
    if (hasChannel) channelCounts[video.channelId] = count + 1
    return true
}

internal fun addUniquePageVideos(
    candidates: Iterable<Video>,
    targetList: MutableList<Video>,
    channelCounts: MutableMap<String, Int>,
    usedVideoIds: MutableSet<String>,
    targetSize: Int,
    maxPerChannel: Int = 1,
): Int {
    var added = 0
    for (candidate in candidates) {
        if (targetList.size >= targetSize) break
        if (addUniqueVideo(candidate, targetList, channelCounts, usedVideoIds, maxPerChannel)) {
            added++
        }
    }
    return added
}

private fun addUniqueCandidate(
    candidate: FeedCandidate?,
    targetList: MutableList<FeedCandidate>,
    channelCounts: MutableMap<String, Int>,
    usedVideoIds: MutableSet<String>,
    maxPerChannel: Int = 2,
): Boolean {
    if (candidate == null) return false
    val temp = mutableListOf<Video>()
    if (!addUniqueVideo(candidate.video, temp, channelCounts, usedVideoIds, maxPerChannel)) return false
    targetList.add(candidate)
    return true
}

/**
 * Round-robin over the lanes up to each quota, then lanes with leftovers fill what the others could
 * not. [CHANNEL_MEMORY] and [NEIGHBOURS] never take more than their quotas, and a channel in
 * [singleChannels] (one those lanes serve) appears at most once.
 */
internal fun blendFeedSources(
    lanes: Map<FeedSource, List<Video>>,
    quotas: Map<FeedSource, Int>,
    targetSize: Int,
    channelCounts: MutableMap<String, Int> = mutableMapOf(),
    usedVideoIds: MutableSet<String> = mutableSetOf(),
    singleChannels: Set<String> = emptySet(),
): FeedMixResult {
    val target = targetSize.coerceAtLeast(0)
    if (target == 0) return FeedMixResult(emptyList(), emptyMap())

    val queues =
        FeedSource.entries.associateWith { source ->
            java.util.ArrayDeque(lanes[source].orEmpty().map { FeedCandidate(it, source) })
        }
    val quotaOrder =
        listOf(
            FeedSource.SUBS,
            FeedSource.RELATED,
            FeedSource.YOUTUBE_HOME,
            FeedSource.DISCOVERY,
            FeedSource.CHANNEL_MEMORY,
            FeedSource.NEIGHBOURS,
        )
    // The account's own picks lead the refill: when a lane runs dry they are the best replacement
    // available, and they are absent while signed out so this changes nothing for a guest.
    val scarcityOrder =
        listOf(
            FeedSource.YOUTUBE_HOME,
            FeedSource.RELATED,
            FeedSource.DISCOVERY,
            FeedSource.SUBS,
        )
    val addedBySource = mutableMapOf<FeedSource, Int>()
    val out = mutableListOf<FeedCandidate>()

    // One video per channel first; a second only fills what the lanes could not, so it lands at the end.
    var perChannel = 1
    val skipped = FeedSource.entries.associateWith { java.util.ArrayDeque<FeedCandidate>() }

    fun take(candidate: FeedCandidate?): Boolean {
        val cap = if (candidate != null && candidate.video.channelId in singleChannels) 1 else perChannel
        return addUniqueCandidate(candidate, out, channelCounts, usedVideoIds, cap).also { added ->
            if (added) {
                addedBySource[candidate!!.source] = (addedBySource[candidate.source] ?: 0) + 1
            } else if (candidate != null && perChannel == 1 && candidate.video.id !in usedVideoIds) {
                skipped.getValue(candidate.source).addLast(candidate)
            }
        }
    }

    fill(target, queues, out, quotaOrder, scarcityOrder, addedBySource, quotas, ::take)
    if (out.size < target && skipped.values.any { it.isNotEmpty() }) {
        perChannel = 2
        fill(target, skipped, out, quotaOrder, scarcityOrder, addedBySource, quotas, ::take)
    }

    return FeedMixResult(
        items = out,
        sourceCounts = FeedSource.entries.associateWith { source -> addedBySource[source] ?: 0 },
    )
}

private fun fill(
    target: Int,
    queues: Map<FeedSource, java.util.ArrayDeque<FeedCandidate>>,
    out: List<FeedCandidate>,
    quotaOrder: List<FeedSource>,
    scarcityOrder: List<FeedSource>,
    addedBySource: Map<FeedSource, Int>,
    quotas: Map<FeedSource, Int>,
    take: (FeedCandidate?) -> Boolean,
) {
    while (out.size < target && queues.any { it.value.isNotEmpty() }) {
        var addedThisRound = false
        for (source in quotaOrder) {
            if (out.size >= target) break
            if ((addedBySource[source] ?: 0) < (quotas[source] ?: 0) && take(queues[source]?.pollFirst())) {
                addedThisRound = true
            }
        }

        if (!addedThisRound) {
            // A rejected head (duplicate or capped channel) must not end the refill while the lane
            // still holds usable videos behind it.
            val forced =
                scarcityOrder.any { source ->
                    val queue = queues.getValue(source)
                    var added = false
                    while (!added && queue.isNotEmpty()) added = take(queue.pollFirst())
                    added
                }
            if (!forced) break
        }
    }
}
