package com.neurotube.app.data.local.dao

import androidx.room.*
import com.neurotube.app.data.local.entity.PlaylistEntity
import com.neurotube.app.data.local.entity.PlaylistVideoCrossRef
import com.neurotube.app.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    @ColumnInfo(name = "video_count") val videoCount: Int,
)

/** A playlist's video joined with per-playlist metadata (when it was added to that playlist). */
data class PlaylistVideoWithMeta(
    @Embedded val video: VideoEntity,
    @ColumnInfo(name = "addedAt") val addedAt: Long,
)

/**
 * Playlist reads and writes, all scoped to one account.
 *
 * Every statement takes `accountId` explicitly instead of reading the active account itself: a
 * background worker, a sync pass and a screen must be able to name the library they act on, and a
 * query that forgot the filter would silently mix two accounts' playlists.
 */
@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistRow(playlist: PlaylistEntity)

    /** Re-saving or syncing a playlist keeps the place the viewer dragged it to. */
    @Transaction
    suspend fun insertPlaylist(playlist: PlaylistEntity) {
        val keptPosition =
            if (playlist.position != 0) {
                playlist.position
            } else {
                getPlaylist(playlist.id, playlist.accountId)?.position ?: 0
            }
        insertPlaylistRow(playlist.copy(position = keptPosition))
    }

    @Query("UPDATE playlists SET position = :position WHERE id = :id AND accountId = :accountId")
    suspend fun updatePlaylistPosition(
        id: String,
        accountId: String,
        position: Int,
    )

    /** Stores [orderedIds] as the custom order, starting at 1 so playlists added later lead the list. */
    @Transaction
    suspend fun reorderPlaylists(
        orderedIds: List<String>,
        accountId: String,
    ) {
        orderedIds.forEachIndexed { index, id -> updatePlaylistPosition(id, accountId, index + 1) }
    }

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId AND playlists.accountId = playlist_video_cross_ref.accountId WHERE playlists.accountId = :accountId GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getAllPlaylistsWithCount(accountId: String): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId AND playlists.accountId = playlist_video_cross_ref.accountId WHERE playlists.accountId = :accountId AND isMusic = 1 GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getMusicPlaylistsWithCount(accountId: String): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId AND playlists.accountId = playlist_video_cross_ref.accountId WHERE playlists.accountId = :accountId AND isMusic = 0 AND playlists.id NOT IN ('watch_later', 'saved_shorts') GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getVideoPlaylistsWithCount(accountId: String): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId AND playlists.accountId = playlist_video_cross_ref.accountId WHERE playlists.accountId = :accountId AND isMusic = 0 AND isUserCreated = 1 AND playlists.id NOT IN ('watch_later', 'saved_shorts') GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getUserCreatedVideoPlaylistsWithCount(accountId: String): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId AND playlists.accountId = playlist_video_cross_ref.accountId WHERE playlists.accountId = :accountId AND isMusic = 0 AND isUserCreated = 0 AND playlists.id NOT IN ('watch_later', 'saved_shorts') GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getSavedVideoPlaylistsWithCount(accountId: String): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId AND playlists.accountId = playlist_video_cross_ref.accountId WHERE playlists.accountId = :accountId AND isMusic = 1 AND isUserCreated = 1 GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getUserCreatedMusicPlaylistsWithCount(accountId: String): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId AND playlists.accountId = playlist_video_cross_ref.accountId WHERE playlists.accountId = :accountId AND isMusic = 1 AND isUserCreated = 0 GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getSavedMusicPlaylistsWithCount(accountId: String): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE accountId = :accountId ORDER BY createdAt DESC")
    fun getAllPlaylists(accountId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE accountId = :accountId AND isMusic = 1 ORDER BY createdAt DESC")
    fun getMusicPlaylists(accountId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE accountId = :accountId AND isMusic = 0 ORDER BY createdAt DESC")
    fun getVideoPlaylists(accountId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id AND accountId = :accountId")
    suspend fun getPlaylist(
        id: String,
        accountId: String,
    ): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id AND accountId = :accountId")
    fun observePlaylist(
        id: String,
        accountId: String,
    ): Flow<PlaylistEntity?>

    @Query("DELETE FROM playlists WHERE id = :id AND accountId = :accountId")
    suspend fun deletePlaylist(
        id: String,
        accountId: String,
    )

    @Query("UPDATE playlists SET name = :name WHERE id = :id AND accountId = :accountId")
    suspend fun updatePlaylistName(
        id: String,
        accountId: String,
        name: String,
    )

    @Query(
        "UPDATE playlists SET name = :name, description = :description, isPrivate = :isPrivate WHERE id = :id AND accountId = :accountId",
    )
    suspend fun updatePlaylistMetadata(
        id: String,
        accountId: String,
        name: String,
        description: String,
        isPrivate: Boolean,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistVideoCrossRef(crossRef: PlaylistVideoCrossRef)

    @Query("SELECT * FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND accountId = :accountId AND videoId IN (:videoIds)")
    suspend fun getCrossRefs(
        playlistId: String,
        accountId: String,
        videoIds: List<String>,
    ): List<PlaylistVideoCrossRef>

    /** Removes [videoIds] as one change and returns their entries, so the removal can be undone. */
    @Transaction
    suspend fun takePlaylistVideos(
        playlistId: String,
        accountId: String,
        videoIds: List<String>,
    ): List<PlaylistVideoCrossRef> {
        val taken = getCrossRefs(playlistId, accountId, videoIds)
        taken.forEach { removeVideoFromPlaylist(playlistId, it.videoId, accountId) }
        updatePlaylistThumbnail(playlistId, accountId, getFirstVideoThumbnail(playlistId, accountId).orEmpty())
        return taken
    }

    /** Adds a whole playlist with its entries as one change, so it never appears half filled. */
    @Transaction
    suspend fun insertPlaylistWithVideos(
        playlist: PlaylistEntity,
        entries: List<PlaylistVideoCrossRef>,
    ) {
        insertPlaylist(playlist)
        entries.forEach { insertPlaylistVideoCrossRef(it) }
    }

    /** Puts entries back where they were, with their original position and date added. */
    @Transaction
    suspend fun restorePlaylistVideos(entries: List<PlaylistVideoCrossRef>) {
        entries.forEach { insertPlaylistVideoCrossRef(it) }
        entries
            .map { it.playlistId to it.accountId }
            .distinct()
            .forEach { (playlistId, accountId) ->
                updatePlaylistThumbnail(playlistId, accountId, getFirstVideoThumbnail(playlistId, accountId).orEmpty())
            }
    }

    /** Applies a whole new order at once, so no intermediate order with duplicate positions is emitted. */
    @Transaction
    suspend fun reorderPlaylistVideos(
        playlistId: String,
        accountId: String,
        orderedVideoIds: List<String>,
    ) {
        orderedVideoIds.forEachIndexed { index, videoId -> updatePlaylistVideoPosition(playlistId, videoId, accountId, index.toLong()) }
        updatePlaylistThumbnail(playlistId, accountId, getFirstVideoThumbnail(playlistId, accountId).orEmpty())
    }

    /** Makes the playlist hold exactly [orderedVideoIds], in that order, as one change. */
    @Transaction
    suspend fun replacePlaylistVideos(
        playlistId: String,
        accountId: String,
        orderedVideoIds: List<String>,
    ) {
        val wanted = orderedVideoIds.toSet()
        getVideoIdsInPlaylist(playlistId, accountId).filterNot { it in wanted }.forEach {
            removeVideoFromPlaylist(playlistId, it, accountId)
        }
        orderedVideoIds.forEachIndexed { index, videoId ->
            insertPlaylistVideoCrossRef(
                PlaylistVideoCrossRef(
                    playlistId = playlistId,
                    videoId = videoId,
                    position = index.toLong(),
                    accountId = accountId,
                ),
            )
        }
        updatePlaylistThumbnail(playlistId, accountId, getFirstVideoThumbnail(playlistId, accountId).orEmpty())
    }

    @Query(
        "UPDATE playlist_video_cross_ref SET position = :position WHERE playlistId = :playlistId AND videoId = :videoId AND accountId = :accountId",
    )
    suspend fun updatePlaylistVideoPosition(
        playlistId: String,
        videoId: String,
        accountId: String,
        position: Long,
    )

    @Query("DELETE FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND videoId = :videoId AND accountId = :accountId")
    suspend fun removeVideoFromPlaylist(
        playlistId: String,
        videoId: String,
        accountId: String,
    )

    @Transaction
    @Query(
        "SELECT videos.* FROM videos INNER JOIN playlist_video_cross_ref ON videos.id = playlist_video_cross_ref.videoId WHERE playlist_video_cross_ref.playlistId = :playlistId AND playlist_video_cross_ref.accountId = :accountId ORDER BY playlist_video_cross_ref.position ASC",
    )
    fun getVideosForPlaylist(
        playlistId: String,
        accountId: String,
    ): Flow<List<VideoEntity>>

    @Query("SELECT playlistId FROM playlist_video_cross_ref WHERE videoId = :videoId AND accountId = :accountId")
    suspend fun getPlaylistIdsForVideo(
        videoId: String,
        accountId: String,
    ): List<String>

    @Transaction
    @Query(
        "SELECT videos.*, playlist_video_cross_ref.addedAt AS addedAt FROM videos INNER JOIN playlist_video_cross_ref ON videos.id = playlist_video_cross_ref.videoId WHERE playlist_video_cross_ref.playlistId = :playlistId AND playlist_video_cross_ref.accountId = :accountId ORDER BY playlist_video_cross_ref.position ASC",
    )
    fun getVideosWithMetaForPlaylist(
        playlistId: String,
        accountId: String,
    ): Flow<List<PlaylistVideoWithMeta>>

    @Query("SELECT COUNT(*) FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND accountId = :accountId")
    fun getPlaylistVideoCount(
        playlistId: String,
        accountId: String,
    ): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND videoId = :videoId AND accountId = :accountId")
    suspend fun isVideoInPlaylist(
        playlistId: String,
        videoId: String,
        accountId: String,
    ): Int

    @Query("SELECT videoId FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND accountId = :accountId")
    suspend fun getVideoIdsInPlaylist(
        playlistId: String,
        accountId: String,
    ): List<String>

    /** Null when the playlist is empty. */
    @Query("SELECT MAX(position) FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND accountId = :accountId")
    suspend fun getMaxPlaylistPosition(
        playlistId: String,
        accountId: String,
    ): Long?

    @Query(
        """
        SELECT COUNT(*) FROM playlist_video_cross_ref r
        INNER JOIN playlists p ON p.id = r.playlistId AND p.accountId = r.accountId
        WHERE r.videoId = :videoId AND r.accountId = :accountId AND p.isMusic = 0
    """,
    )
    fun getVideoPlaylistMembershipCount(
        videoId: String,
        accountId: String,
    ): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlists WHERE id = :id AND accountId = :accountId AND isUserCreated = 0")
    suspend fun isSavedExternalPlaylist(
        id: String,
        accountId: String,
    ): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE id = :id AND accountId = :accountId")
    suspend fun isPlaylistInDb(
        id: String,
        accountId: String,
    ): Int

    @Query("SELECT * FROM playlist_video_cross_ref WHERE accountId = :accountId")
    suspend fun getAllPlaylistVideoCrossRefs(accountId: String): List<PlaylistVideoCrossRef>

    /** Distinct videos saved across Watch Later and all non-music video playlists (excludes saved shorts). */
    @Query(
        """
        SELECT v.* FROM videos v
        INNER JOIN playlist_video_cross_ref r ON v.id = r.videoId
        INNER JOIN playlists p ON p.id = r.playlistId AND p.accountId = r.accountId
        WHERE r.accountId = :accountId AND p.isMusic = 0 AND p.id != 'saved_shorts'
        GROUP BY v.id
        ORDER BY MIN(r.position) ASC
    """,
    )
    suspend fun getSavedVideoPlaylistVideos(accountId: String): List<VideoEntity>

    @Query("UPDATE playlists SET thumbnailUrl = :thumbnailUrl WHERE id = :id AND accountId = :accountId")
    suspend fun updatePlaylistThumbnail(
        id: String,
        accountId: String,
        thumbnailUrl: String,
    )

    @Query(
        "SELECT v.thumbnailUrl FROM videos v INNER JOIN playlist_video_cross_ref r ON v.id = r.videoId WHERE r.playlistId = :playlistId AND r.accountId = :accountId ORDER BY r.position ASC LIMIT 1",
    )
    suspend fun getFirstVideoThumbnail(
        playlistId: String,
        accountId: String,
    ): String?

    /**
     * Returns stub VideoEntities inside music playlists that are missing a title OR a thumbnail.
     * Used for background enrichment — e.g. synced album tracks arrive with a title but no artwork.
     */
    @Query(
        """
        SELECT DISTINCT v.* FROM videos v
        INNER JOIN playlist_video_cross_ref r ON v.id = r.videoId
        INNER JOIN playlists p ON p.id = r.playlistId AND p.accountId = r.accountId
        WHERE r.accountId = :accountId AND p.isMusic = 1 AND (v.title = '' OR v.title IS NULL OR v.thumbnailUrl = '' OR v.thumbnailUrl IS NULL)
        LIMIT 200
    """,
    )
    suspend fun getMusicPlaylistStubVideos(accountId: String): List<VideoEntity>

    /** Music playlist/album ids whose cover is blank — recompute from the first track after enrichment. */
    @Query("SELECT id FROM playlists WHERE accountId = :accountId AND isMusic = 1 AND (thumbnailUrl = '' OR thumbnailUrl IS NULL)")
    suspend fun getMusicPlaylistsMissingThumbnail(accountId: String): List<String>
}
