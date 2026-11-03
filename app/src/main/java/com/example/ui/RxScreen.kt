package com.example.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.relay.AudioLatencyProfile
import com.example.relay.RxConnectionState
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.StreamAmber
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.StreamRose
import com.example.ui.theme.StreamViolet
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxScreen(
    viewModel: CaptureViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val connectionState by viewModel.rxConnectionState.collectAsStateWithLifecycle()
    val rxStats by viewModel.rxStats.collectAsStateWithLifecycle()
    val discoveredList by viewModel.discoveredTransmitters.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedLatencyProfile.collectAsStateWithLifecycle()

    var manualIp by remember { mutableStateOf("192.168.1.") }
    var isFullscreen by remember { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(true) }

    val isConnected = connectionState == RxConnectionState.CONNECTED

    if (isFullscreen && isConnected) {
        // Fullscreen Mode with Overlay HUD
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showHud = !showHud }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.setRxSurfaceHolder(holder)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.setRxSurfaceHolder(null)
                            }
                        })
                    }
                }
            )

            AnimatedVisibility(
                visible = showHud,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top Bar
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = DarkSurface.copy(alpha = 0.85f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(StreamEmerald)
                                )
                                Text(
                                    text = "ScreenStream Live (${rxStats.videoCodec})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = { isFullscreen = false },
                                    modifier = Modifier.testTag("exit_fullscreen_button")
                                ) {
                                    Icon(
                                        Icons.Default.FullscreenExit,
                                        contentDescription = "Exit Fullscreen",
                                        tint = StreamCyan
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.disconnectRx() },
                                    modifier = Modifier.testTag("disconnect_rx_button")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = StreamRose)
                                }
                            }
                        }
                    }

                    // Bottom Floating Diagnostics HUD
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurface.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, StreamCyan.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            HudItem("FPS", "%.1f".format(rxStats.fps))
                            HudItem("Bitrate", "%.1f Mbps".format(rxStats.videoBytesPerSecond / 1_000_000f))
                            HudItem("RTT", "%.0f ms".format(rxStats.rttAvgMs))
                            HudItem("Decode", "${rxStats.decodeTimeMs} ms")
                        }
                    }
                }
            }
        }
    } else {
        // Standard Layout
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = DarkBg,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Screen Receiver",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = connectionState.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isConnected) StreamEmerald else StreamCyan
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("rx_back_button")) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = DarkSurface)
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Monitor View
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.5.dp, if (isConnected) StreamEmerald else DarkBorder)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            viewModel.setRxSurfaceHolder(holder)
                                        }
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            viewModel.setRxSurfaceHolder(null)
                                        }
                                    })
                                }
                            }
                        )

                        if (!isConnected) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(DarkBg.copy(alpha = 0.7f)),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = StreamCyan.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (connectionState == RxConnectionState.CONNECTING) "Connecting to Screen Stream..." else "Awaiting Screen Stream Connection",
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                            }
                        } else {
                            // Expand to Fullscreen button
                            IconButton(
                                onClick = { isFullscreen = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(36.dp)
                                    .background(DarkSurface.copy(alpha = 0.7f), CircleShape)
                                    .testTag("toggle_fullscreen_button")
                            ) {
                                Icon(
                                    Icons.Default.Fullscreen,
                                    contentDescription = "Fullscreen",
                                    tint = StreamCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // If connected: HUD and controls
                if (isConnected) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, DarkBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, tint = StreamCyan)
                                    Text("Receiver Telemetry", fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                Button(
                                    onClick = { viewModel.disconnectRx() },
                                    colors = ButtonDefaults.buttonColors(containerColor = StreamRose),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Disconnect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkSurfaceElevated, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                TelemetryItem("FPS", "%.1f".format(rxStats.fps), StreamEmerald)
                                TelemetryItem("Bitrate", "%.1f M".format(rxStats.videoBytesPerSecond / 1_000_000f), StreamCyan)
                                TelemetryItem("RTT", "%.0f ms".format(rxStats.rttAvgMs), StreamAmber)
                                TelemetryItem("Decode", "${rxStats.decodeTimeMs}ms", StreamViolet)
                            }

                            // Audio Latency Profile Selector
                            Text("Audio Buffer Profile", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AudioLatencyProfile.values().forEach { profile ->
                                    FilterChip(
                                        selected = selectedProfile == profile,
                                        onClick = { viewModel.setAudioLatencyProfile(profile) },
                                        label = { Text(profile.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = StreamEmerald.copy(alpha = 0.2f),
                                            selectedLabelColor = StreamEmerald
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Discovered Broadcasters on LAN
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Router, contentDescription = null, tint = StreamEmerald)
                            Text("Auto-Discovered Broadcasters", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        if (discoveredList.isEmpty()) {
                            Text(
                                text = "Scanning local Wi-Fi for active ScreenStream hosts...",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                discoveredList.forEach { tx ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = DarkSurfaceElevated,
                                        border = BorderStroke(1.dp, DarkBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(tx.name, fontWeight = FontWeight.Bold, color = Color.White)
                                                Text("${tx.host}:${tx.port}", fontSize = 12.sp, color = StreamCyan)
                                            }

                                            Button(
                                                onClick = { viewModel.connectRx(tx.host, tx.port) },
                                                colors = ButtonDefaults.buttonColors(containerColor = StreamCyan, contentColor = Color.Black),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.testTag("connect_discovered_button")
                                            ) {
                                                Text("Connect", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Manual IP Connection Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Manual IP Connection", fontWeight = FontWeight.Bold, color = Color.White)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = { manualIp = it },
                                label = { Text("Transmitter IP") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("manual_ip_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = StreamCyan,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Button(
                                onClick = { viewModel.connectRx(manualIp, 4120) },
                                colors = ButtonDefaults.buttonColors(containerColor = StreamCyan, contentColor = Color.Black),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .height(56.dp)
                                    .testTag("connect_manual_button")
                            ) {
                                Text("Connect", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudItem(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = StreamCyan)
        Text(label, fontSize = 10.sp, color = TextSecondary)
    }
}

@Composable
private fun TelemetryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = color)
        Text(label, fontSize = 11.sp, color = TextSecondary)
    }
}
