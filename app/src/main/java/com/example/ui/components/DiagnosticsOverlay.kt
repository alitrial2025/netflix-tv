package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.util.AppDiagnosticsLogger

@Composable
fun DiagnosticsConsoleDialog(
    onDismissRequest: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rawLogs by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("ALL") } // "ALL", "CDN", "ERROR", "PERF"
    var copyStatus by remember { mutableStateOf<String?>(null) }

    // Load logs on launch and when refreshed
    fun loadLogs() {
        rawLogs = AppDiagnosticsLogger.readLogFileContent(350)
    }

    LaunchedEffect(Unit) {
        loadLogs()
    }

    // Parse and filter log lines
    val filteredLines = remember(rawLogs, filterMode) {
        val lines = rawLogs.split("\n")
        when (filterMode) {
            "CDN" -> lines.filter { it.contains("DirectCDN", ignoreCase = true) || it.contains("CDN", ignoreCase = true) }
            "ERROR" -> lines.filter { it.contains("ERROR", ignoreCase = true) || it.contains("FAILED", ignoreCase = true) || it.contains("Exception", ignoreCase = true) }
            "PERF" -> lines.filter { it.contains("PERF", ignoreCase = true) || it.contains("took", ignoreCase = true) }
            else -> lines
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false // Lets us take up almost the full screen
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFB141414), // Solid dark grey background with slight opacity
            border = BorderStroke(1.5.dp, Color(0xFF333333))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NETFLIX PRO TV - DIAGNOSTICS & TELEMETRY",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFE50914), // Netflix Red
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Primary: ${AppDiagnosticsLogger.getLogFilePath()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        copyStatus?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Top Action Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val success = AppDiagnosticsLogger.copyLogsToClipboard(context)
                                copyStatus = if (success) "✅ Copied logs to clipboard!" else "❌ Failed to copy"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Copy Logs", color = Color.White)
                        }

                        Button(
                            onClick = {
                                loadLogs()
                                copyStatus = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Refresh", color = Color.White)
                        }

                        Button(
                            onClick = {
                                AppDiagnosticsLogger.clearLogFile()
                                loadLogs()
                                copyStatus = "Logs cleared"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF401515)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Clear", color = Color(0xFFFF5252))
                        }

                        Button(
                            onClick = onDismissRequest,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Close", color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Filters
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val filters = listOf(
                        "ALL" to "All Logs",
                        "CDN" to "DirectCDN Flow",
                        "ERROR" to "Errors & Exceptions",
                        "PERF" to "Performance & Speed"
                    )

                    filters.forEach { (key, label) ->
                        val isSelected = filterMode == key
                        Button(
                            onClick = { filterMode = key },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFE50914) else Color(0xFF222222)
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color.LightGray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Console Output Terminal
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0A0A))
                        .border(1.dp, Color(0xFF222222), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    if (filteredLines.isEmpty() || (filteredLines.size == 1 && filteredLines[0].isBlank())) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching log entries found.",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        val scrollState = rememberScrollState()
                        
                        // Auto scroll to bottom when logs are loaded
                        LaunchedEffect(filteredLines.size) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            filteredLines.forEach { line ->
                                if (line.isNotBlank()) {
                                    val color = when {
                                        line.contains("[ERROR]") || line.contains("FAILED") -> Color(0xFFFF5252)
                                        line.contains("[PERF_HOTSPOT_WARNING]") -> Color(0xFFFFB300)
                                        line.contains("DirectCDN") && line.contains("successfully") -> Color(0xFF4CAF50)
                                        line.contains("[EVENT]") -> Color(0xFF2196F3)
                                        else -> Color.LightGray
                                    }
                                    
                                    Text(
                                        text = line,
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
