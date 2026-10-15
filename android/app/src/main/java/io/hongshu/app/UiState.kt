package io.hongshu.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Recomposition key that changes whenever [Config] is written, from any thread or process
 * component. Config reads SharedPreferences directly, so without this key a switch only
 * reflected its new value on the next unrelated recomposition.
 */
@Composable
internal fun rememberPreferenceVersion(context: Context): Long {
    var version by remember { mutableLongStateOf(0L) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
        // SharedPreferences keeps listeners weakly; the onDispose lambda holds this one.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return version
}

/**
 * Recomposition key for system state the app cannot observe (runtime permissions, notification
 * access, battery exemptions). Those only change while the user is in system settings, so
 * re-reading them on resume replaces the previous once-per-second polling.
 */
@Composable
internal fun rememberResumeCount(): Int {
    val owner = LocalLifecycleOwner.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) count++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return count
}
