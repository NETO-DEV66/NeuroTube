package com.neurotube.app.sync.apply

import android.content.Context
import com.neurotube.app.account.AccountScope
import com.neurotube.app.data.audio.eq.EqStateJson
import com.neurotube.app.data.audio.eq.EqualizerRepository
import com.neurotube.app.data.local.LikedVideosRepository
import com.neurotube.app.data.local.PlayerPreferences
import com.neurotube.app.data.local.SubscriptionRepository
import com.neurotube.app.data.local.dao.PlaylistDao
import com.neurotube.app.data.local.dao.SubscriptionGroupDao
import com.neurotube.app.data.local.dao.VideoDao
import com.neurotube.app.data.local.dao.WatchHistoryDao
import com.neurotube.app.data.local.entity.NoteEntity
import com.neurotube.app.data.local.readHistory
import com.neurotube.app.data.localmedia.LocalMediaIds
import com.neurotube.app.sync.canonical.CanonicalLike
import com.neurotube.app.sync.canonical.CanonicalNote
import com.neurotube.app.sync.canonical.CanonicalPlaylist
import com.neurotube.app.sync.canonical.CanonicalSetting
import com.neurotube.app.sync.canonical.CanonicalSubscribedChannel
import com.neurotube.app.sync.canonical.CanonicalSubscriptionGroup
import com.neurotube.app.sync.canonical.CanonicalWatchHistory
import com.neurotube.app.sync.identity.Hlc
import com.neurotube.app.sync.mapping.LikesMapper
import com.neurotube.app.sync.mapping.PlaylistMapper
import com.neurotube.app.sync.mapping.SettingsMapper
import com.neurotube.app.sync.mapping.SubscribedChannelsMapper
import com.neurotube.app.sync.mapping.SubscriptionsMapper
import com.neurotube.app.sync.mapping.WatchHistoryMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bridge between platform-neutral canonical records and the app's real stores (Room DAOs,
 * DataStore singletons). Provides `read*` (local → canonical) and `write*` (merged canonical →
 * store). Records are read with placeholder stamps; [SyncChangeTracker] stamps them and adds the
 * deletions. The stateful brains live in [BrainSyncAccess].
 */
