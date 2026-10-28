package com.neurotube.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.neurotube.app.data.local.entity.SubscriptionGroupEntity
import kotlinx.coroutines.flow.Flow

/**
 * Channel groups, scoped to one account: a group is a saved view of that account's subscriptions,
 * so it cannot be shared between accounts.
 */
@Dao
interface SubscriptionGroupDao {
    @Query("SELECT * FROM subscription_groups WHERE accountId = :accountId ORDER BY sortOrder ASC")
    fun getAllGroups(accountId: String): Flow<List<SubscriptionGroupEntity>>

    @Query("SELECT * FROM subscription_groups WHERE accountId = :accountId ORDER BY sortOrder ASC")
    suspend fun getAllGroupsOnce(accountId: String): List<SubscriptionGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: SubscriptionGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(groups: List<SubscriptionGroupEntity>)

    @Update
    suspend fun updateGroup(group: SubscriptionGroupEntity)

    @Query("DELETE FROM subscription_groups WHERE name = :name AND accountId = :accountId")
    suspend fun deleteGroup(
        name: String,
        accountId: String,
    )

    @Query("SELECT EXISTS(SELECT 1 FROM subscription_groups WHERE name = :name AND accountId = :accountId)")
    suspend fun exists(
        name: String,
        accountId: String,
    ): Boolean
}
