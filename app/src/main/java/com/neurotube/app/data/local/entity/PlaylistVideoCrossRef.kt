package com.neurotube.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.neurotube.app.account.AccountScope

/**
 * Which videos a playlist holds, in order.
 *
 * The owner is part of the key and part of the playlist foreign key, so an entry can only ever point
 * at a playlist of its own account. The video side stays a plain reference to the shared `videos`
 * table: metadata is not account-specific, only membership is.
 */
@Entity(
    tableName = "playlist_video_cross_ref",
    primaryKeys = ["playlistId", "videoId", "accountId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id", "accountId"],
            childColumns = ["playlistId", "accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VideoEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["videoId"]), Index(value = ["playlistId"]), Index(value = ["accountId"])],
)
data class PlaylistVideoCrossRef(
    val playlistId: String,
    val videoId: String,
    // For custom ordering within playlist
    val position: Long,
    // When the video was added to this playlist
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "guest") val accountId: String = AccountScope.GUEST,
)
