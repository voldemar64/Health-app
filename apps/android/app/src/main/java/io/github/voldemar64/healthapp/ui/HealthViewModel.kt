package io.github.voldemar64.healthapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.voldemar64.healthapp.domain.HealthData
import io.github.voldemar64.healthapp.domain.HealthRepository
import io.github.voldemar64.healthapp.domain.UserProfile
import io.github.voldemar64.healthapp.domain.LEGACY_PROFILE_ID
import io.github.voldemar64.healthapp.domain.InvalidInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.Clock
import java.time.LocalDate

data class HealthState(val data: HealthData = HealthData(), val loaded: Boolean = false,
    val profiles: List<UserProfile> = emptyList(), val activeProfileId: String = LEGACY_PROFILE_ID,
    val now: Instant = Instant.EPOCH,
    val saving: Boolean = false, val error: String? = null)

class HealthViewModel(private val repository: HealthRepository,
    private val cancelProfileReminders: suspend (String) -> Unit = {},
    private val clock: Clock = Clock.systemUTC()) : ViewModel() {
    private val mutable = MutableStateFlow(HealthState(now = clock.instant()))
    val state = mutable.asStateFlow()
    fun currentTime(): Instant = clock.instant()
    fun observePeriod(profileId: String, start: LocalDate, end: LocalDate) =
        repository.observeProfile(profileId, start, end)
    init {
        viewModelScope.launch { repository.profiles.collect { mutable.value = mutable.value.copy(profiles = it) } }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                mutable.value = mutable.value.copy(now = clock.instant())
            }
        }
        viewModelScope.launch {
            repository.activeProfileId.collectLatest { id ->
                repository.observeProfile(id).catch { failure ->
                    if (failure is CancellationException) throw failure
                    mutable.value = mutable.value.copy(error = "STORAGE")
                }.collect { data -> mutable.value = mutable.value.copy(data = data, loaded = true,
                    activeProfileId = id) }
            }
        }
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun selectProfile(id: String) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(data = HealthData(), loaded = false)
        viewModelScope.launch {
            try {
                repository.selectProfile(id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutable.value = mutable.value.copy(error = (failure as? InvalidInput)?.code?.name ?: "STORAGE")
            }
        }
    }
    fun createProfile(name: String) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(data = HealthData(), loaded = false)
        save { this.createProfile(name) }
    }
    fun renameProfile(id: String, name: String) = save { this.renameProfile(id, name) }
    fun deleteProfile(id: String) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(data = HealthData(), loaded = false)
        save { cancelProfileReminders(id); this.deleteProfile(id) }
    }
    fun save(onSuccess: () -> Unit = {}, action: suspend HealthRepository.() -> Unit) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                repository.action()
                onSuccess()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutable.value = mutable.value.copy(error = (failure as? InvalidInput)?.code?.name ?: "STORAGE")
            } finally {
                mutable.value = mutable.value.copy(saving = false)
            }
        }
    }
    companion object {
        fun factory(repository: HealthRepository,
            cancelProfileReminders: suspend (String) -> Unit = {}): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HealthViewModel(repository, cancelProfileReminders) as T
        }
    }
}
