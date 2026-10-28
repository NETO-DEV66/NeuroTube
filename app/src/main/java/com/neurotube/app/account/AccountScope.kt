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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which account's library the app is reading and writing right now.
 *
 * The library repositories ask for this instead of taking an account as a parameter, because the
 * account is process-wide state, not a per-call argument: a playlist opened from a notification and
 * the same playlist opened from the Library tab must resolve to the same owner.
 *
 * [GUEST] is the library of someone who has not signed in. It is a real, persistent scope rather
 * than "no scope", so a device that is used signed out keeps its own library and hands it back when
 * the last account is removed.
 *
 * Data written before multi-account support existed is all in [GUEST]; [adoptGuestLibrary] moves it
 * to the first account that signs in, so an upgrade does not appear to lose the user's library.
 */
object AccountScope {
    /** The signed-out library. */
    const val GUEST = "guest"

    private val _active = MutableStateFlow(GUEST)

    /** The active account id, as a flow for anything that must rebuild when it changes. */
    val active: StateFlow<String> = _active.asStateFlow()

    /** The active account id, for repositories that only need the current value. */
    val activeId: String get() = _active.value

    /** True while a signed-in account owns the library. */
    val isAccount: Boolean get() = _active.value != GUEST

    /**
     * Points the library at [accountId], or at [GUEST] when it is null/blank.
     *
     * Returns true when the scope actually moved, so the caller can invalidate the caches that were
     * built for the previous owner.
     */
    fun set(accountId: String?): Boolean {
        val next = accountId?.takeIf { it.isNotBlank() } ?: GUEST
        if (next == _active.value) return false
        _active.value = next
        return true
    }
}