@Singleton
class SyncDataAccess
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val watchHistoryDao: WatchHistoryDao,
        private val playlistDao: PlaylistDao,
        private val videoDao: VideoDao,
        private val subscriptionGroupDao: SubscriptionGroupDao,
        private val noteDao: com.neurotube.app.data.local.dao.NoteDao,
        private val subscriptions: SubscriptionRepository,
        private val equalizer: EqualizerRepository,
    ) {
        private val likedVideos: LikedVideosRepository by lazy { LikedVideosRepository.getInstance(context) }
        private val playerPrefs: PlayerPreferences by lazy { PlayerPreferences(context) }

        /**
         * The library a sync pass reads and writes. Sync moves one library at a time, so it follows
         * the active account rather than merging every account's data into one stream.
         */
        private val accountId: String get() = AccountScope.activeId

        // --- watch history ---

        suspend fun readWatchHistory(node: String): List<CanonicalWatchHistory> =
            watchHistoryDao
                .readHistory(accountId, isLocal = 0) // device-local media files don't sync
                .map { WatchHistoryMapper.toCanonical(it, node) }

        suspend fun writeWatchHistory(merged: List<CanonicalWatchHistory>) {
            val toUpsert = merged.filter { !it.deleted }.map { WatchHistoryMapper.toEntity(it, accountId) }
            if (toUpsert.isNotEmpty()) watchHistoryDao.upsertAll(toUpsert)
            for (d in merged) if (d.deleted) watchHistoryDao.deleteEntry(d.videoId, accountId)
        }

        // --- likes (export is liked-only; apply handles all 3 states) ---

        suspend fun readLikes(node: String): List<CanonicalLike> =
            likedVideos
                .getAllLikedVideos()
                .first()
                .filterNot { LocalMediaIds.isLocal(it.videoId) }
                .map { LikesMapper.likedToCanonical(it, node) }

        suspend fun writeLikes(merged: List<CanonicalLike>) {
            for (like in merged) {
                when (like.state) {
                    CanonicalLike.STATE_LIKED -> likedVideos.likeVideo(LikesMapper.toLikedInfo(like))
                    CanonicalLike.STATE_DISLIKED -> likedVideos.dislikeVideo(like.id)
                    CanonicalLike.STATE_NONE -> likedVideos.removeLikeState(like.id)
                }
            }
        }

        // --- settings (curated whitelist) ---

        suspend fun readSettings(hlc: String): List<CanonicalSetting> {
            val settings = playerPrefs.getExportData()
            val withEqualizer = settings.copy(strings = settings.strings + (EqStateJson.KEY to equalizer.exportJson()))
            return SettingsMapper.exportToCanonical(withEqualizer, hlc)
        }

        suspend fun writeSettings(merged: List<CanonicalSetting>) {
            val settings = SettingsMapper.applyToBackup(merged)
            settings.strings[EqStateJson.KEY]?.let { equalizer.importJson(it) }
            playerPrefs.restoreData(settings.copy(strings = settings.strings - EqStateJson.KEY))
        }

        // --- subscribed channels ---

        /**
         * Live subscriptions plus this device's unsubscribe tombstones. A channel that appears in both
         * is emitted once, resolved by the merger — a re-subscribe clears its tombstone anyway.
         */
        suspend fun readSubscribedChannels(node: String): List<CanonicalSubscribedChannel> {
            val live = subscriptions.getAllSubscriptions().first()
            val liveIds = live.mapTo(HashSet()) { it.channelId }
            val tombstones =
                subscriptions
                    .unsubscribedTombstones()
                    .filterKeys { it !in liveIds }
                    .map { (channelId, at) -> SubscribedChannelsMapper.tombstone(channelId, at, node) }
            return live.map { SubscribedChannelsMapper.toCanonical(it, node) } + tombstones
        }

        suspend fun writeSubscribedChannels(merged: List<CanonicalSubscribedChannel>) {
            val (removed, kept) = merged.partition { it.deleted }

            // subscribeAll replaces the whole stored record, so carry this device's local-only fields
            // across explicitly — otherwise every sync would silently reset per-channel notification
            // opt-ins and force the subscription feed to re-fetch every channel from scratch.
            val existing = subscriptions.getAllSubscriptions().first().associateBy { it.channelId }
            val toSubscribe =
                kept.map { c ->
                    val local = existing[c.channelId]
                    SubscribedChannelsMapper.toSubscription(c).copy(
                        isNotificationEnabled = local?.isNotificationEnabled ?: false,
                        lastVideoId = local?.lastVideoId,
                        lastCheckTime = local?.lastCheckTime ?: 0L,
                        lastFeedFetchAt = local?.lastFeedFetchAt ?: 0L,
                    )
                }
            if (toSubscribe.isNotEmpty()) subscriptions.subscribeAll(toSubscribe)

            val subscribedIds = subscriptions.getAllSubscriptionIds()
            for (c in removed) {
                if (c.channelId in subscribedIds) subscriptions.unsubscribe(c.channelId)
            }
            // Keep the peer's tombstones even for channels this device never had, so the unsubscribe
            // still reaches a third device that does.
            subscriptions.recordUnsubscribedAt(
                removed.associate { it.channelId to Hlc.decode(it.hlc).physicalMs },
            )
        }

        // --- subscription groups ---

        suspend fun readSubscriptions(hlc: String): List<CanonicalSubscriptionGroup> =
            subscriptionGroupDao.getAllGroupsOnce(accountId).map { SubscriptionsMapper.toCanonical(it, hlc) }

        suspend fun writeSubscriptions(merged: List<CanonicalSubscriptionGroup>) {
            val toUpsert = merged.filter { !it.deleted }.map { SubscriptionsMapper.toEntity(it, accountId) }
            if (toUpsert.isNotEmpty()) subscriptionGroupDao.insertAll(toUpsert)
            for (g in merged) if (g.deleted) subscriptionGroupDao.deleteGroup(g.name, accountId)
        }

        // --- notes ---

        suspend fun readNotes(): List<CanonicalNote> =
            noteDao.getAll().map { note ->
                CanonicalNote(
                    id = note.id,
                    targetId = note.targetId,
                    kind = note.kind,
                    text = note.text,
                    updatedAt = note.updatedAt,
                    title = note.title,
                    channelName = note.channelName,
                    channelId = note.channelId,
                    thumbnailUrl = note.thumbnailUrl,
                    durationSeconds = note.durationSeconds,
                    channelAvatarUrl = note.channelAvatarUrl,
                    channelHandle = note.channelHandle,
                    subscriberCountText = note.subscriberCountText,
                )
            }

        suspend fun writeNotes(merged: List<CanonicalNote>) {
            val live = merged.filter { !it.deleted && it.text.isNotBlank() }
            if (live.isNotEmpty()) {
                // The hand-made order is this device's own; a peer's copy never carries it.
                val positions = noteDao.getAll().associate { it.id to it.position }
                noteDao.upsertAll(
                    live.map { note ->
                        NoteEntity(
                            id = note.id,
                            targetId = note.targetId,
                            kind = note.kind,
                            text = note.text,
                            updatedAt = note.updatedAt,
                            title = note.title,
                            channelName = note.channelName,
                            channelId = note.channelId,
                            thumbnailUrl = note.thumbnailUrl,
                            durationSeconds = note.durationSeconds,
                            channelAvatarUrl = note.channelAvatarUrl,
                            channelHandle = note.channelHandle,
                            subscriberCountText = note.subscriberCountText,
                            position = positions[note.id],
                        )
                    },
                )
            }
            for (note in merged) if (note.deleted) noteDao.deleteById(note.id)
        }

        // --- playlists ---

        suspend fun readPlaylists(hlc: String): List<CanonicalPlaylist> {
            val playlists = playlistDao.getAllPlaylists(accountId).first()
            val refsByPlaylist = playlistDao.getAllPlaylistVideoCrossRefs(accountId).groupBy { it.playlistId }
            val videosById = videoDao.getAllVideos().associateBy { it.id }
            return playlists.map { p ->
                val items =
                    (refsByPlaylist[p.id] ?: emptyList()).map { ref ->
                        PlaylistMapper.ItemSource(ref, videosById[ref.videoId])
                    }
                PlaylistMapper.toCanonical(p, items, hlc)
            }
        }

        suspend fun writePlaylists(merged: List<CanonicalPlaylist>) {
            val locals = playlistDao.getAllPlaylists(accountId).first()
            val bySyncId = locals.associateBy { it.syncId ?: it.id }
            val byYoutubeId = locals.filter { !it.isUserCreated }.associateBy { it.id }
            val allRefs = playlistDao.getAllPlaylistVideoCrossRefs(accountId).groupBy { it.playlistId }

            for (cp in merged) {
                val localId = resolveLocalId(cp, bySyncId, byYoutubeId)
                if (cp.deleted) {
                    if (localId != null && localId != PlaylistMapper.WATCH_LATER_ID &&
                        localId != PlaylistMapper.SAVED_SHORTS_ID
                    ) {
                        playlistDao.deletePlaylist(localId, accountId)
                    }
                    continue
                }
                val targetId = localId ?: newLocalId(cp)
                playlistDao.insertPlaylist(PlaylistMapper.toPlaylistEntity(cp, targetId, accountId))
                videoDao.insertVideosOrIgnore(PlaylistMapper.toVideoEntities(cp))

                val mergedRefs = PlaylistMapper.toCrossRefs(cp, targetId, accountId)
                val mergedVids = mergedRefs.map { it.videoId }.toSet()
                // Remove refs no longer present, then upsert the merged set (positions updated).
                for (ref in allRefs[targetId].orEmpty()) {
                    if (ref.videoId !in mergedVids) playlistDao.removeVideoFromPlaylist(targetId, ref.videoId, accountId)
                }
                for (ref in mergedRefs) playlistDao.insertPlaylistVideoCrossRef(ref)
            }
        }

        private fun resolveLocalId(
            cp: CanonicalPlaylist,
            bySyncId: Map<String, com.neurotube.app.data.local.entity.PlaylistEntity>,
            byYoutubeId: Map<String, com.neurotube.app.data.local.entity.PlaylistEntity>,
        ): String? {
            if (cp.syncId == CanonicalPlaylist.RESERVED_WATCH_LATER) return PlaylistMapper.WATCH_LATER_ID
            bySyncId[cp.syncId]?.let { return it.id }
            if (cp.origin == CanonicalPlaylist.ORIGIN_YOUTUBE && cp.youtubeId != null) {
                byYoutubeId[cp.youtubeId]?.let { return it.id }
            }
            return null
        }

        private fun newLocalId(cp: CanonicalPlaylist): String =
            when {
                cp.syncId == CanonicalPlaylist.RESERVED_WATCH_LATER -> PlaylistMapper.WATCH_LATER_ID
                cp.origin == CanonicalPlaylist.ORIGIN_YOUTUBE && cp.youtubeId != null -> cp.youtubeId
                else -> "sync_${UUID.randomUUID()}"
            }
    }
