package com.neurotube.app.ui.screens.settings.index

import com.neurotube.app.R
import com.neurotube.app.ui.components.settings.SettingAvailability
import com.neurotube.app.ui.components.settings.SettingEntry
import com.neurotube.app.ui.components.settings.SettingsDestination

/** Options that live directly on the settings list rather than on a page of their own. */
internal object HomeIndex {
    private val home = SettingsDestination.HOME

    val persona =
        SettingEntry(
            key = "home.persona",
            title = R.string.taste_title,
            summary = R.string.taste_summary,
            section = R.string.settings_flow_engine_header,
            destination = home,
        )
    val deepFocus =
        SettingEntry(
            key = "home.deep_focus",
            title = R.string.deep_focus_mode_title,
            summary = R.string.deep_focus_mode_subtitle,
            section = R.string.settings_flow_engine_header,
            destination = home,
        )
    val deepFocusDuration =
        SettingEntry(
            key = "home.deep_focus_duration",
            title = R.string.deep_focus_expire_duration_title,
            section = R.string.settings_flow_engine_header,
            destination = home,
        )
    val deepFocusHistory =
        SettingEntry(
            key = "home.deep_focus_history",
            title = R.string.deep_focus_save_history_title,
            summary = R.string.deep_focus_save_history_subtitle,
            section = R.string.settings_flow_engine_header,
            destination = home,
        )
    val deepFocusScrobble =
        SettingEntry(
            key = "home.deep_focus_scrobble",
            title = R.string.deep_focus_scrobble_title,
            summary = R.string.deep_focus_scrobble_subtitle,
            section = R.string.settings_flow_engine_header,
            destination = home,
        )
    val checkForUpdates =
        SettingEntry(
            key = "home.updates",
            title = R.string.check_for_updates,
            summary = R.string.check_for_updates_subtitle,
            section = R.string.settings_header_about,
            destination = home,
            availability = SettingAvailability.GithubOnly,
        )

    /**
     * The account row lives directly on the settings list rather than on a page, so its state
     * (signed in, signed out, working) is rendered by the list itself.
     */
    val account =
        SettingEntry(
            key = "home.account",
            title = R.string.youtube_account_title,
            summary = R.string.youtube_account_subtitle,
            keywords = R.string.youtube_account_keywords,
            destination = home,
        )

    /**
     * The switch on the account page that decides whether signing in brings the library in on its
     * own. Declared here so settings search finds it by name.
     */
    val accountAutoImport =
        SettingEntry(
            key = "account.auto_import",
            title = R.string.youtube_account_import_auto_title,
            summary = R.string.youtube_account_import_auto_summary,
            keywords = R.string.youtube_account_import_auto_keywords,
            destination = SettingsDestination.ACCOUNT,
        )

    /** The persona card is found through Your taste's own destination entry, not listed twice. */
    val all =
        listOf(account, accountAutoImport, deepFocus, deepFocusDuration, deepFocusHistory, deepFocusScrobble, checkForUpdates)
}
