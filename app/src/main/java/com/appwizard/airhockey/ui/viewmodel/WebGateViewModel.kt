package com.appwizard.airhockey.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import com.appwizard.airhockey.data.web.WebConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WebGateViewModel(
    private val webConfigRepository: WebConfigRepository,
    context: Context
) : ViewModel() {
    private val appContext = context.applicationContext

    sealed interface AppState {
        data object Loading : AppState
        data class WebView(val url: String) : AppState
        data object NormalApp : AppState
    }

    private val _appState = MutableStateFlow<AppState>(AppState.Loading)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    init {
        checkWebViewUrl()
    }

    private fun checkWebViewUrl() {
        viewModelScope.launch {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val webViewPrefs = appContext.getSharedPreferences(WEBVIEW_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
            val cachedUrl = prefs.getString(CACHED_URL, null)
            val cachedFinalUrl = webViewPrefs.getString(CACHED_FINAL_URL, null)
            val fallbackUrl = cachedFinalUrl.takeIf { !it.isNullOrBlank() }
                ?: cachedUrl.takeIf { !it.isNullOrBlank() }

            runCatching {
                withTimeoutOrNull(10_000L) {
                    webConfigRepository.getWebViewUrl()
                }
            }.fold(
                onSuccess = { url ->
                    when {
                        !url.isNullOrBlank() -> {
                            prefs.edit { putString(CACHED_URL, url) }
                            _appState.value = AppState.WebView(cachedFinalUrl.takeIf { !it.isNullOrBlank() } ?: url)
                        }
                        else -> {
                            _appState.value = if (!fallbackUrl.isNullOrBlank()) {
                                AppState.WebView(fallbackUrl)
                            } else {
                                AppState.NormalApp
                            }
                        }
                    }
                },
                onFailure = {
                    _appState.value = if (!fallbackUrl.isNullOrBlank()) {
                        AppState.WebView(fallbackUrl)
                    } else {
                        AppState.NormalApp
                    }
                }
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "webview_prefs"
        const val WEBVIEW_CACHE_PREFS_NAME = "webview_cache"
        const val CACHED_URL = "cached_url"
        const val CACHED_FINAL_URL = "cached_final_url"
    }
}
