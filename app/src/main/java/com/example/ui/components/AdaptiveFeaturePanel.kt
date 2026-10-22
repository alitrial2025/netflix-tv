package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Remote-friendly edge panel on television; a touch sheet with native dismissal on phones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveFeaturePanel(television: Boolean, title: String, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (television) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .4f)).clickable(onClick = onDismiss))
                var entered by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { entered = true }
                AnimatedVisibility(entered, modifier = Modifier.align(Alignment.CenterEnd),
                    enter = slideInHorizontally(tween(180)) { it } + fadeIn(tween(180))) {
                    Surface(modifier = modifier.fillMaxHeight().fillMaxWidth(.46f).widthIn(max = 520.dp)
                        .semantics { paneTitle = title }.testTag("feature_side_panel"),
                        color = Color(0xFF17191D), contentColor = Color.White,
                        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))) {
                        Column(Modifier.fillMaxSize().padding(20.dp), content = content)
                    }
                }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF17191D), contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = modifier.semantics { paneTitle = title }.testTag("feature_bottom_sheet")) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}
