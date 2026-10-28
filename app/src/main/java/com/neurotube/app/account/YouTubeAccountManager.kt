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

import android.app.Activity
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place the UI talks to for "is a YouTube account linked, and how do I link one".
 *
 * [signIn] drives [YouTubeLoginActivity] and blocks until the WebView has captured a session, the
 * user gave up, or the attempt timed out — so callers can simply render the result.
 */
@Singleton
class YouTubeAccountManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val cacheInvalidator: AccountCacheInvalidator,
    ) {
        init {
            // The repository is the object that knows when the active account moves; hand it the
            // cache clear so a switch cannot leave the previous account's feed behind.
            YouTubeAccountRepository.bindCacheInvalidator(cacheInvalidator)
        }

        /** The signed-in account, or `null` when signed out. */
        val account: StateFlow<YouTubeAccount?> = YouTubeAccountRepository.account

        /** Every linked account, in the order they were added. */
        val accounts: StateFlow<List<YouTubeAccount>> = YouTubeAccountRepository.accounts

        /** How many accounts can be linked at once. */
        val maxAccounts: Int get() = YouTubeAccountRepository.MAX_ACCOUNTS

        /** True once no further account can be linked. */
        val atCapacity: Boolean get() = YouTubeAccountRepository.atCapacity

        val isSignedIn: Boolean get() = YouTubeAccountRepository.isSignedIn

        /** Makes an already-linked account active, switching the library the app reads. */
        suspend fun switchTo(accountId: String): Boolean = YouTubeAccountRepository.switchTo(accountId)

        /** Unlinks one account, keeping its library on disk. */
        suspend fun remove(accountId: String) = YouTubeAccountRepository.remove(accountId)

        /** The identity of [account], as the account list and preferences key it. */
        fun identityOf(account: YouTubeAccount): String = account.identity()

        /**
         * Opens the Google sign-in WebView and validates whatever it captures.
         *
         * [activity] is required because a WebView needs an activity-hosted window.
         */
        suspend fun signIn(activity: Activity?): Result<YouTubeAccount> {
            if (activity == null) {
                return Result.failure(IllegalStateException("No activity to show sign-in in"))
            }
            if (YouTubeLoginBroker.isPending()) {
                return Result.failure(IllegalStateException("A sign-in is already in progress"))
            }

            val deferred = YouTubeLoginBroker.begin()
            activity.startActivity(Intent(activity, YouTubeLoginActivity::class.java))

            val captured =
                withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) { deferred.await() }
                    ?: run {
                        YouTubeLoginBroker.fail("Timed out")
                        return Result.failure(IllegalStateException("Sign-in timed out"))
                    }

            return captured.fold(
                onSuccess = { session -> YouTubeAccountRepository.signIn(session) },
                onFailure = { Result.failure(it) },
            )
        }

        suspend fun signOut() = YouTubeAccountRepository.signOut()

        private companion object {
            /** Generous: the user may be typing a password or solving a second factor. */
            const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L
        }
    }
