package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AudioMode
import com.example.model.LogEntry
import com.example.model.ScreenResolution
import com.example.model.ScreenShareConfig
import com.example.model.StreamBitrate
import com.example.model.TargetFps
import com.example.model.VideoCodec
import com.example.relay.TxRelayStats
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
fun TxScreen(
    viewModel: CaptureViewModel,
    onRequestScreenCapture: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val isSharing by viewModel.isSharingScreen.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val localIp by viewModel.localIp.collectAsStateWithLifecycle()
    val webUrl by viewModel.webUrl.collectAsStateWithLifecycle()
    val qrBitmap by viewModel.qrBitmap.collectAsStateWithLifecycle()
    val txStats by viewModel.txStats.collectAsStateWithLifecycle()
    val webViewers by viewModel.connectedWebViewers.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var showQrDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBg,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Broadcast Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (isSharing) "LIVE • ${txStats.connectedClientsCount + webViewers} Viewers" else "Ready to Stream",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSharing) StreamEmerald else StreamCyan
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("tx_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLogsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Logs",
                            tint = StreamCyan
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
            // 1. Live Hero Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.5.dp, if (isSharing) StreamEmerald else DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (isSharing) StreamEmerald else StreamAmber)
                            )
                            Column {
                                Text(
                                    text = if (isSharing) "STREAMING ACTIVE" else "BROADCASTER IDLE",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    color = if (isSharing) StreamEmerald else Color.White
                                )
                                Text(
                                    text = if (isSharing) "Screen capturing in background" else "Select settings & start capture",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        if (isSharing) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = StreamEmerald.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, StreamEmerald)
                            ) {
                                Text(
                                    text = "60 FPS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StreamEmerald,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (isSharing) {
                        // Metrics strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceElevated, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            TelemetryStat("Sent Frames", "${txStats.framesSent}")
                            TelemetryStat("Web Viewers", "$webViewers")
                            TelemetryStat("App Clients", "${txStats.connectedClientsCount}")
                            TelemetryStat("Dropped", "${txStats.framesDropped}")
                        }

                        Button(
                            onClick = { viewModel.stopScreenShare() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("stop_screen_share_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = StreamRose),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.StopScreenShare, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Stop Screen Sharing", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onRequestScreenCapture,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("start_screen_share_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StreamCyan,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ScreenShare, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Screen Sharing", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. Web Viewer & Link Sharing Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            Icon(Icons.Default.Language, contentDescription = null, tint = StreamCyan)
                            Text(
                                text = "Universal Web Viewer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "Any Browser",
                            fontSize = 11.sp,
                            color = StreamCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "Anyone on the same Wi-Fi network can watch your screen in Chrome, Safari, Edge, or Smart TV:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = DarkBg,
                        border = BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = webUrl,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StreamCyan
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", webUrl))
                                        Toast.makeText(context, "URL copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("copy_link_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Link", tint = TextPrimary, modifier = Modifier.size(18.dp))
                                }

                                IconButton(
                                    onClick = {
                                        try {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                                            context.startActivity(browserIntent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("open_browser_button")
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in Browser", tint = StreamCyan, modifier = Modifier.size(20.dp))
                                }

                                IconButton(
                                    onClick = { showQrDialog = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("show_qr_button")
                                ) {
                                    Icon(Icons.Default.QrCode, contentDescription = "Show QR Code", tint = StreamViolet, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 3. Encoder & Stream Configuration
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = StreamViolet)
                        Text(
                            text = "Stream Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Codec Selector
                    Text("Video Codec", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = config.codec == VideoCodec.H264,
                            onClick = { viewModel.updateCodec(VideoCodec.H264) },
                            label = { Text("H.264 (Hardware GPU)") },
                            modifier = Modifier.testTag("codec_h264_chip"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StreamCyan.copy(alpha = 0.2f),
                                selectedLabelColor = StreamCyan
                            )
                        )
                        FilterChip(
                            selected = config.codec == VideoCodec.MJPEG,
                            onClick = { viewModel.updateCodec(VideoCodec.MJPEG) },
                            label = { Text("MJPEG (Web)") },
                            modifier = Modifier.testTag("codec_mjpeg_chip"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StreamViolet.copy(alpha = 0.2f),
                                selectedLabelColor = StreamViolet
                            )
                        )
                    }

                    // Resolution
                    Text("Resolution", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScreenResolution.values().forEach { res ->
                            FilterChip(
                                selected = config.resolution == res,
                                onClick = { viewModel.updateResolution(res) },
                                label = { Text(if (res == ScreenResolution.RES_720P) "720p HD" else if (res == ScreenResolution.RES_1080P) "1080p FHD" else "540p") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StreamCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = StreamCyan
                                )
                            )
                        }
                    }

                    // Framerate
                    Text("Framerate Target", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetFps.values().forEach { fps ->
                            FilterChip(
                                selected = config.fps == fps,
                                onClick = { viewModel.updateFps(fps) },
                                label = { Text(fps.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StreamEmerald.copy(alpha = 0.2f),
                                    selectedLabelColor = StreamEmerald
                                )
                            )
                        }
                    }

                    // Audio Source
                    Text("Audio Capture", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AudioMode.values().forEach { mode ->
                            FilterChip(
                                selected = config.audioMode == mode,
                                onClick = { viewModel.updateAudioMode(mode) },
                                label = { Text(mode.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StreamViolet.copy(alpha = 0.2f),
                                    selectedLabelColor = StreamViolet
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // QR Code Modal Dialog
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = {
                Text(
                    text = "Scan to Watch Live",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Point any phone, tablet, or laptop camera at this QR code to open the screen viewer in browser:",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )

                    qrBitmap?.let { bmp ->
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "ScreenStream QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Text(
                        text = webUrl,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = StreamCyan
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Close", color = StreamCyan)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Logs Dialog
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("System Console", fontWeight = FontWeight.Bold, color = Color.White)
                    TextButton(onClick = { viewModel.clearLogs() }) {
                        Text("Clear", color = StreamRose)
                    }
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(DarkBg, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { entry ->
                        Text(
                            text = "[${entry.timestamp}] [${entry.tag}] ${entry.message}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (entry.isError) StreamRose else TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("Close", color = StreamCyan)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun TelemetryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp,
            color = Color.White
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextSecondary
        )
    }
}
