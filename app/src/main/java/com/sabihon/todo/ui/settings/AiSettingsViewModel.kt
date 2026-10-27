package com.sabihon.todo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.datastore.AiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSettingsUiState(
    /** Null until the stored value has been read, so the switch never flickers. */
    val aiEnabled: Boolean? = null
)

/**
 * The state behind the one switch this screen owns.
 *
 * Kept apart from [com.sabihon.todo.ui.profile.ProfileViewModel], which Settings already
 * borrows for the name, email and theme: those come from Firebase and the auth session,
 * while this is a preference in the local DataStore. Different sources, different
 * lifetimes, no reason to share a ViewModel.
 */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val aiPreferences: AiPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            aiPreferences.aiEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(aiEnabled = enabled) }
            }
        }
    }

    fun setAiEnabled(enabled: Boolean) {
        // Written optimistically: the DataStore round-trip is fast, and a switch that
        // waits for disk before moving feels broken.
        _uiState.update { it.copy(aiEnabled = enabled) }
        viewModelScope.launch {
            aiPreferences.setAiEnabled(enabled)
        }
    }
}
