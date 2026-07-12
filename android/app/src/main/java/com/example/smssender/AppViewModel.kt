package com.example.smssender

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smssender.data.AppDatabase
import com.example.smssender.data.ForwardStatus
import com.example.smssender.data.PendingMessage
import com.example.smssender.data.StatusStore
import com.example.smssender.model.AppConfig
import com.example.smssender.security.SecureConfigStore
import com.example.smssender.validation.ConfigValidator
import com.example.smssender.worker.ForwardWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val secureStore = SecureConfigStore(application)

    val pending: StateFlow<List<PendingMessage>> =
        AppDatabase.get(application).pendingMessageDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val status: StateFlow<ForwardStatus> = StatusStore(application).observe()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            StatusStore(application).current(),
        )

    fun loadConfig(): AppConfig = secureStore.load()

    fun isConfigured(): Boolean =
        secureStore.isConfigured() && ConfigValidator.validate(secureStore.load()).isEmpty()

    fun save(config: AppConfig): Map<String, String> {
        val errors = ConfigValidator.validate(config)
        if (errors.isEmpty()) secureStore.save(config)
        return errors
    }

    fun test(config: AppConfig): Map<String, String> {
        val errors = save(config)
        if (errors.isEmpty()) ForwardWorker.test(getApplication())
        return errors
    }

    fun retry() = ForwardWorker.enqueue(getApplication())
}
