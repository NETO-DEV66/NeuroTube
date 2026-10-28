package com.neurotube.app.ui.screens.settings.account

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neurotube.app.R
import com.neurotube.app.account.AccountImportReport
import com.neurotube.app.account.AccountImportState
import com.neurotube.app.account.YouTubeAccount
import com.neurotube.app.account.identity
import com.neurotube.app.account.titleRes
import com.neurotube.app.ui.components.settings.SettingsGroupScope
import com.neurotube.app.ui.components.settings.SettingsPage
import com.neurotube.app.ui.components.settings.notice
import com.neurotube.app.ui.components.settings.switch
import com.neurotube.app.ui.components.shared.FlowActionButton
import com.neurotube.app.ui.components.shared.FlowAlertDialog
import com.neurotube.app.ui.components.shared.FlowMorphingPortrait
import com.neurotube.app.ui.components.shared.FlowNavRow
import com.neurotube.app.ui.components.shared.FlowProgressBanner
import com.neurotube.app.ui.screens.settings.index.HomeIndex

private val PortraitSize = 72.dp
private val HeaderSpacing = 12.dp

/**
 * The YouTube account page: sign in, see who is signed in, sign out.
 *
 * Sign-in opens a WebView, so it needs the hosting activity; on a large window this page still runs
 * in the same activity, so that is all it takes.
 */
