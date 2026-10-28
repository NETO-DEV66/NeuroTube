package com.neurotube.app.ui.screens.settings.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neurotube.app.account.AccountImportState
import com.neurotube.app.account.AccountLibraryImportCoordinator
import com.neurotube.app.account.YouTubeAccount
import com.neurotube.app.account.YouTubeAccountManager
import com.neurotube.app.data.local.PlayerPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the account page is doing right now. */
sealed interface YouTubeSignInState {
    data object Idle : YouTubeSignInState

    data object Working : YouTubeSignInState

    data object Failed : YouTubeSignInState
}

@HiltViewModel
class YouTubeAccountViewModel
    @Inject
    constructor(
        private val manager: YouTubeAccountManager,
        private val importCoordinator: AccountLibraryImportCoordinator,
        private val preferences: PlayerPreferences,
    ) : ViewModel() {
        val account: StateFlow<YouTubeAccount?> = manager.account

        /** Every linked account, oldest first. */
        val accounts: StateFlow<List<YouTubeAccount>> = manager.accounts

        /** How many accounts can be linked at once. */
        val maxAccounts: Int get() = manager.maxAccounts

        /** True once no further account can be linked. */
        val atCapacity: Boolean get() = manager.atCapacity

        private val _signInState = MutableStateFlow<YouTubeSignInState>(YouTubeSignInState.Idle)
        val signInState: StateFlow<YouTubeSignInState> = _signInState.asStateFlow()

        /** The one import the whole process shares, so the page shows it whether it started it or not. */
        val importState: StateFlow<AccountImportState> = importCoordinator.state

        val autoImportEnabled: StateFlow<Boolean> =
            preferences.accountAutoImportEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        fun signIn(activity: android.app.Activity?) {
            if (_signInState.value == YouTubeSignInState.Working) return
            _signInState.value = YouTubeSignInState.Working
            viewModelScope.launch {
                _signInState.value =
                    manager.signIn(activity).fold(
                        onSuccess = { YouTubeSignInState.Idle },
                        onFailure = { YouTubeSignInState.Failed },
                    )
            }
        }

        fun consumeFailure() {
            _signInState.value = YouTubeSignInState.Idle
        }

        fun signOut() {
            viewModelScope.launch { manager.signOut() }
        }

        /** Makes [account] the active one; the library the app reads changes with it. */
        fun switchTo(account: YouTubeAccount) {
            val id = manager.identityOf(account)
            if (id.isEmpty() || _switchingTo.value != null) return
            _switchingTo.value = id
            viewModelScope.launch {
                manager.switchTo(id)
                _switchingTo.value = null
            }
        }

        /** Unlinks [account]. Its library stays on disk, so linking it again brings it back. */
        fun remove(account: YouTubeAccount) {
            viewModelScope.launch { manager.remove(manager.identityOf(account)) }
        }

        /** The account being switched to, so its card can show progress and block a second tap. */
        private val _switchingTo = MutableStateFlow<String?>(null)
        val switchingTo: StateFlow<String?> = _switchingTo.asStateFlow()

        /** Brings the signed-in account's library in, one part at a time. */
        fun importLibrary() {
            importCoordinator.importNow()
        }

        fun setAutoImportEnabled(enabled: Boolean) {
            viewModelScope.launch { preferences.setAccountAutoImportEnabled(enabled) }
        }

        /** Clears the last import's result once it has been shown. */
        fun consumeImportResult() {
            importCoordinator.consumeResult()
        }
    }
