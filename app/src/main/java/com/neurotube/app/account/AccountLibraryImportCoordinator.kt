package com.neurotube.app.account

import android.content.Context
import android.util.Log
import com.neurotube.app.R
import com.neurotube.app.data.local.PlayerPreferences
import com.neurotube.app.innertube.utils.toHex
import com.neurotube.app.notification.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AccountImportCoord"

/**
 * How long to wait after an account appears before reading its library. A cold start is still
 * fetching the visitor identity and warming the player, and a sign-in has just finished its own
 * round trip; this lets the request context settle so the first page is not rejected.
 */
private const val AUTO_IMPORT_SETTLE_MS = 2_000L

/**
 * Owns the account-library import for the whole process: it starts one automatically once an
 * account is signed in, keeps it running when the screen that started it goes away, and publishes
 * its progress so any surface can show it.
 *
 * The import is single-flight. A manual import from the account page and the automatic one after
 * sign-in go through the same entry point, so they can never run over each other, and the account
 * page, the onboarding step and the progress notification all read the same [state].
 *
 * An automatic import runs once per signed-in account: the account it last completed for is
 * remembered, so signing out and back in as the same person does not re-import, while a different
 * account does. A failed import is left un-remembered, so it is tried again on the next launch
 * rather than silently skipped.
 */
@Singleton
class AccountLibraryImportCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val libraryImport: AccountLibraryImport,
        private val preferences: PlayerPreferences,
        private val accountManager: YouTubeAccountManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _state = MutableStateFlow<AccountImportState>(AccountImportState.Idle)

        /** What the import is doing right now, shared by every surface that shows it. */
        val state: StateFlow<AccountImportState> = _state.asStateFlow()

        /** Serializes imports so the automatic and the manual one can never overlap. */
        private val mutex = Mutex()

        private var started = false

        /**
         * Starts watching for a signed-in account and importing its library once. Called once from
         * `FlowTubeApplication.onCreate`; safe to call again.
         */
        fun start() {
            if (started) return
            started = true
            scope.launch {
                accountManager.account.collect { account ->
                    if (account == null) return@collect
                    maybeAutoImport(account)
                }
            }
        }

        /**
         * Imports the signed-in account's library now, as the account page's button asks. Returns
         * false when an import is already running. The account is remembered, so a manual import
         * also stops the automatic one from running again for the same person.
         */
        fun importNow(): Boolean {
            if (_state.value is AccountImportState.Running) return false
            scope.launch {
                val id = accountManager.account.first()?.identity()
                runImport(rememberForAccount = id)
            }
            return true
        }

        /** Clears a finished import's result once it has been shown. */
        fun consumeResult() {
            val state = _state.value
            if (state is AccountImportState.Done || state is AccountImportState.Failed) {
                _state.value = AccountImportState.Idle
            }
        }

        private suspend fun maybeAutoImport(account: YouTubeAccount) {
            val id = account.identity()
            if (!preferences.accountAutoImportEnabled.first()) return
            if (preferences.accountAutoImportLastAccount.first() == id) return
            if (_state.value is AccountImportState.Running) return

            delay(AUTO_IMPORT_SETTLE_MS)

            // A manual import may have finished while this waited; it already recorded the account.
            if (_state.value is AccountImportState.Running) return
            if (preferences.accountAutoImportLastAccount.first() == id) return

            runImport(rememberForAccount = id)
        }

        private suspend fun runImport(rememberForAccount: String?) {
            mutex.withLock {
                if (_state.value is AccountImportState.Running) return

                val label = context.getString(R.string.youtube_account_import_notification_label)
                _state.value = AccountImportState.Running(AccountImportStep.SUBSCRIPTIONS)
                notifyProgress(label, _state.value as AccountImportState.Running)

                val report =
                    runCatching {
                        libraryImport.run { progress ->
                            _state.value = progress
                            notifyProgress(label, progress)
                        }
                    }.getOrNull()

                NotificationHelper.cancelImportNotification(context, NotificationHelper.NOTIFICATION_ACCOUNT_IMPORT_PROGRESS)

                if (report == null) {
                    _state.value = AccountImportState.Failed
                    return
                }

                _state.value = AccountImportState.Done(report)
                rememberForAccount?.let { id ->
                    runCatching { preferences.setAccountAutoImportLastAccount(id) }
                        .onFailure { Log.w(TAG, "Could not remember the imported account", it) }
                }

                if (NotificationHelper.hasNotificationPermission(context)) {
                    NotificationHelper.showImportComplete(
                        context = context,
                        label = label,
                        count = 0,
                        message = summary(report),
                        progressId = NotificationHelper.NOTIFICATION_ACCOUNT_IMPORT_PROGRESS,
                        completionId = NotificationHelper.NOTIFICATION_ACCOUNT_IMPORT_COMPLETE,
                    )
                }
            }
        }

        private fun notifyProgress(
            label: String,
            progress: AccountImportState.Running,
        ) {
            if (!NotificationHelper.hasNotificationPermission(context)) return
            NotificationHelper.showImportProgress(
                context = context,
                label = label,
                current = progress.current,
                total = progress.total,
                notificationId = NotificationHelper.NOTIFICATION_ACCOUNT_IMPORT_PROGRESS,
            )
        }

        private fun summary(report: AccountImportReport): String =
            context.getString(
                R.string.youtube_account_import_done_body,
                report.channels,
                report.artists,
                report.likedSongs,
                report.playlists,
                report.albums,
                report.videoPlaylists,
                report.watchLater,
                report.history,
            )
    }

/**
 * A stable, non-identifying id for the signed-in account, so "imported already" survives a restart.
 *
 * The remembered value is a digest, not the address itself: preferences are not encrypted at rest,
 * and the import only needs to tell one account from another, never to name it.
 */
internal fun YouTubeAccount.identity(): String {
    val identifying =
        email?.takeIf { it.isNotBlank() }
            ?: channelHandle?.takeIf { it.isNotBlank() }
            ?: name
    return MessageDigest.getInstance("SHA-256").digest(identifying.toByteArray(Charsets.UTF_8)).toHex()
}
