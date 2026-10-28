/*
 * Copyright (C) 2026 NeuroTube | A-EDev
 *
 * This file is part of NeuroTube (https://github.com/NETO-DEV66/NeuroTube).
 *
 * NeuroTube is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package com.neurotube.app.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.neurotube.app.data.local.AccountScopedPreferences
import com.neurotube.app.data.local.AppDatabase
import com.neurotube.app.data.local.likedVideosDataStore
import com.neurotube.app.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.subscriptionsAdoptionStore: DataStore<Preferences> by safePreferencesDataStore(name = "subscriptions")

/**
 * Hands the signed-out library to the first account that is linked.
 *
 * Everything a user had before accounts existed is filed under [AccountScope.GUEST]. Without this,
 * linking an account would look like the library had been wiped, because the app would then read
 * that account's (empty) scope. Adoption copies the guest rows and keys into the account once; the
 * guest copy is left in place, so signing out still shows the library that was there before.
 *
 * A no-op when the account already owns a library — that happens when an account is unlinked and
 * linked again, and its rows are still on disk. Overwriting them with the guest library would lose
 * the account's own data.
 */
internal object GuestLibraryAdopter {
    private val TABLES = listOf("playlists", "playlist_video_cross_ref", "watch_history", "subscription_groups")

    suspend fun adopt(
        context: Context,
        accountId: String,
    ) {
        adoptRoomLibrary(context, accountId)
        adoptPreferenceLibrary(context, accountId)
    }

    private fun adoptRoomLibrary(
        context: Context,
        accountId: String,
    ) {
        val db = AppDatabase.getDatabase(context).openHelper.writableDatabase
        if (TABLES.any { hasOwnRows(db, it, accountId) }) return
        TABLES.forEach { table ->
            db.execSQL("UPDATE `$table` SET `accountId` = ? WHERE `accountId` = ?", arrayOf(accountId, AccountScope.GUEST))
        }
    }

    private fun hasOwnRows(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        accountId: String,
    ): Boolean =
        db.query("SELECT COUNT(*) FROM `$table` WHERE `accountId` = ?", arrayOf(accountId)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) > 0
        }

    /**
     * Copies the guest keys of the subscription and like stores into [accountId]'s namespace.
     *
     * Only the keys that belong to a library are copied, identified by their base name; a preference
     * that is not account-scoped (there is none in these stores today) is left alone rather than
     * guessed at.
     */
    private suspend fun adoptPreferenceLibrary(
        context: Context,
        accountId: String,
    ) {
        copyGuestKeys(
            source = context.subscriptionsAdoptionStore,
            accountId = accountId,
            isLibraryKey = { base -> base.startsWith("channel_") || base.startsWith("unsub_") || base == "subscriptions_order" },
        )
        copyGuestKeys(
            source = context.likedVideosDataStore,
            accountId = accountId,
            isLibraryKey = { base -> base.startsWith("video_") || base.startsWith("like_state_") || base == "liked_videos_order" },
        )
    }

    private suspend fun copyGuestKeys(
        source: DataStore<Preferences>,
        accountId: String,
        isLibraryKey: (String) -> Boolean,
    ) {
        val guestKeys =
            source.data
                .first()
                .asMap()
                .filterKeys { !it.name.startsWith(ACCOUNT_PREFIX) && isLibraryKey(it.name) }
        if (guestKeys.isEmpty()) return

        source.edit { preferences ->
            guestKeys.forEach { (key, value) ->
                val target = stringPreferencesKey(AccountScopedPreferences.key(key.name, accountId))
                when (value) {
                    is String -> preferences[target] = value
                    is Boolean -> preferences[booleanKey(key.name, accountId)] = value
                    is Long -> preferences[longKey(key.name, accountId)] = value
                    is Int -> preferences[intKey(key.name, accountId)] = value
                    is Float -> preferences[floatKey(key.name, accountId)] = value
                    else -> Unit
                }
            }
        }
    }

    private fun booleanKey(
        base: String,
        accountId: String,
    ) = androidx.datastore.preferences.core
        .booleanPreferencesKey(AccountScopedPreferences.key(base, accountId))

    private fun longKey(
        base: String,
        accountId: String,
    ) = androidx.datastore.preferences.core
        .longPreferencesKey(AccountScopedPreferences.key(base, accountId))

    private fun intKey(
        base: String,
        accountId: String,
    ) = androidx.datastore.preferences.core
        .intPreferencesKey(AccountScopedPreferences.key(base, accountId))

    private fun floatKey(
        base: String,
        accountId: String,
    ) = androidx.datastore.preferences.core
        .floatPreferencesKey(AccountScopedPreferences.key(base, accountId))

    private const val ACCOUNT_PREFIX = "acc:"
}
