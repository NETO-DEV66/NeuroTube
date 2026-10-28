package com.neurotube.app.ui.screens.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neurotube.app.R
import com.neurotube.app.account.YouTubeAccount
import com.neurotube.app.account.identity
import com.neurotube.app.ui.components.settings.SettingsGroupScope
import com.neurotube.app.ui.components.shared.FlowMorphingPortrait
import com.neurotube.app.ui.components.shared.FlowNavRow
import com.neurotube.app.ui.components.shared.FlowPopIn

private val RowPortraitSize = 40.dp

/**
 * The linked accounts, one row each, drawn as a settings group.
 *
 * Each row is one account and one action: tapping it makes that account active, which is what
 * changes the library the app reads. The active one is marked rather than hidden, so it stays
 * obvious which library the rest of the app is showing.
 *
 * Rows land one after another on [FlowPopIn] and the portrait springs in on its own, so opening the
 * page reads as the accounts arriving rather than appearing.
 */
internal fun SettingsGroupScope.accountRows(
    accounts: List<YouTubeAccount>,
    activeId: String?,
    switchingTo: String?,
    onSwitch: (YouTubeAccount) -> Unit,
    onRemove: (YouTubeAccount) -> Unit,
) = accounts.forEachIndexed { index, account ->
    val id = account.identityForUi()
    val isActive = id == activeId
    row("account.$id") { shape ->
        FlowPopIn(index = index) {
            FlowNavRow(
                title = account.name,
                supportingText = account.email,
                onClick = { if (!isActive) onSwitch(account) },
                showChevron = false,
                enabled = switchingTo == null || switchingTo == id,
                shape = shape,
                selected = isActive,
                leadingContent = {
                    FlowMorphingPortrait(
                        imageUrl = account.thumbnailUrl.orEmpty(),
                        fallback = account.name,
                        diameter = RowPortraitSize,
                        accent = MaterialTheme.colorScheme.secondaryContainer,
                        onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
                        delayIndex = index,
                    )
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isActive) ActiveLabel()
                        IconButton(onClick = { onRemove(account) }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.youtube_account_remove),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** The row that starts a sign-in for one more account; absent once the cap is reached. */
internal fun SettingsGroupScope.addAccountRow(
    enabled: Boolean,
    onClick: () -> Unit,
) = row("account.add") { shape ->
    FlowNavRow(
        title = stringResource(R.string.youtube_account_add),
        supportingText = stringResource(R.string.youtube_account_add_subtitle),
        onClick = onClick,
        enabled = enabled,
        showChevron = false,
        shape = shape,
        leadingIcon = Icons.Outlined.Add,
    )
}

@Composable
private fun ActiveLabel() {
    Text(
        text = stringResource(R.string.youtube_account_active),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = 4.dp),
    )
}

/** Stable key for a row: the account identity, which is what the list is keyed by everywhere else. */
private fun YouTubeAccount.identityForUi(): String = identity()
