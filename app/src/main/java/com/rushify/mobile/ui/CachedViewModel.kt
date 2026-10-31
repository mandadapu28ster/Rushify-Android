package com.rushify.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.Cached
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Loadable<T>(val data: Cached<T>? = null, val loading: Boolean = false, val error: String? = null)

/**
 * Cache-first loading: show the saved copy immediately, refresh in the background, and on failure
 * keep the saved copy on screen with an error so the UI can show the offline banner.
 */
abstract class CachedViewModel<T> : ViewModel() {
    val state = MutableStateFlow(Loadable<T>())
    private var started = false

    protected abstract suspend fun readCache(): Cached<T>?
    protected abstract suspend fun fetch(): Cached<T>

    private var generation = 0

    fun load() {
        if (started) return
        started = true
        launchLoad()
    }

    /** Parameter changed (range, granularity, scope): drop stale data and load again; late replies for the old parameter are ignored. */
    fun reload() {
        started = true
        generation++
        state.value = Loadable()
        launchLoad()
    }

    private fun launchLoad() {
        val g = generation
        viewModelScope.launch {
            readCache()?.let { c -> if (g == generation) state.update { it.copy(data = c) } }
            doRefresh()
        }
    }

    fun refresh() { viewModelScope.launch { doRefresh() } }

    protected suspend fun doRefresh() {
        val g = generation
        state.update { it.copy(loading = true) }
        try {
            val c = fetch()
            if (g == generation) state.update { Loadable(data = c) }
        } catch (e: ApiError) {
            if (g == generation) state.update { it.copy(loading = false, error = e.message) }
        }
    }
}
