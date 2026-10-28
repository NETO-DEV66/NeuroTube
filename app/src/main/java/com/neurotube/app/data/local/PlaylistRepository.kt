package com.neurotube.app.data.local

import com.neurotube.app.account.AccountScope
import com.neurotube.app.data.local.dao.PlaylistDao
import com.neurotube.app.data.local.dao.PlaylistWithCount
import com.neurotube.app.data.local.dao.VideoDao
import com.neurotube.app.data.local.entity.PlaylistEntity
import com.neurotube.app.data.local.entity.PlaylistVideoCrossRef
import com.neurotube.app.data.local.entity.VideoEntity
import com.neurotube.app.data.model.PlaylistInfo
import com.neurotube.app.data.model.Video
import com.neurotube.app.innertube.YouTube
import com.neurotube.app.innertube.pages.AccountVideoCollection
import com.neurotube.app.utils.relativedate.RelativeUploadDateParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository
    @Inject
    constructor(
        private val playlistDao: PlaylistDao,
        private val videoDao: VideoDao,
    ) {
        constructor(context: android.content.Context) : this(
            AppDatabase.getDatabase(context).playlistDao(),
            AppDatabase.getDatabase(context).videoDao(),
        )

        /**
         * The library every call acts on. Read per call rather than captured once, because the
         * account can change while this singleton lives, and an old scope would write into the
         * wrong library.
         */
        private val scope: String get() = AccountScope.activeId

        // Watch Later Logic (using a special hardcoded playlist ID "watch_later")
        companion object {
            const val WATCH_LATER_ID = "watch_later"
            const val SAVED_SHORTS_ID = "saved_shorts"

            // Built-in pages read from the likes store, not playlist rows.
            const val LIKED_VIDEOS_ID = "liked_videos"
            const val LIKED_MUSIC_ID = "liked_music"
        }

        suspend fun updateVideoMetadata(video: Video) {
            videoDao.upsertMetadata(listOf(normalizedEntity(video)))
        }

        private fun normalizedEntity(video: Video): VideoEntity {
            val normalizedVideo =
                RelativeUploadDateParser
                    .parse(video.uploadDate, YouTube.locale.hl)
                    ?.let { parsedTimestamp ->
                        val stableTimestamp =
                            video.timestamp
                                .takeIf { it > 0L }
                                ?.let { minOf(it, parsedTimestamp) }
                                ?: parsedTimestamp
                        video.copy(timestamp = stableTimestamp)
                    }
                    ?: video
            return VideoEntity.fromDomain(normalizedVideo)
        }

        // Saved Shorts Logic
        suspend fun addToSavedShorts(video: Video) {
            // Ensure saved shorts playlist exists
            val savedShorts = playlistDao.getPlaylist(SAVED_SHORTS_ID, scope)
            if (savedShorts == null) {
                playlistDao.insertPlaylist(
                    PlaylistEntity(
                        id = SAVED_SHORTS_ID,
                        name = "Saved Shorts",
                        description = "Your saved shorts",
                        thumbnailUrl = "",
                        isPrivate = true,
                        createdAt = System.currentTimeMillis(),
                        accountId = scope,
                    ),
                )
            }

            // Save video
            updateVideoMetadata(video)

            // Add relationship
            val position = System.currentTimeMillis()
            playlistDao.insertPlaylistVideoCrossRef(
                PlaylistVideoCrossRef(
                    playlistId = SAVED_SHORTS_ID,
                    videoId = video.id,
                    position = -position,
                ),
            )
        }

        suspend fun removeFromSavedShorts(videoId: String) {
            playlistDao.removeVideoFromPlaylist(SAVED_SHORTS_ID, videoId, scope)
        }

        fun getSavedShortsFlow(): Flow<List<Video>> =
            playlistDao.getVideosForPlaylist(SAVED_SHORTS_ID, scope).map { entities ->
                entities.map { it.toDomain() }
            }

        fun getVideoOnlySavedShortsFlow(): Flow<List<Video>> = getSavedShortsFlow().map { list -> list.filter { !it.isMusic } }

        suspend fun isInSavedShorts(videoId: String): Boolean {
            val videos = playlistDao.getVideosForPlaylist(SAVED_SHORTS_ID, scope).firstOrNull() ?: emptyList()
            return videos.any { it.id == videoId }
        }

        suspend fun addToWatchLater(video: Video) {
            try {
                android.util.Log.d("PlaylistRepository", "Adding video to Watch Later: ${video.id}")
                val watchLater = playlistDao.getPlaylist(WATCH_LATER_ID, scope)
                if (watchLater == null) {
                    android.util.Log.d("PlaylistRepository", "Creating Watch Later playlist")
                    playlistDao.insertPlaylist(
                        PlaylistEntity(
                            id = WATCH_LATER_ID,
                            name = "Watch Later",
                            description = "Your watch later list",
                            thumbnailUrl = "",
                            isPrivate = true,
                            createdAt = System.currentTimeMillis(),
                            accountId = scope,
                        ),
                    )
                }

                // Save video
                android.util.Log.d("PlaylistRepository", "Inserting video metadata")
                updateVideoMetadata(video)

                // Add relationship
                val position = System.currentTimeMillis()
                android.util.Log.d("PlaylistRepository", "Inserting cross-ref")
                playlistDao.insertPlaylistVideoCrossRef(
                    PlaylistVideoCrossRef(
                        playlistId = WATCH_LATER_ID,
                        videoId = video.id,
                        position = -position,
                        accountId = scope,
                    ),
                )
                android.util.Log.d("PlaylistRepository", "Successfully added to Watch Later")
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Failed to add to Watch Later", e)
                throw e
            }
        }

        suspend fun removeFromWatchLater(videoId: String) {
            playlistDao.removeVideoFromPlaylist(WATCH_LATER_ID, videoId, scope)
        }

        suspend fun clearWatchLater() {
            playlistDao.deletePlaylist(WATCH_LATER_ID, scope)
        }

        fun getWatchLaterVideosFlow(): Flow<List<Video>> =
            playlistDao.getVideosForPlaylist(WATCH_LATER_ID, scope).map { entities ->
                entities.map { it.toDomain() }
            }

        fun getVideoOnlyWatchLaterFlow(): Flow<List<Video>> = getWatchLaterVideosFlow().map { list -> list.filter { !it.isMusic } }

        fun getMusicOnlyWatchLaterFlow(): Flow<List<Video>> = getWatchLaterVideosFlow().map { list -> list.filter { it.isMusic } }

        fun getWatchLaterIdsFlow(): Flow<Set<String>> =
            playlistDao.getVideosForPlaylist(WATCH_LATER_ID, scope).map { entities ->
                entities.map { it.id }.toSet()
            }

        fun isVideoSavedToAnyPlaylistFlow(videoId: String): Flow<Boolean> =
            playlistDao.getVideoPlaylistMembershipCount(videoId, scope).map {
                it >
                    0
            }

        suspend fun isInWatchLater(videoId: String): Boolean =
            try {
                playlistDao.isVideoInPlaylist(WATCH_LATER_ID, videoId, scope) > 0
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Error checking watch later status", e)
                false
            }

        suspend fun isVideoInPlaylist(
            playlistId: String,
            videoId: String,
        ): Boolean =
            try {
                playlistDao.isVideoInPlaylist(playlistId, videoId, scope) > 0
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Error checking playlist status", e)
                false
            }

        // Playlist Management
        suspend fun createPlaylist(
            playlistId: String,
            name: String,
            description: String,
            isPrivate: Boolean,
            isMusic: Boolean = false,
        ) {
            val entity =
                PlaylistEntity(
                    id = playlistId,
                    name = name,
                    description = description,
                    thumbnailUrl = "",
                    isPrivate = isPrivate,
                    createdAt = System.currentTimeMillis(),
                    isMusic = isMusic,
                    isUserCreated = true,
                    accountId = scope,
                )
            playlistDao.insertPlaylist(entity)
        }

        /**
         * Adds an imported playlist as the viewer's own, in the given order. Videos already in the
         * library keep their stored metadata; the file only fills in ones NeuroTube has never seen.
         */
        suspend fun importPlaylist(
            name: String,
            description: String,
            videos: List<Video>,
            isMusic: Boolean = false,
        ): String {
            val now = System.currentTimeMillis()
            val playlistId = now.toString()
            videoDao.insertVideosOrIgnore(videos.map(::normalizedEntity))
            playlistDao.insertPlaylistWithVideos(
                playlist =
                    PlaylistEntity(
                        id = playlistId,
                        name = name,
                        description = description,
                        thumbnailUrl = videos.firstOrNull()?.thumbnailUrl.orEmpty(),
                        isPrivate = true,
                        createdAt = now,
                        isMusic = isMusic,
                        isUserCreated = true,
                        accountId = scope,
                    ),
                entries =
                    videos.mapIndexed { index, video ->
                        PlaylistVideoCrossRef(
                            playlistId = playlistId,
                            videoId = video.id,
                            position = index.toLong(),
                            addedAt = video.addedAtInPlaylist ?: now,
                            accountId = scope,
                        )
                    },
            )
            return playlistId
        }

        /**
         * Saves the account's youtube.com playlists as external playlists, one row each.
         *
         * The playlists themselves are what is kept; their videos are fetched when the user opens
         * one, which keeps an import from spending a request per playlist on songs the user may
         * never play. Returns how many rows were written.
         */
        suspend fun saveVideoPlaylists(playlists: List<AccountVideoCollection>): Int =
            playlists.count { playlist ->
                runCatching {
                    val existing = playlistDao.getPlaylist(playlist.id, scope)
                    if (existing != null && existing.isUserCreated) return@runCatching false
                    playlistDao.insertPlaylist(
                        PlaylistEntity(
                            id = playlist.id,
                            name = playlist.title,
                            description = "",
                            thumbnailUrl = playlist.thumbnailUrl,
                            isPrivate = false,
                            createdAt = System.currentTimeMillis(),
                            isMusic = false,
                            isUserCreated = false,
                            accountId = scope,
                        ),
                    )
                    true
                }.getOrDefault(false)
            }

        suspend fun saveExternalVideoPlaylist(
            id: String,
            name: String,
            description: String,
            thumbnailUrl: String,
        ) {
            val entity =
                PlaylistEntity(
                    id = id,
                    name = name,
                    description = description,
                    thumbnailUrl = thumbnailUrl,
                    isPrivate = false,
                    createdAt = System.currentTimeMillis(),
                    isMusic = false,
                    isUserCreated = false,
                    accountId = scope,
                )
            playlistDao.insertPlaylist(entity)
        }

        suspend fun saveExternalMusicPlaylist(
            id: String,
            name: String,
            description: String,
            thumbnailUrl: String,
        ) {
            val entity =
                PlaylistEntity(
                    id = id,
                    name = name,
                    description = description,
                    thumbnailUrl = thumbnailUrl,
                    isPrivate = false,
                    createdAt = System.currentTimeMillis(),
                    isMusic = true,
                    isUserCreated = false,
                    accountId = scope,
                )
            playlistDao.insertPlaylist(entity)
        }

        suspend fun unsaveExternalPlaylist(playlistId: String) {
            val entity = playlistDao.getPlaylist(playlistId, scope)
            if (entity != null && !entity.isUserCreated) {
                playlistDao.deletePlaylist(playlistId, scope)
            }
        }

        suspend fun isExternalPlaylistSaved(playlistId: String): Boolean = playlistDao.isSavedExternalPlaylist(playlistId, scope) > 0

        suspend fun updatePlaylistName(
            playlistId: String,
            name: String,
        ) {
            playlistDao.updatePlaylistName(playlistId, scope, name)
        }

        suspend fun updatePlaylistMetadata(
            playlistId: String,
            name: String,
            description: String,
            isPrivate: Boolean,
        ) {
            playlistDao.updatePlaylistMetadata(playlistId, scope, name, description, isPrivate)
        }

        suspend fun deletePlaylist(playlistId: String) {
            playlistDao.deletePlaylist(playlistId, scope)
        }

        suspend fun addVideoToPlaylist(
            playlistId: String,
            video: Video,
        ) {
            addVideosToPlaylist(playlistId, listOf(video))
        }

        /**
         * Appends [videos] to the end of the playlist, preserving the order they are given in.
         * Positions are a plain ascending ordering key here, unlike the negative "newest first"
         * timestamps Watch Later and Saved Shorts stamp on their own rows.
         */
        suspend fun addVideosToPlaylist(
            targetPlaylistId: String,
            videos: List<Video>,
        ) {
            if (videos.isEmpty()) return
            try {
                val alreadyInPlaylist = playlistDao.getVideoIdsInPlaylist(targetPlaylistId, scope).toHashSet()
                var nextPosition = (playlistDao.getMaxPlaylistPosition(targetPlaylistId, scope) ?: -1L) + 1L

                videoDao.mergeMetadata(videos.map(::normalizedEntity))
                videos.forEach { video ->
                    // Re-adding a track already in the playlist must not move it.
                    if (!alreadyInPlaylist.add(video.id)) return@forEach
                    playlistDao.insertPlaylistVideoCrossRef(
                        PlaylistVideoCrossRef(
                            playlistId = targetPlaylistId,
                            videoId = video.id,
                            position = nextPosition++,
                            accountId = scope,
                        ),
                    )
                }

                val newThumb = playlistDao.getFirstVideoThumbnail(targetPlaylistId, scope) ?: videos.first().thumbnailUrl
                playlistDao.updatePlaylistThumbnail(targetPlaylistId, scope, newThumb)
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Failed to add to playlist $targetPlaylistId", e)
                throw e
            }
        }

        suspend fun removeVideoFromPlaylist(
            playlistId: String,
            videoId: String,
        ) {
            playlistDao.removeVideoFromPlaylist(playlistId, videoId, scope)
            val newThumb = playlistDao.getFirstVideoThumbnail(playlistId, scope) ?: ""
            playlistDao.updatePlaylistThumbnail(playlistId, scope, newThumb)
        }

        /** Removes [videoIds] and returns what [restorePlaylistVideos] needs to put them back. */
        suspend fun takeVideosFromPlaylist(
            playlistId: String,
            videoIds: Collection<String>,
        ): List<PlaylistVideoCrossRef> = playlistDao.takePlaylistVideos(playlistId, scope, videoIds.toList())

        suspend fun restorePlaylistVideos(entries: List<PlaylistVideoCrossRef>) {
            if (entries.isNotEmpty()) playlistDao.restorePlaylistVideos(entries)
        }

        suspend fun reorderVideosInPlaylist(
            playlistId: String,
            orderedVideoIds: List<String>,
        ) {
            playlistDao.reorderPlaylistVideos(playlistId, scope, orderedVideoIds)
        }

        suspend fun reorderPlaylists(orderedIds: List<String>) = playlistDao.reorderPlaylists(orderedIds, scope)

        fun getAllPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getVideoPlaylistsWithCount(scope).map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getUserCreatedVideoPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getUserCreatedVideoPlaylistsWithCount(scope).map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getSavedVideoPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getSavedVideoPlaylistsWithCount(scope).map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getMusicPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getMusicPlaylistsWithCount(scope).map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getUserCreatedMusicPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getUserCreatedMusicPlaylistsWithCount(scope).map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getSavedMusicPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getSavedMusicPlaylistsWithCount(scope).map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        suspend fun getSavedVideoPlaylistVideos(): List<Video> = playlistDao.getSavedVideoPlaylistVideos(scope).map { it.toDomain() }

        suspend fun getPlaylistIdsForVideo(videoId: String): List<String> = playlistDao.getPlaylistIdsForVideo(videoId, scope)

        fun getPlaylistVideosFlow(playlistId: String): Flow<List<Video>> =
            playlistDao.getVideosForPlaylist(playlistId, scope).map { entities ->
                entities.map { it.toDomain() }
            }

        /** Like [getPlaylistVideosFlow] but each video carries when it was added to this playlist. */
        fun getPlaylistVideosWithAddedAtFlow(playlistId: String): Flow<List<Video>> =
            playlistDao.getVideosWithMetaForPlaylist(playlistId, scope).map { rows ->
                // addedAt <= 0 means "unknown" (legacy rows reordered before the addedAt column
                // existed) — surface null so the UI falls back instead of showing an epoch date.
                rows.map { it.video.toDomain().copy(addedAtInPlaylist = it.addedAt.takeIf { ts -> ts > 0L }) }
            }

        /**
         * Reconciles a saved (not-owned) playlist's local copy with a fresh remote fetch: upserts each
         * remote video's metadata, restores creator order, adds newly-published videos and drops ones
         * the creator removed. Keeps the playlist available offline while showing real, current data.
         */
        suspend fun syncSavedPlaylistVideos(
            playlistId: String,
            remoteVideos: List<Video>,
        ) {
            if (remoteVideos.isEmpty()) return
            videoDao.mergeMetadata(remoteVideos.map(::normalizedEntity))
            playlistDao.replacePlaylistVideos(playlistId, scope, remoteVideos.map { it.id }.distinct())
        }

        suspend fun getPlaylistEntity(playlistId: String): PlaylistEntity? = playlistDao.getPlaylist(playlistId, scope)

        fun observePlaylistEntity(playlistId: String): Flow<PlaylistEntity?> = playlistDao.observePlaylist(playlistId, scope)

        suspend fun getPlaylistInfo(playlistId: String): PlaylistInfo? {
            val entity = playlistDao.getPlaylist(playlistId, scope) ?: return null
            return PlaylistInfo(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                videoCount = 0,
                thumbnailUrl = entity.thumbnailUrl,
                isPrivate = entity.isPrivate,
                createdAt = entity.createdAt,
            )
        }
    }

private fun PlaylistWithCount.toPlaylistInfo() =
    PlaylistInfo(
        id = playlist.id,
        name = playlist.name,
        description = playlist.description,
        videoCount = videoCount,
        thumbnailUrl = playlist.thumbnailUrl,
        isPrivate = playlist.isPrivate,
        createdAt = playlist.createdAt,
        position = playlist.position,
    )
