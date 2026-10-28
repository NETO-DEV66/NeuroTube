package com.neurotube.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.neurotube.app.account.AccountScope

/**
 * A playlist row, owned by one account.
 *
 * The key is `(id, accountId)` rather than `id` alone: a YouTube playlist id is only unique within
 * the account that saved it, and two linked accounts can both save the same public playlist. A
 * single-column key would make the second save overwrite the first.
 */
@Entity(
    tableName = "playlists",
    primaryKeys = ["id", "accountId"],
    indices = [Index(value = ["syncId"]), Index(value = ["accountId"])],
)
data class PlaylistEntity(
    val id: String,
    val name: String,
    val description: String,
    val thumbnailUrl: String,
    val isPrivate: Boolean,
    val createdAt: Long,
    val videoCount: Int = 0, // Denormalized count for performance
    val isMusic: Boolean = false,
    val isUserCreated: Boolean = true,
    val syncId: String? = null,
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    /** The owning library; [AccountScope.GUEST] for the signed-out library. */
    @ColumnInfo(defaultValue = "guest") val accountId: String = AccountScope.GUEST,
)
