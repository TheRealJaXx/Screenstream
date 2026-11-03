package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AppMode
import com.example.ui.CaptureViewModel
import com.example.ui.LatencyTestScreen
import com.example.ui.ModeSelectionScreen
import com.example.ui.RoundTripScreen
import com.example.ui.RxScreen
import com.example.ui.TxScreen
import com.example.ui.theme.DarkBg
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
                val localIp by viewModel.localIp.collectAsStateWithLifecycle()

                // MediaProjection Screen Capture Launcher
                val screenCaptureLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        viewModel.startScreenShare(result.resultCode, result.data!!)
                    }
                }

                // Normal / Dangerous permissions launcher
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // Permissions handled
                }

                LaunchedEffect(currentMode) {
                    if (currentMode == AppMode.TX) {
                        val needed = mutableListOf<String>()
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            needed.add(Manifest.permission.RECORD_AUDIO)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (needed.isNotEmpty()) {
                            permissionsLauncher.launch(needed.toTypedArray())
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBg
                ) {
                    when (currentMode) {
                        AppMode.UNSELECTED -> {
                            ModeSelectionScreen(
                                localIp = localIp,
                                onSelectMode = { mode -> viewModel.selectMode(mode) }
                            )
                        }

                        AppMode.TX -> {
                            TxScreen(
                                viewModel = viewModel,
                                onRequestScreenCapture = {
                                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                                },
                                onBack = { viewModel.selectMode(AppMode.UNSELECTED) }
                            )
                        }

                        AppMode.RX -> {
                            RxScreen(
                                viewModel = viewModel,
                                onBack = { viewModel.selectMode(AppMode.UNSELECTED) }
                            )
                        }

                        AppMode.TEST_TX -> {
                            LatencyTestScreen(
                                viewModel = viewModel,
                                onChangeMode = { viewModel.selectMode(AppMode.UNSELECTED) }
                            )
                        }

                        AppMode.ROUND_TRIP -> {
                            RoundTripScreen(
                                viewModel = viewModel,
                                onBack = { viewModel.selectMode(AppMode.UNSELECTED) }
                            )
                        }
                    }
                }
            }
        }
    }
}
