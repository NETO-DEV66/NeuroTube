package com.neurotube.app.ui.screens.onboarding

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neurotube.app.account.AccountImportState
import com.neurotube.app.account.AccountLibraryImportCoordinator
import com.neurotube.app.account.YouTubeAccount
import com.neurotube.app.account.YouTubeAccountManager
import com.neurotube.app.data.backup.BackupCoordinator
import com.neurotube.app.data.backup.BackupOperation
import com.neurotube.app.data.backup.ImportKind
import com.neurotube.app.data.backup.ImportSource
import com.neurotube.app.data.local.ChannelSubscription
import com.neurotube.app.data.local.PlayerPreferences
import com.neurotube.app.data.local.SubscriptionRepository
import com.neurotube.app.data.model.Channel
import com.neurotube.app.data.paging.ChannelSearch
import com.neurotube.app.data.recommendation.OnboardingCompleter
import com.neurotube.app.data.recommendation.music.FavouriteArtistCatalog
import com.neurotube.app.data.recommendation.music.FavouriteArtistPicker
import com.neurotube.app.data.recommendation.music.FavouriteArtistsStore
import com.neurotube.app.ui.screens.settings.account.YouTubeSignInState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MS = 400L
private const val KEY_STEP = "step"
private const val KEY_TOPICS = "topics"
private const val KEY_QUERY = "query"
private const val KEY_SUB_IDS = "subscribedIds"
private const val KEY_SUB_NAMES = "subscribedNames"
private const val KEY_SUB_THUMBS = "subscribedThumbs"
private const val KEY_IMPORTED = "importedSources"
private const val KEY_NOTIFYING = "notifying"

