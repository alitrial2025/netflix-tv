package com.example.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

/** Wraps existing screens without changing their layout. Home starts after the launch check. */
@Composable
fun UpdateGateHost(content: @Composable () -> Unit) {
    val gate: UpdateGateViewModel = viewModel()
    val state by gate.state.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(gate, activity, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null) gate.onResume(activity)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.phase, activity) {
        if (activity != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            gate.onResume(activity)
        }
    }
    if (state.phase == UpdatePhase.CONTINUE) {
        content()
    } else {
        BackHandler { gate.later() }
        UpdateGateScreen(
            state = state,
            onLater = gate::later,
            onRetry = gate::retry,
            onAllow = { activity?.let(gate::allowUpdates) },
            onApprove = { activity?.let(gate::openApproval) }
        )
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun UpdateGateScreen(
    state: UpdateGateState,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    onAllow: () -> Unit,
    onApprove: () -> Unit
) {
    val primaryFocus = remember { FocusRequester() }
    val isBusy = state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
    val action = when (state.phase) {
        UpdatePhase.PERMISSION -> "Allow updates" to onAllow
        UpdatePhase.APPROVAL -> "Confirm install" to onApprove
        UpdatePhase.ERROR -> "Retry" to onRetry
        else -> null
    }
    LaunchedEffect(state.phase) {
        if (state.phase != UpdatePhase.INSTALLING) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { primaryFocus.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("NETFLIXPRO", color = Color(0xFFE50914), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Update Gate", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            state.release?.let {
                Text("Version ${it.versionName} · ${"%.1f".format(it.sizeBytes / 1048576.0)} MB", color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
                if (it.notes.isNotBlank()) {
                    Text(it.notes, color = Color.White.copy(alpha = .82f), fontSize = 15.sp, textAlign = TextAlign.Center)
                }
            }
            Text(state.message, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
            if (state.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFFE50914), trackColor = Color.White.copy(alpha = .15f)
                )
                Text("${(state.progress * 100).toInt()}%", color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
            } else if (isBusy || state.phase == UpdatePhase.READY) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
            }
            if (state.phase == UpdatePhase.PERMISSION) {
                Text("Enable ‘Allow from this source’, then return here. Android may ask you to confirm installation.",
                    color = Color.White.copy(alpha = .65f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (action != null) GateButton(action.first, action.second, primaryFocus)
                if (state.phase != UpdatePhase.INSTALLING) {
                    GateButton(
                        if (state.phase == UpdatePhase.CHECKING) "Continue" else "Later", onLater,
                        if (action == null) primaryFocus else null
                    )
                }
            }
            if (state.phase == UpdatePhase.INSTALLING) {
                Text("Android will replace the app. It may reopen automatically; otherwise launch it again.",
                    color = Color.White.copy(alpha = .6f), fontSize = 13.sp, textAlign = TextAlign.Center)
            } else if (state.phase == UpdatePhase.DOWNLOADING) {
                Text("You can keep watching while the download finishes. Installation waits until the next launch.",
                    color = Color.White.copy(alpha = .6f), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun GateButton(label: String, action: () -> Unit, requester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    Button(
        onClick = action,
        modifier = Modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .padding(4.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}
