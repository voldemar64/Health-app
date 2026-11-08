package io.github.voldemar64.healthapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.voldemar64.healthapp.domain.HealthData
import io.github.voldemar64.healthapp.domain.HealthRepository
import io.github.voldemar64.healthapp.domain.InvalidInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Instant

data class HealthState(val data: HealthData = HealthData(), val loaded: Boolean = false, val now: Instant = Instant.now(),
    val saving: Boolean = false, val error: String? = null)

class HealthViewModel(private val repository: HealthRepository) : ViewModel() {
    private val mutable = MutableStateFlow(HealthState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                mutable.value = mutable.value.copy(now = Instant.now())
            }
        }
        viewModelScope.launch {
            repository.data.catch { failure ->
                if (failure is CancellationException) throw failure
                mutable.value = mutable.value.copy(error = "STORAGE")
            }.collect { mutable.value = mutable.value.copy(data = it, loaded = true) }
        }
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
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
        fun factory(repository: HealthRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HealthViewModel(repository) as T
        }
    }
}
