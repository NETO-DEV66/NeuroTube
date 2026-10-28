package com.neurotube.app.ui.screens.subscriptions

import com.neurotube.app.account.AccountScope
import com.neurotube.app.data.local.dao.SubscriptionGroupDao
import com.neurotube.app.data.local.entity.SubscriptionGroupEntity

internal suspend fun SubscriptionGroupDao.appendGroup(
    name: String,
    channelIds: List<String>,
    accountId: String = AccountScope.activeId,
) {
    insertGroup(
        SubscriptionGroupEntity(
            name = name,
            channelIds = channelIds.joinToString(","),
            sortOrder = getAllGroupsOnce(accountId).size,
            accountId = accountId,
        ),
    )
}

/** Returns false when no group is called [oldName]. A rename is a delete and insert because the name is the key. */
internal suspend fun SubscriptionGroupDao.editGroup(
    oldName: String,
    newName: String,
    channelIds: List<String>,
    accountId: String = AccountScope.activeId,
): Boolean {
    val existing = getAllGroupsOnce(accountId).find { it.name == oldName } ?: return false
    if (oldName != newName) {
        deleteGroup(oldName, accountId)
        insertGroup(existing.copy(name = newName, channelIds = channelIds.joinToString(",")))
    } else {
        updateGroup(existing.copy(channelIds = channelIds.joinToString(",")))
    }
    return true
}

internal suspend fun SubscriptionGroupDao.moveGroup(
    fromIndex: Int,
    toIndex: Int,
    accountId: String = AccountScope.activeId,
) {
    val groups = getAllGroupsOnce(accountId).toMutableList()
    if (fromIndex !in groups.indices || toIndex !in groups.indices || fromIndex == toIndex) return
    groups.add(toIndex, groups.removeAt(fromIndex))
    insertAll(groups.mapIndexed { index, group -> group.copy(sortOrder = index) })
}
