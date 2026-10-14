package io.hongshu.app

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// SnackbarHostState.showSnackbar suspends until a SnackbarHost displays and dismisses the
// message. The settings destination does not compose the main host, so awaiting it there never
// returns. The busy flag must therefore be released before, and independently of, any message.
internal fun CoroutineScope.launchUiAction(
    snackbar: SnackbarHostState,
    announce: Boolean,
    setBusy: (Boolean) -> Unit,
    onFinished: () -> Unit,
    task: suspend () -> String,
): Job = launch {
    var message: String? = null
    try {
        message = withContext(Dispatchers.IO) { task() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        message = e.message ?: "操作失败"
    } finally {
        setBusy(false)
        onFinished()
    }
    // Callers whose screen shows its own feedback pass announce=false so nothing queues
    // invisibly on a host that is not on screen.
    val text = message
    if (announce && text != null) {
        snackbar.currentSnackbarData?.dismiss()
        launch { snackbar.showSnackbar(text) }
    }
}