@Composable
internal fun YouTubeAccountScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: YouTubeAccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val account by viewModel.account.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val switchingTo by viewModel.switchingTo.collectAsStateWithLifecycle()
    val signInState by viewModel.signInState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val autoImportEnabled by viewModel.autoImportEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }

    val failedMessage = stringResource(R.string.youtube_account_sign_in_failed)
    val importFailedMessage = stringResource(R.string.youtube_account_import_failed)
    LaunchedEffect(signInState) {
        if (signInState == YouTubeSignInState.Failed) {
            viewModel.consumeFailure()
            snackbarHostState.showSnackbar(failedMessage)
        }
    }
    LaunchedEffect(importState) {
        if (importState is AccountImportState.Failed) {
            viewModel.consumeImportResult()
            snackbarHostState.showSnackbar(importFailedMessage)
        }
    }

    val done = importState as? AccountImportState.Done
    val failures = done?.report?.failures.orEmpty()
    val importFailureText =
        if (failures.isEmpty()) {
            null
        } else {
            failures.joinToString("\n") { "${it.step}: ${it.error}" }
        }

    SettingsPage(
        title = stringResource(R.string.youtube_account_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        item("account.status") {
            AccountStatus(account = account, working = signInState == YouTubeSignInState.Working)
        }
        (importState as? AccountImportState.Running)?.let { running ->
            item("account.import.progress") {
                FlowProgressBanner(
                    headline = stringResource(running.step.titleRes()),
                    current = running.current,
                    total = running.total,
                    note = stringResource(R.string.youtube_account_import_running_note),
                )
            }
        }
        group(key = "account.accounts", header = R.string.youtube_account_section_accounts) {
            accountRows(
                accounts = accounts,
                activeId = account?.identity(),
                switchingTo = switchingTo,
                onSwitch = viewModel::switchTo,
                onRemove = { confirmRemove = it.identity() },
            )
            if (account == null) {
                signInRow(
                    enabled = signInState != YouTubeSignInState.Working,
                    onClick = { viewModel.signIn(activity) },
                )
            } else if (accounts.size < viewModel.maxAccounts) {
                addAccountRow(
                    enabled = signInState != YouTubeSignInState.Working && switchingTo == null,
                    onClick = { viewModel.signIn(activity) },
                )
            }
        }
        group(key = "account.actions") {
            if (account != null) {
                importRow(
                    state = importState,
                    onClick = { viewModel.importLibrary() },
                )
                autoImportRow(
                    checked = autoImportEnabled,
                    onCheckedChange = viewModel::setAutoImportEnabled,
                )
                signOutRow(onClick = { confirmSignOut = true })
            }
        }
        if (switchingTo != null) {
            notice(
                key = "account.switching",
                text = { stringResource(R.string.youtube_account_switching) },
            )
        }
        if (accounts.size >= viewModel.maxAccounts) {
            notice(
                key = "account.limit",
                text = { stringResource(R.string.youtube_account_limit, viewModel.maxAccounts) },
                icon = Icons.Outlined.Warning,
                isWarning = true,
            )
        }
        (importState as? AccountImportState.Done)?.let { done ->
            notice(
                key = "account.import.result",
                text = { importSummary(done.report) },
                title = { stringResource(R.string.youtube_account_import_done_title) },
            )
        }
        importFailureText?.let { text ->
            notice(
                key = "account.import.failure",
                text = { text },
                title = { stringResource(R.string.youtube_account_import_partial_title) },
                icon = Icons.Outlined.Warning,
                isWarning = true,
            )
        }
        notice(
            key = "account.risk",
            text = { stringResource(R.string.youtube_account_risk_note) },
            icon = Icons.Outlined.Warning,
            isWarning = true,
        )
    }

    confirmRemove?.let { id ->
        val target = accounts.firstOrNull { it.identity() == id }
        if (target != null) {
            FlowAlertDialog(
                onDismissRequest = { confirmRemove = null },
                icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                title = { Text(stringResource(R.string.youtube_account_remove_confirm, target.name)) },
                text = { Text(stringResource(R.string.youtube_account_remove_confirm_body)) },
                confirmButton = {
                    FlowActionButton(
                        text = stringResource(R.string.youtube_account_remove),
                        onClick = {
                            confirmRemove = null
                            viewModel.remove(target)
                        },
                    )
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemove = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        } else {
            confirmRemove = null
        }
    }

    if (confirmSignOut) {
        FlowAlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.youtube_account_sign_out_confirm)) },
            text = { Text(stringResource(R.string.youtube_account_sign_out_confirm_body)) },
            confirmButton = {
                FlowActionButton(
                    text = stringResource(R.string.youtube_account_sign_out),
                    onClick = {
                        confirmSignOut = false
                        viewModel.signOut()
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AccountStatus(
    account: YouTubeAccount?,
    working: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HeaderSpacing),
    ) {
        if (account != null) {
            FlowMorphingPortrait(
                imageUrl = account.thumbnailUrl.orEmpty(),
                fallback = account.name,
                diameter = PortraitSize,
                accent = MaterialTheme.colorScheme.secondaryContainer,
                onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.youtube_account_signed_in_as, account.name),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            account.email?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Spacer(Modifier.size(PortraitSize))
            Text(
                text =
                    stringResource(
                        if (working) R.string.youtube_login_working else R.string.youtube_account_signed_out,
                    ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun SettingsGroupScope.signInRow(
    enabled: Boolean,
    onClick: () -> Unit,
) = row(HomeIndex.account.key) { shape ->
    FlowNavRow(
        title = stringResource(R.string.youtube_account_sign_in),
        supportingText = stringResource(R.string.youtube_account_subtitle),
        onClick = onClick,
        enabled = enabled,
        showChevron = false,
        shape = shape,
    )
}

private fun SettingsGroupScope.signOutRow(onClick: () -> Unit) =
    row("account.sign_out") { shape ->
        FlowNavRow(
            title = stringResource(R.string.youtube_account_sign_out),
            onClick = onClick,
            showChevron = false,
            destructive = true,
            shape = shape,
        )
    }

/**
 * Brings the account's library in. The row says which part is running, and stays disabled until it
 * finishes so a second tap cannot start another pass over the same data.
 */
private fun SettingsGroupScope.importRow(
    state: AccountImportState,
    onClick: () -> Unit,
) = row("account.import") { shape ->
    val running = state as? AccountImportState.Running
    FlowNavRow(
        title = stringResource(R.string.youtube_account_import_title),
        supportingText =
            running
                ?.let { stringResource(it.step.titleRes()) }
                ?: stringResource(R.string.youtube_account_import_subtitle),
        onClick = onClick,
        enabled = running == null,
        showChevron = false,
        shape = shape,
    )
}

/** The switch that decides whether signing in brings the library in on its own. */
private fun SettingsGroupScope.autoImportRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) = switch(
    entry = HomeIndex.accountAutoImport,
    checked = checked,
    onCheckedChange = onCheckedChange,
)

@Composable
private fun importSummary(report: AccountImportReport): String =
    stringResource(
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
