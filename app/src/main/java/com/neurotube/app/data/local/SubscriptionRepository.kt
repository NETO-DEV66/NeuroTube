package com.neurotube.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.neurotube.app.account.AccountScope
import com.neurotube.app.data.engagement.FeedInvalidationBus
import com.neurotube.app.data.local.AppDatabase
import com.neurotube.app.utils.ThumbnailUrlResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.subscriptionsDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "subscriptions")

class SubscriptionRepository private constructor(
    private val context: Context,
) {
    companion object {
        @Volatile
        private var instance: SubscriptionRepository? = null

        fun getInstance(context: Context): SubscriptionRepository =
            instance ?: synchronized(this) {
                instance ?: SubscriptionRepository(context.applicationContext).also { instance = it }
            }

        // Keys format: "channel_{channelId}" -> JSON string with channel info. Both the channel and
        // the order keys carry the active account's prefix, so one account's subscriptions are
        // invisible to the next.
        private fun channelKey(channelId: String) = stringPreferencesKey(AccountScopedPreferences.key("channel_$channelId"))

        /** "unsub_{channelId}" -> epoch ms the user unsubscribed. See [unsubscribedTombstones]. */
        private fun unsubscribedKey(channelId: String) = stringPreferencesKey(AccountScopedPreferences.key("unsub_$channelId"))

        private const val UNSUBSCRIBED_PREFIX = "unsub_"

        private fun orderKey() = stringPreferencesKey(AccountScopedPreferences.key(SUBSCRIPTIONS_ORDER_KEY))

        private const val SUBSCRIPTIONS_ORDER_KEY = "subscriptions_order"

        /**
         * How long an unsubscribe is remembered. Device sync resolves subscribe-vs-unsubscribe by
         * comparing their timestamps, so forgetting a tombstone lets a peer that still holds the
         * subscription resurrect it. Long enough that any realistic sync gap is covered, bounded so
         * the store cannot grow without limit.
         */
        private const val UNSUBSCRIBE_TOMBSTONE_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }

    /**
     * Subscribe to a channel
     */
    suspend fun subscribe(channel: ChannelSubscription) {
        context.subscriptionsDataStore.edit { preferences ->
            val safeChannel = channel.withPreservedThumbnail(preferences)

            // Save channel data
            preferences[channelKey(safeChannel.channelId)] = serializeChannel(safeChannel)
            preferences.remove(unsubscribedKey(safeChannel.channelId)) // re-subscribing clears the tombstone

            // Update order list
            val currentOrder = preferences[orderKey()] ?: ""
            val orderList =
                if (currentOrder.isEmpty()) {
                    mutableListOf()
                } else {
                    currentOrder.split(",").toMutableList()
                }

            if (!orderList.contains(safeChannel.channelId)) {
                orderList.add(0, safeChannel.channelId)
                preferences[orderKey()] = orderList.joinToString(",")
            }
        }
    }

    suspend fun subscribeAll(channels: Collection<ChannelSubscription>) {
        if (channels.isEmpty()) return

        context.subscriptionsDataStore.edit { preferences ->
            val currentOrder =
                preferences[orderKey()]
                    .orEmpty()
                    .split(",")
                    .filter { it.isNotEmpty() }
            val knownIds = currentOrder.toMutableSet()
            val newIds = mutableListOf<String>()

            channels.forEach { channel ->
                val safeChannel = channel.withPreservedThumbnail(preferences)
                preferences[channelKey(safeChannel.channelId)] = serializeChannel(safeChannel)
                preferences.remove(unsubscribedKey(safeChannel.channelId))
                if (knownIds.add(safeChannel.channelId)) {
                    newIds += safeChannel.channelId
                }
            }

            if (newIds.isNotEmpty()) {
                preferences[orderKey()] =
                    (newIds.asReversed() + currentOrder).joinToString(",")
            }
        }
    }

    /**
     * Unsubscribe from a channel
     */
    suspend fun unsubscribe(channelId: String) {
        val now = System.currentTimeMillis()
        context.subscriptionsDataStore.edit { preferences ->
            preferences.remove(channelKey(channelId))
            // Remember *when*, so device sync can tell an unsubscribe from "this device never knew
            // about the channel" and the removal actually reaches the other device.
            preferences[unsubscribedKey(channelId)] = now.toString()
            prune(preferences, now, AccountScope.activeId)

            // Update order list
            val currentOrder = preferences[orderKey()] ?: ""
            if (currentOrder.isNotEmpty()) {
                val orderList = currentOrder.split(",").toMutableList()
                orderList.remove(channelId)
                preferences[orderKey()] = orderList.joinToString(",")
            }
        }

        AppDatabase
            .getDatabase(context)
            .cacheDao()
            .deleteSubscriptionFeedForChannel(channelId)
        FeedInvalidationBus.emit(FeedInvalidationBus.Event.ChannelUnsubscribed(channelId))
    }

    /**
     * Check if subscribed to a channel
     */
    fun isSubscribed(channelId: String): Flow<Boolean> =
        context.subscriptionsDataStore.data.map { preferences ->
            preferences.contains(channelKey(channelId))
        }

    /**
     * Get all subscriptions
     */
    fun getAllSubscriptions(): Flow<List<ChannelSubscription>> =
        context.subscriptionsDataStore.data.map { preferences ->
            val orderString = preferences[orderKey()] ?: ""
            if (orderString.isEmpty()) {
                emptyList()
            } else {
                val orderList = orderString.split(",")
                orderList.mapNotNull { channelId ->
                    val channelData = preferences[channelKey(channelId)]
                    channelData?.let { deserializeChannel(it) }
                }
            }
        }

    /**
     * Get all subscription IDs as a Set
     */
    suspend fun getAllSubscriptionIds(): Set<String> {
        val orderString =
            context.subscriptionsDataStore.data
                .map { preferences ->
                    preferences[orderKey()] ?: ""
                }.first()

        return if (orderString.isEmpty()) {
            emptySet()
        } else {
            orderString.split(",").toSet()
        }
    }

    /**
     * Channels this device has explicitly unsubscribed from, mapped to when it happened.
     *
     * Device sync ships these as tombstones. Without them an unsubscribe is indistinguishable from
     * "never subscribed", and the peer that still holds the channel puts it straight back.
     */
    suspend fun unsubscribedTombstones(): Map<String, Long> {
        // Captured once so a scope change mid-scan cannot mix two accounts' tombstones.
        val scope = AccountScope.activeId
        return context.subscriptionsDataStore.data
            .map { preferences ->
                preferences
                    .asMap()
                    .mapNotNull { (key, value) ->
                        if (!AccountScopedPreferences.belongsTo(key.name, scope)) return@mapNotNull null
                        val base = AccountScopedPreferences.baseName(key.name, scope)
                        if (!base.startsWith(UNSUBSCRIBED_PREFIX)) return@mapNotNull null
                        val at = (value as? String)?.toLongOrNull() ?: return@mapNotNull null
                        base.removePrefix(UNSUBSCRIBED_PREFIX) to at
                    }.toMap()
            }.first()
    }

    /** Record a peer's unsubscribe so it keeps propagating to any third device. */
    suspend fun recordUnsubscribedAt(tombstones: Map<String, Long>) {
        if (tombstones.isEmpty()) return
        val now = System.currentTimeMillis()
        val scope = AccountScope.activeId
        context.subscriptionsDataStore.edit { preferences ->
            tombstones.forEach { (channelId, at) ->
                val key = unsubscribedKey(channelId)
                val existing = preferences[key]?.toLongOrNull() ?: 0L
                if (at > existing) preferences[key] = at.toString()
            }
            prune(preferences, now, scope)
        }
    }

    /**
     * Drop tombstones past the retention window so the store cannot grow without limit.
     *
     * Only [scope]'s tombstones are considered: the store holds every account at once, and expiring
     * another account's unsubscribes while signed in as this one would resurrect them there.
     */
    private fun prune(
        preferences: MutablePreferences,
        now: Long,
        scope: String,
    ) {
        val cutoff = now - UNSUBSCRIBE_TOMBSTONE_RETENTION_MS
        preferences
            .asMap()
            .keys
            .filter { AccountScopedPreferences.belongsTo(it.name, scope) }
            .filter { AccountScopedPreferences.baseName(it.name, scope).startsWith(UNSUBSCRIBED_PREFIX) }
            .forEach { key ->
                val at = (preferences[key] as? String)?.toLongOrNull()
                if (at == null || at < cutoff) preferences.remove(key)
            }
    }

    /**
     * Get subscription by channel ID
     */
    fun getSubscription(channelId: String): Flow<ChannelSubscription?> =
        context.subscriptionsDataStore.data.map { preferences ->
            val channelData = preferences[channelKey(channelId)]
            channelData?.let { deserializeChannel(it) }
        }

    suspend fun repairVideoThumbnailSubscriptions(fetchChannelThumbnail: suspend (String) -> String): Int {
        val subscriptions = getAllSubscriptions().first()
        val repairs =
            subscriptions
                .filter { ThumbnailUrlResolver.isUnusableChannelAvatar(it.channelThumbnail) }
                .mapNotNull { subscription ->
                    val avatar = fetchChannelThumbnail(subscription.channelId).trim()
                    if (avatar.isNotEmpty() && !ThumbnailUrlResolver.isUnusableChannelAvatar(avatar)) {
                        subscription.channelId to subscription.copy(channelThumbnail = avatar)
                    } else {
                        null
                    }
                }.toMap()

        if (repairs.isEmpty()) return 0

        context.subscriptionsDataStore.edit { preferences ->
            repairs.forEach { (channelId, subscription) ->
                preferences[channelKey(channelId)] = serializeChannel(subscription)
            }
        }
        return repairs.size
    }

    private fun serializeChannel(channel: ChannelSubscription): String = SubscriptionRecordCodec.encode(channel)

    private fun ChannelSubscription.withPreservedThumbnail(preferences: Preferences): ChannelSubscription {
        val existing = preferences[channelKey(channelId)]?.let { deserializeChannel(it) }
        return if (
            ThumbnailUrlResolver.isUnusableChannelAvatar(channelThumbnail) &&
            existing?.channelThumbnail?.isNotBlank() == true &&
            !ThumbnailUrlResolver.isUnusableChannelAvatar(existing.channelThumbnail)
        ) {
            copy(channelThumbnail = existing.channelThumbnail)
        } else {
            this
        }
    }

    private fun deserializeChannel(data: String): ChannelSubscription? = SubscriptionRecordCodec.decode(data)

    /**
     * Update the notification state for a channel
     */
    suspend fun updateNotificationState(
        channelId: String,
        enabled: Boolean,
    ) {
        context.subscriptionsDataStore.edit { preferences ->
            val channelData = preferences[channelKey(channelId)]
            if (channelData != null) {
                val subscription = deserializeChannel(channelData)
                if (subscription != null) {
                    val updated = subscription.copy(isNotificationEnabled = enabled)
                    preferences[channelKey(channelId)] = serializeChannel(updated)
                }
            }
        }
    }

    /**
     * Record that the subscription feed has just fetched these channels.
     *
     * Written in one [DataStore] transaction so a refresh over hundreds of channels does not
     * produce hundreds of preference commits.
     */
    suspend fun markFeedFetched(
        channelIds: Collection<String>,
        fetchedAt: Long,
    ) {
        if (channelIds.isEmpty()) return

        context.subscriptionsDataStore.edit { preferences ->
            channelIds.forEach { channelId ->
                val subscription = preferences[channelKey(channelId)]?.let { deserializeChannel(it) }
                if (subscription != null) {
                    preferences[channelKey(channelId)] =
                        serializeChannel(subscription.copy(lastFeedFetchAt = fetchedAt))
                }
            }
        }
    }

    /**
     * Update the last seen video for a channel
     */
    suspend fun updateChannelLatestVideo(
        channelId: String,
        videoId: String,
    ) {
        context.subscriptionsDataStore.edit { preferences ->
            val channelData = preferences[channelKey(channelId)]
            if (channelData != null) {
                val subscription = deserializeChannel(channelData)
                if (subscription != null) {
                    val updated =
                        subscription.copy(
                            lastVideoId = videoId,
                            lastCheckTime = System.currentTimeMillis(),
                        )
                    preferences[channelKey(channelId)] = serializeChannel(updated)
                }
            }
        }
    }
}

data class ChannelSubscription(
    val channelId: String,
    val channelName: String,
    val channelThumbnail: String,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastVideoId: String? = null,
    /** When the background new-upload check last saw a *new* video for this channel. */
    val lastCheckTime: Long = 0L,
    val isNotificationEnabled: Boolean = false,
    val isMusic: Boolean = false,
    /** When the subscription feed last fetched this channel; 0 means never. */
    val lastFeedFetchAt: Long = 0L,
)
