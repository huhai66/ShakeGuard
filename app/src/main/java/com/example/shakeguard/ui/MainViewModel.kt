package com.example.shakeguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.shakeguard.data.AppInfo
import com.example.shakeguard.data.AppRepository
import com.example.shakeguard.data.SkipSettings
import com.example.shakeguard.util.KeepAliveManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application)
    private val settings = SkipSettings.get(application)

    val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val search = MutableStateFlow("")

    val masterEnabled: StateFlow<Boolean> = settings.masterEnabled
    val enabledPackages: StateFlow<Set<String>> = settings.enabledPackages
    val skipCount: StateFlow<Int> = settings.skipCount
    val debugLines: StateFlow<List<String>> = settings.debugLines
    val imageRecognitionEnabled: StateFlow<Boolean> = settings.imageRecognitionEnabled
    val lastEventTime: StateFlow<Long> = settings.lastEventTime

    init {
        KeepAliveManager.update(getApplication())
        viewModelScope.launch {
            apps.value = repository.loadLaunchableApps()
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        settings.setMasterEnabled(enabled)
        KeepAliveManager.update(getApplication())
    }

    fun setImageRecognitionEnabled(enabled: Boolean) {
        settings.setImageRecognitionEnabled(enabled)
        KeepAliveManager.update(getApplication())
    }

    fun setEnabled(packageName: String, enabled: Boolean) =
        settings.setEnabled(packageName, enabled)

    fun setEnabledPackages(packages: Set<String>) =
        settings.setEnabledPackages(packages)

    fun clearDebugLines() = settings.clearDebugLines()
}
