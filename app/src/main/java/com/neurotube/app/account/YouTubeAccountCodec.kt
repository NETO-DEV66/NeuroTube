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

import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk shape of the linked sessions, kept apart from the encryption in [YouTubeAccountStore]
 * so the field mapping can be exercised without the Android keystore.
 *
 * The visitor identity is part of the session, not decoration: a cookie restored without it is
 * rejected by InnerTube, which is what made a saved sign-in come back signed out.
 *
 * The file used to hold a single account object. That shape is still read — as a one-entry list whose
 * only account is active — so an upgrade keeps the session that was already linked, and the next
 * write moves the file to the list shape.
 */
internal object YouTubeAccountCodec {
    fun encode(stored: StoredAccounts): ByteArray =
        JSONObject()
            .put("active_id", stored.activeId)
            .put(
                "accounts",
                JSONArray().apply {
                    stored.accounts.forEach { put(encodeAccount(it)) }
                },
            ).toString()
            .toByteArray(Charsets.UTF_8)

    fun decode(plaintext: ByteArray): StoredAccounts {
        val json = JSONObject(String(plaintext, Charsets.UTF_8))
        val accounts = json.optJSONArray("accounts")
        if (accounts == null) {
            // Single-account file: the linked session becomes the active one.
            val account = decodeAccount(json) ?: return StoredAccounts(emptyList(), null)
            return StoredAccounts(listOf(account), account.identity())
        }

        // One unreadable entry is dropped rather than failing the whole file: a corrupt account
        // must not sign the user out of the others.
        val decoded =
            (0 until accounts.length()).mapNotNull { index ->
                accounts.optJSONObject(index)?.let(::decodeAccount)
            }
        return StoredAccounts(decoded, json.optString("active_id").ifBlank { null })
    }

    private fun encodeAccount(account: YouTubeAccount): JSONObject =
        JSONObject()
            .put("name", account.name)
            .put("email", account.email)
            .put("channel_handle", account.channelHandle)
            .put("thumbnail_url", account.thumbnailUrl)
            .put("cookie", account.cookieHeader)
            .put("visitor_data", account.visitorData)
            .put("data_sync_id", account.dataSyncId)

    /** The account in [json], or null when it is missing the name or the cookie it cannot work without. */
    private fun decodeAccount(json: JSONObject): YouTubeAccount? =
        runCatching {
            YouTubeAccount(
                name = json.getString("name"),
                email = json.optString("email").ifBlank { null },
                channelHandle = json.optString("channel_handle").ifBlank { null },
                thumbnailUrl = json.optString("thumbnail_url").ifBlank { null },
                cookieHeader = json.getString("cookie"),
                visitorData = json.optString("visitor_data").ifBlank { null },
                dataSyncId = json.optString("data_sync_id").ifBlank { null },
            )
        }.getOrNull()
}
