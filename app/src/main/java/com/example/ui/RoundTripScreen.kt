package com.example.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun RoundTripScreen(
    viewModel: CaptureViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val rxConnectionState by viewModel.rxConnectionState.collectAsStateWithLifecycle()
    val rxStats by viewModel.rxStats.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharingScreen.collectAsStateWithLifecycle()
    val localIp by viewModel.localIp.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.latencyTestEngine.start()
        onDispose {
            viewModel.latencyTestEngine.stop()
            viewModel.disconnectRx()
        }
    }

    val isConnected = rxConnectionState == RxConnectionState.CONNECTED

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBg,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Glass-to-Glass Benchmark",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Side-by-Side Local vs Received Stream",
                            style = MaterialTheme.typography.labelSmall,
                            color = StreamViolet
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("round_trip_back_button")) {
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
            // Benchmark control banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Loopback Benchmark", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            text = if (isConnected) "Connected to $localIp:4120" else "Streams test pattern to receiver",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    if (isConnected) {
                        Button(
                            onClick = { viewModel.disconnectRx() },
                            colors = ButtonDefaults.buttonColors(containerColor = StreamRose),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("stop_loopback_button")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop Loopback")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.connectRx("127.0.0.1", 4120) },
                            colors = ButtonDefaults.buttonColors(containerColor = StreamCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("start_loopback_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start Loopback", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Side-by-side comparative views
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left: Local Reference Clock
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "1. Local Reference (0.0ms)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = StreamCyan
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        border = BorderStroke(1.dp, StreamCyan.copy(alpha = 0.5f))
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(h: SurfaceHolder) {
                                            viewModel.latencyTestEngine.attachSurface(h)
                                        }
                                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hPx: Int) {
                                            viewModel.latencyTestEngine.onSurfaceSizeChanged(w, hPx)
                                        }
                                        override fun surfaceDestroyed(h: SurfaceHolder) {
                                            viewModel.latencyTestEngine.detachSurface(h)
                                        }
                                    })
                                }
                            }
                        )
                    }
                }

                // Right: Received Stream
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "2. Received Stream",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = StreamEmerald
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        border = BorderStroke(1.dp, if (isConnected) StreamEmerald else DarkBorder)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    SurfaceView(ctx).apply {
                                        holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(h: SurfaceHolder) {
                                                viewModel.setRxSurfaceHolder(h)
                                            }
                                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hPx: Int) {}
                                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                                viewModel.setRxSurfaceHolder(null)
                                            }
                                        })
                                    }
                                }
                            )

                            if (!isConnected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(DarkBg.copy(alpha = 0.8f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Loopback Inactive",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Real-Time Delay Metrics Card
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
                    Text("Live Latency Telemetry", fontWeight = FontWeight.Bold, color = Color.White)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceElevated, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        MetricPill("Network RTT", "%.1f ms".format(rxStats.rttAvgMs), StreamCyan)
                        MetricPill("Decode Latency", "${rxStats.decodeTimeMs} ms", StreamEmerald)
                        MetricPill("Receiver FPS", "%.1f".format(rxStats.fps), StreamAmber)
                        MetricPill("Codec", rxStats.videoCodec, StreamViolet)
                    }

                    Text(
                        text = "Tip: When streaming to another phone or PC over Wi-Fi, compare the millisecond numbers visible in both screens to observe total glass-to-glass latency (<30-50ms).",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = color)
        Text(label, fontSize = 11.sp, color = TextSecondary)
    }
}
