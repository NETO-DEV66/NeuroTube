package com.neurotube.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.neurotube.app.account.AccountScope

/**
 * A user-made group of subscribed channels.
 *
 * The key is `(name, accountId)`: group names are user-chosen, so "Music" is expected to exist in
 * more than one account, and the group itself is a view of that account's subscriptions.
 */
@Entity(
    tableName = "subscription_groups",
    primaryKeys = ["name", "accountId"],
    indices = [Index(value = ["accountId"])],
)
data class SubscriptionGroupEntity(
    val name: String,
    val channelIds: String = "",
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "guest") val accountId: String = AccountScope.GUEST,
)
