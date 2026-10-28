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

package com.neurotube.app.data.local

import com.neurotube.app.account.AccountScope

/**
 * Namespaces the library preference keys by the active account.
 *
 * The signed-out library keeps the historical, unprefixed keys so an existing install is never
 * orphaned by an upgrade. Every linked account writes under its own id instead, which is what makes
 * one account's subscriptions and likes invisible to the next.
 *
 * The account prefix is [ACCOUNT_PREFIX] plus the id, so a key written by an account is always
 * recognisable from its name alone — that is what lets a whole-store scan (tombstones, dislike
 * states) tell the active account's keys from the others' without keeping a key list anywhere.
 */
internal object AccountScopedPreferences {
    /** Marks a key as belonging to a linked account rather than to the signed-out library. */
    private const val ACCOUNT_PREFIX = "acc:"

    /** The key prefix for [scope]; empty for the signed-out library. */
    fun prefix(scope: String = AccountScope.activeId): String = if (scope == AccountScope.GUEST) "" else "$ACCOUNT_PREFIX$scope:"

    fun key(
        base: String,
        scope: String = AccountScope.activeId,
    ): String = prefix(scope) + base

    /** True when [name] is one of [scope]'s keys and not another account's or the guest library's. */
    fun belongsTo(
        name: String,
        scope: String = AccountScope.activeId,
    ): Boolean =
        if (scope == AccountScope.GUEST) {
            !name.startsWith(ACCOUNT_PREFIX)
        } else {
            name.startsWith("$ACCOUNT_PREFIX$scope:")
        }

    /** [name] with the active scope's prefix removed, so the base key can be inspected. */
    fun baseName(
        name: String,
        scope: String = AccountScope.activeId,
    ): String = name.removePrefix(prefix(scope))
}
