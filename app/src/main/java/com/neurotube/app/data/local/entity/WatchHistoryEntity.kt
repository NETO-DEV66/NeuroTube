package com.neurotube.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.neurotube.app.account.AccountScope
import com.neurotube.app.data.local.VideoHistoryEntry
import com.neurotube.app.data.model.Video

/**
 * Room entity that replaces the previous DataStore-based watch history.
 *
 * The old DataStore approach stored ~8 preference keys per video, meaning
 * 46 000 watched videos → ~370 000 entries in a single serialised protobuf
 * file that was fully deserialized on every read.  SQLite handles millions of
 * rows efficiently with proper indexing.
 *
 * The key is `(videoId, accountId)`: the same video watched under two accounts is two histories with
 * two positions. A single-column key would let one account's progress overwrite the other's.
 */
@Entity(
    tableName = "watch_history",
    primaryKeys = ["videoId", "accountId"],
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["isMusic"]),
        Index(value = ["isShort"]),
        Index(value = ["isLocal"]),
        Index(value = ["accountId"]),
    ],
)
data class WatchHistoryEntity(
    val videoId: String,
    val position: Long,
    val duration: Long,
    val timestamp: Long,
    val title: String,
    val thumbnailUrl: String,
    val channelName: String,
    val channelId: String,
    val isMusic: Boolean,
    val isShort: Boolean = false,
    val isLocal: Boolean = false,
    /** The owning library; [AccountScope.GUEST] for the signed-out library. */
    @ColumnInfo(defaultValue = "guest") val accountId: String = AccountScope.GUEST,
) {
    fun toDomain() =
        VideoHistoryEntry(
            videoId = videoId,
            position = position,
            duration = duration,
            timestamp = timestamp,
            title = title,
            thumbnailUrl = thumbnailUrl,
            channelName = channelName,
            channelId = channelId,
            isMusic = isMusic,
            isShort = isShort,
            isLocal = isLocal,
        )

    /** Reconstruct a lightweight [Video] from history metadata (no stream info). */
    fun toVideo() =
        Video(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnailUrl,
            duration = if (duration > 0) (duration / 1000).toInt() else 0,
            viewCount = 0,
            uploadDate = "",
            isShort = isShort,
        )
}