/**
 * First-run setup. Everything a person picks is kept in [SavedStateHandle], so a recreated
 * activity or a killed process resumes on the same step with the same choices.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val subscriptions: SubscriptionRepository,
        private val backup: BackupCoordinator,
        private val channelSearch: ChannelSearch,
        private val completer: OnboardingCompleter,
        private val preferences: PlayerPreferences,
        private val accountManager: YouTubeAccountManager,
        private val accountImport: AccountLibraryImportCoordinator,
        favouriteArtists: FavouriteArtistsStore,
        artistCatalog: FavouriteArtistCatalog,
    ) : ViewModel() {
        private val _state = MutableStateFlow(restore())
        val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

        val importOperation: StateFlow<BackupOperation> = backup.operation

        /** The account library import the process shares, so this step shows it live. */
        val accountImportState: StateFlow<AccountImportState> = accountImport.state

        val account: StateFlow<YouTubeAccount?> = accountManager.account

        private val _signInState = MutableStateFlow<YouTubeSignInState>(YouTubeSignInState.Idle)
        val signInState: StateFlow<YouTubeSignInState> = _signInState.asStateFlow()

        /** The account page's failure feedback reuses this state; onboarding only shows a snackbar. */
        fun signIn(activity: android.app.Activity?) {
            if (_signInState.value == YouTubeSignInState.Working) return
            _signInState.value = YouTubeSignInState.Working
            viewModelScope.launch {
                _signInState.value =
                    accountManager.signIn(activity).fold(
                        onSuccess = { YouTubeSignInState.Idle },
                        onFailure = { YouTubeSignInState.Failed },
                    )
            }
        }

        fun consumeSignInFailure() {
            _signInState.value = YouTubeSignInState.Idle
        }

        fun signOut() {
            viewModelScope.launch { accountManager.signOut() }
        }

        /** Picks are saved as they are made, the same as in Settings, so nothing waits for Done. */
        val artists = FavouriteArtistPicker(viewModelScope, favouriteArtists, artistCatalog)

        val newVideoAlerts: StateFlow<Boolean> =
            preferences.notifNewVideosEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        private var searchJob: Job? = null
        private var completion: Job? = null
        private var pendingSource: ImportSource? = null

        init {
            if (_state.value.query.isNotBlank()) search(_state.value.query)
            viewModelScope.launch {
                backup.operation.collect { operation ->
                    val source = pendingSource
                    if (operation is BackupOperation.Succeeded && source != null) {
                        pendingSource = null
                        edit { it.copy(importedSources = it.importedSources + source) }
                    } else if (operation is BackupOperation.Failed) {
                        pendingSource = null
                    }
                }
            }
        }

        fun next() {
            val next = OnboardingStep.entries.getOrNull(_state.value.step.index + 1)
            if (next == null) complete() else goTo(next)
        }

        /** Steps back; false on the first step, so the system can handle back itself. */
        fun back(): Boolean {
            val previous = OnboardingStep.entries.getOrNull(_state.value.step.index - 1) ?: return false
            goTo(previous)
            return true
        }

        fun goTo(step: OnboardingStep) = edit { it.copy(step = step) }

        fun toggleTopic(topic: String) =
            edit { state ->
                state.copy(topics = if (topic in state.topics) state.topics - topic else state.topics + topic)
            }

        fun search(query: String) {
            edit { it.copy(query = query) }
            searchJob?.cancel()
            if (query.isBlank()) {
                _state.update { it.copy(results = emptyList(), searching = false) }
                return
            }
            searchJob =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    _state.update { it.copy(searching = true) }
                    val results = channelSearch.search(query)
                    _state.update { it.copy(results = results, searching = false) }
                }
        }

        fun toggleSubscription(channel: Channel) {
            val subscribed = _state.value.isSubscribed(channel.id)
            edit { state ->
                state.copy(
                    subscribed =
                        if (subscribed) {
                            state.subscribed.filterNot { it.id == channel.id }
                        } else {
                            state.subscribed + channel
                        },
                    notifying = state.notifying - channel.id,
                )
            }
            viewModelScope.launch {
                if (subscribed) {
                    subscriptions.unsubscribe(channel.id)
                } else {
                    subscriptions.subscribe(
                        ChannelSubscription(
                            channelId = channel.id,
                            channelName = channel.name,
                            channelThumbnail = channel.thumbnailUrl,
                        ),
                    )
                }
            }
        }

        fun setChannelNotifications(
            channelId: String,
            enabled: Boolean,
        ) {
            edit { state -> state.copy(notifying = if (enabled) state.notifying + channelId else state.notifying - channelId) }
            viewModelScope.launch { subscriptions.updateNotificationState(channelId, enabled) }
        }

        fun setNewVideoAlerts(enabled: Boolean) {
            viewModelScope.launch { preferences.setNotifNewVideosEnabled(enabled) }
        }

        fun startImport(
            kind: ImportKind,
            uris: List<Uri>,
        ) {
            pendingSource = ImportSource.entries.firstOrNull { kind in it.kinds }
            if (!kind.start(backup, uris)) pendingSource = null
        }

        fun dismissImport() = backup.dismiss()

        /** Seeds the engine once; a second tap while it runs does nothing. */
        fun complete() {
            if (completion != null) return
            completion =
                viewModelScope.launch {
                    completer.complete(_state.value.topics)
                    _state.update { it.copy(completed = true) }
                }
        }

        private fun edit(transform: (OnboardingUiState) -> OnboardingUiState) {
            val state = _state.updateAndGet(transform)
            savedState[KEY_STEP] = state.step.name
            savedState[KEY_TOPICS] = ArrayList(state.topics)
            savedState[KEY_QUERY] = state.query
            savedState[KEY_SUB_IDS] = ArrayList(state.subscribed.map { it.id })
            savedState[KEY_SUB_NAMES] = ArrayList(state.subscribed.map { it.name })
            savedState[KEY_SUB_THUMBS] = ArrayList(state.subscribed.map { it.thumbnailUrl })
            savedState[KEY_IMPORTED] = ArrayList(state.importedSources.map { it.name })
            savedState[KEY_NOTIFYING] = ArrayList(state.notifying)
        }

        private fun restore(): OnboardingUiState {
            val ids = savedState.get<ArrayList<String>>(KEY_SUB_IDS).orEmpty()
            val names = savedState.get<ArrayList<String>>(KEY_SUB_NAMES).orEmpty()
            val thumbs = savedState.get<ArrayList<String>>(KEY_SUB_THUMBS).orEmpty()
            return OnboardingUiState(
                step =
                    savedState
                        .get<String>(KEY_STEP)
                        ?.let { name -> OnboardingStep.entries.firstOrNull { it.name == name } }
                        ?: OnboardingStep.entries.first(),
                topics = savedState.get<ArrayList<String>>(KEY_TOPICS).orEmpty().toSet(),
                query = savedState.get<String>(KEY_QUERY).orEmpty(),
                subscribed =
                    ids.mapIndexed { index, id ->
                        Channel(
                            id = id,
                            name = names.getOrElse(index) { "" },
                            thumbnailUrl = thumbs.getOrElse(index) { "" },
                            subscriberCount = -1L,
                        )
                    },
                notifying = savedState.get<ArrayList<String>>(KEY_NOTIFYING).orEmpty().toSet(),
                importedSources =
                    savedState
                        .get<ArrayList<String>>(KEY_IMPORTED)
                        .orEmpty()
                        .mapNotNull { name -> ImportSource.entries.firstOrNull { it.name == name } }
                        .toSet(),
            )
        }
    }
