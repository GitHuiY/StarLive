package io.github.githuiy.starlive.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import java.util.UUID

internal class WithinEditorSessionStore : ViewModel() {
    private var activeId: String? = null
    private var activeHistory: MutableState<WithinEditHistory>? = null

    fun open(id: String, initial: WithinEditSnapshot): MutableState<WithinEditHistory> {
        val existing = activeHistory
        if (activeId == id && existing != null) return existing
        return mutableStateOf(WithinEditHistory(initial)).also {
            activeId = id
            activeHistory = it
        }
    }

    fun close(id: String) {
        if (activeId == id) {
            activeId = null
            activeHistory = null
        }
    }
}

internal class WithinEditorSession(val history: MutableState<WithinEditHistory>, val release: () -> Unit)

@Composable
internal fun rememberWithinEditorSession(recordId: String?, restored: WithinEditSnapshot): WithinEditorSession {
    val id = rememberSaveable(recordId) { UUID.randomUUID().toString() }
    val owner = LocalActivity.current as? ViewModelStoreOwner
    val store = remember(owner) { owner?.let { ViewModelProvider(it)[WithinEditorSessionStore::class.java] } }
    return remember(id, store) {
        WithinEditorSession(store?.open(id, restored) ?: mutableStateOf(WithinEditHistory(restored))) { store?.close(id) }
    }
}
