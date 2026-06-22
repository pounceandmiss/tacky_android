package com.example.tackyapk.feature.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Wrap a call action behind a just-in-time RECORD_AUDIO request: the mic is asked
 * for only when the user actually starts or answers a call, never at app launch.
 * If the permission is already held, [action] runs immediately; otherwise the
 * system prompt shows and [action] runs only once it's granted (a denial is a
 * no-op - the user simply gets no call).
 */
@Composable
fun rememberMicGatedAction(action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(action)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) latest() }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            latest()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
