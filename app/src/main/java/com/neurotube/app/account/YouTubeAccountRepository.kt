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
import android.util.Log
import com.neurotube.app.data.engagement.FeedInvalidationBus
import com.neurotube.app.data.local.PlayerPreferences
import com.neurotube.app.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the signed-in YouTube sessions for the whole process.
 *
 * The app already talks to YouTube through the [`YouTube`] singleton, so the active account's cookie
 * is applied there rather than passed around: every request that asks for login (`accountMenu`,
 * likes, subscriptions, …) starts sending `SAPISIDHASH` as soon as a session exists, and stops the
 * moment the last one is removed.
 *
 * Up to [MAX_ACCOUNTS] accounts can be linked at once. Exactly one of them is active, and the
 * library the app reads is the active one's — see [AccountScope]. Switching is a session swap plus a
 * scope move, not a re-authentication, which is why the stored list keeps every cookie.
 *
 * [initialize] must run before any request needs a session — it is called from
 * `FlowTubeApplication.onCreate`.
 */
object YouTubeAccountRepository {
    private const val TAG = "YouTubeAccount"

    /** How many accounts can be linked at once. */
    const val MAX_ACCOUNTS = 5

    private lateinit var store: YouTubeAccountStore

    private val _accounts = MutableStateFlow<List<YouTubeAccount>>(emptyList())

    /** Every linked account, in the order they were added. */
    val accounts: StateFlow<List<YouTubeAccount>> = _accounts.asStateFlow()

    private val _account = MutableStateFlow<YouTubeAccount?>(null)

    /** The active account, or `null` when no account is linked. */
    val account: StateFlow<YouTubeAccount?> = _account.asStateFlow()

    /** Serializes sign-in/switch/remove so a slow account-menu round trip cannot clobber a newer state. */
    private val mutex = Mutex()

    private var preferences: PlayerPreferences? = null

    /** Held so adoption can reach the database and the library stores. */
    private var appContext: Context? = null

    /**
     * Set from the Hilt graph by [AccountCacheInvalidator]; used when the active account moves so
     * the caches built for the previous owner are dropped.
     */
    @Volatile
    private var cacheInvalidator: AccountCacheInvalidator? = null

    /** Wires the cache clear that runs when the active account changes. */
    fun bindCacheInvalidator(invalidator: AccountCacheInvalidator) {
        cacheInvalidator = invalidator
    }

    /** Loads the saved sessions and starts applying the active one. Safe to call more than once. */
    fun initialize(context: Context) {
        if (::store.isInitialized) return
        store = YouTubeAccountStore(context)
        appContext = context.applicationContext
        preferences = PlayerPreferences(context)
        val stored = store.load()
        _accounts.value = stored.accounts
        stored.active()?.let(::activate)
    }

    val isSignedIn: Boolean get() = _account.value != null

    /** True once no further account can be linked. */
    val atCapacity: Boolean get() = _accounts.value.size >= MAX_ACCOUNTS

    /**
     * Validates a freshly captured session against the account menu, then links it.
     *
     * Validation is what proves the cookie is a real, working session rather than a half-finished
     * login: `accountInfo()` only returns a header when YouTube accepts the credential.
     *
     * Signing in an account that is already linked refreshes its cookie and makes it active instead
     * of adding a duplicate.
     */
    suspend fun signIn(session: YouTubeLoginSession): Result<YouTubeAccount> =
        mutex.withLock {
            val cookieHeader =
                YouTubeCookieExtractor.toCookieHeader(session.cookieHeader)
                    ?: return@withLock Result.failure(
                        IllegalArgumentException("Captured cookies do not contain a YouTube session"),
                    )
            if (!YouTubeCookieExtractor.hasSession(cookieHeader)) {
                return@withLock Result.failure(
                    IllegalArgumentException("Captured cookies do not contain a YouTube session"),
                )
            }

            val previous = SessionSnapshot(YouTube.cookie, YouTube.visitorData, YouTube.dataSyncId)
            YouTube.cookie = cookieHeader
            // The identity the session was minted with has to be in place before the first call,
            // because InnerTube echoes it back as the request context.
            session.visitorData?.let { YouTube.visitorData = it }
            session.dataSyncId?.let { YouTube.dataSyncId = it }

            val info = YouTube.accountInfo()
            if (info.isFailure) {
                previous.restore()
                return@withLock Result.failure(
                    info.exceptionOrNull() ?: IllegalStateException("YouTube rejected the session"),
                )
            }

            val account =
                info.getOrThrow().let { header ->
                    YouTubeAccount(
                        name = header.name,
                        email = header.email,
                        channelHandle = header.channelHandle,
                        thumbnailUrl = header.thumbnailUrl,
                        cookieHeader = cookieHeader,
                        visitorData = session.visitorData,
                        dataSyncId = session.dataSyncId,
                    )
                }

            val id = account.identity()
            val current = _accounts.value
            val alreadyLinked = current.any { it.identity() == id }
            if (!alreadyLinked && current.size >= MAX_ACCOUNTS) {
                previous.restore()
                return@withLock Result.failure(TooManyAccountsException())
            }

            val updated =
                if (alreadyLinked) {
                    current.map { if (it.identity() == id) account else it }
                } else {
                    current + account
                }
            persist(updated, activeId = id)

            // The library that predates multi-account support belongs to the first account linked,
            // so an upgrade does not look like a wiped library.
            if (!alreadyLinked) adoptGuestLibraryIfNeeded(id)

            activate(account)
            notifyAccountChanged()
            Result.success(account)
        }

    /**
     * Makes an already-linked account the active one. Returns false when [accountId] is not linked,
     * so a stale UI cannot leave the app pointing at a session that no longer exists.
     */
    suspend fun switchTo(accountId: String): Boolean =
        mutex.withLock {
            val target = _accounts.value.firstOrNull { it.identity() == accountId } ?: return@withLock false
            if (_account.value?.identity() == accountId) return@withLock true
            persist(_accounts.value, activeId = accountId)
            activate(target)
            cacheInvalidator?.clearForAccountChange()
            notifyAccountChanged()
            true
        }

    /**
     * Unlinks [accountId] and drops its session. The account's library stays on disk, so linking the
     * same account again brings it back rather than starting over.
     *
     * When the active account is removed, the next linked one takes over; with none left the app
     * falls back to the signed-out library.
     */
    suspend fun remove(accountId: String) =
        mutex.withLock {
            val remaining = _accounts.value.filterNot { it.identity() == accountId }
            if (remaining.size == _accounts.value.size) return@withLock

            val activeRemoved = _account.value?.identity() == accountId
            val next = if (activeRemoved) remaining.firstOrNull() else _account.value
            persist(remaining, activeId = next?.identity())
            if (next != null) activate(next) else deactivate()
            if (activeRemoved) cacheInvalidator?.clearForAccountChange()
            notifyAccountChanged()
        }

    /** Unlinks the active account. Kept for the account page's single "sign out" action. */
    suspend fun signOut() {
        _account.value?.identity()?.let { remove(it) }
    }

    private suspend fun persist(
        accounts: List<YouTubeAccount>,
        activeId: String?,
    ) {
        _accounts.value = accounts
        if (accounts.isEmpty()) {
            runCatching { store.clear() }
        } else {
            runCatching { store.save(StoredAccounts(accounts, activeId)) }
                .onFailure { Log.w(TAG, "Could not persist the sessions", it) }
        }
    }

    /** Moves the signed-out library to [accountId] once, when the first account is linked. */
    private suspend fun adoptGuestLibraryIfNeeded(accountId: String) {
        val prefs = preferences ?: return
        val context = appContext ?: return
        runCatching {
            if (prefs.isGuestLibraryAdopted.first()) return@runCatching
            GuestLibraryAdopter.adopt(context, accountId)
            prefs.setGuestLibraryAdopted()
        }.onFailure { Log.w(TAG, "Could not adopt the signed-out library", it) }
    }

    private fun notifyAccountChanged() {
        FeedInvalidationBus.emit(FeedInvalidationBus.Event.AccountChanged)
    }

    /** Applies [account]'s session and points the library at it. */
    private fun activate(account: YouTubeAccount) {
        YouTube.cookie = account.cookieHeader
        account.visitorData?.let { YouTube.visitorData = it }
        YouTube.dataSyncId = account.dataSyncId
        // Browse and search ask for login only when this is on, which is what makes the signed-in
        // library (subscriptions, likes, playlists) come back personalised instead of anonymous.
        YouTube.useLoginForBrowse = true
        _account.value = account
        AccountScope.set(account.identity())
    }

    private fun deactivate() {
        YouTube.cookie = null
        YouTube.dataSyncId = null
        YouTube.useLoginForBrowse = false
        _account.value = null
        AccountScope.set(null)
    }

    /** Thrown when a sixth account is linked while five are already active. */
    class TooManyAccountsException : IllegalStateException("At most $MAX_ACCOUNTS accounts can be linked")

    /** The three request-scoped values a session owns, so a rejected sign-in can put them back. */
    private class SessionSnapshot(
        private val cookie: String?,
        private val visitorData: String?,
        private val dataSyncId: String?,
    ) {
        fun restore() {
            YouTube.cookie = cookie
            YouTube.visitorData = visitorData
            YouTube.dataSyncId = dataSyncId
        }
    }
}
