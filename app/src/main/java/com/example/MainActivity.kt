package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ReplayViewModel
import com.example.ui.components.QuickShareModal
import com.example.ui.components.VideoPlayerModal
import com.example.ui.screens.CameraScreen
import com.example.ui.screens.GalleryScreen
import com.example.ui.theme.QuadraReplayTheme
import android.view.WindowManager

class MainActivity : ComponentActivity() {
    private val viewModel: ReplayViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[ReplayViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            QuadraReplayTheme {
                ReplayApp(viewModel)
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (isBluetoothRemoteKey(keyCode)) {
                viewModel.triggerRemoteSaveSignal()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isBluetoothRemoteKey(keyCode: Int): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_CAMERA,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
            android.view.KeyEvent.KEYCODE_SPACE,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            android.view.KeyEvent.KEYCODE_BUTTON_A,
            android.view.KeyEvent.KEYCODE_BUTTON_B,
            android.view.KeyEvent.KEYCODE_BUTTON_C,
            android.view.KeyEvent.KEYCODE_BUTTON_X,
            android.view.KeyEvent.KEYCODE_BUTTON_Y,
            android.view.KeyEvent.KEYCODE_BUTTON_Z,
            android.view.KeyEvent.KEYCODE_BUTTON_L1,
            android.view.KeyEvent.KEYCODE_BUTTON_R1,
            android.view.KeyEvent.KEYCODE_BUTTON_L2,
            android.view.KeyEvent.KEYCODE_BUTTON_R2,
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
            android.view.KeyEvent.KEYCODE_BUTTON_START,
            android.view.KeyEvent.KEYCODE_BUTTON_MODE,
            android.view.KeyEvent.KEYCODE_BUTTON_1,
            android.view.KeyEvent.KEYCODE_BUTTON_2,
            android.view.KeyEvent.KEYCODE_BUTTON_3,
            android.view.KeyEvent.KEYCODE_BUTTON_4 -> true
            else -> false
        }
    }
}

@Composable
fun ReplayApp(viewModel: ReplayViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf("camera") } // "camera" or "gallery"

    val selectedCourt by viewModel.selectedCourtType.collectAsStateWithLifecycle()
    val bufferDurationSec by viewModel.bufferDurationSeconds.collectAsStateWithLifecycle()
    val isSavingReplay by viewModel.isSavingReplay.collectAsStateWithLifecycle()
    val isFlashEnabled by viewModel.isFlashEnabled.collectAsStateWithLifecycle()
    val selectedCameraSource by viewModel.selectedCameraSource.collectAsStateWithLifecycle()
    val isAudioEnabled by viewModel.isAudioEnabled.collectAsStateWithLifecycle()
    val isRecordingBuffer by viewModel.isRecordingBuffer.collectAsStateWithLifecycle()
    val remoteSaveTriggerSignal by viewModel.remoteSaveTriggerSignal.collectAsStateWithLifecycle()

    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val lastSavedClip by viewModel.lastSavedClip.collectAsStateWithLifecycle()
    val selectedClipForPlayback by viewModel.selectedClipForPlayback.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Crossfade(
            targetState = currentScreen,
            modifier = Modifier.padding(innerPadding),
            label = "screen_transition"
        ) { screen ->
            when (screen) {
                "camera" -> {
                    CameraScreen(
                        bufferEngine = viewModel.bufferEngine,
                        selectedCourt = selectedCourt,
                        bufferDurationSec = bufferDurationSec,
                        isSavingReplay = isSavingReplay,
                        isFlashEnabled = isFlashEnabled,
                        selectedCameraSource = selectedCameraSource,
                        isAudioEnabled = isAudioEnabled,
                        isRecordingBuffer = isRecordingBuffer,
                        totalSavedCount = clips.size,
                        onSelectCourt = { viewModel.setCourtType(it) },
                        onSelectDuration = { viewModel.setBufferDuration(it) },
                        onSave35sReplay = { viewModel.save35sInstantReplay() },
                        onToggleFlash = { viewModel.toggleFlash() },
                        onSelectCameraSource = { viewModel.setCameraSource(it) },
                        onToggleCamera = { viewModel.toggleCamera() },
                        onToggleAudio = { viewModel.toggleAudio() },
                        onOpenGallery = { currentScreen = "gallery" },
                        remoteSaveTriggerSignal = remoteSaveTriggerSignal
                    )
                }
                "gallery" -> {
                    GalleryScreen(
                        clips = clips,
                        selectedCourt = selectedCourt,
                        onSelectCourtFilter = { viewModel.setCourtType(it) },
                        onBackToCamera = { currentScreen = "camera" },
                        onSelectClip = { viewModel.setSelectedClipForPlayback(it) },
                        onDeleteClip = { viewModel.deleteClip(it) }
                    )
                }
            }
        }

        // Quick Share Popup immediately after pressing 35s Save
        lastSavedClip?.let { clip ->
            QuickShareModal(
                clip = clip,
                onDismiss = { viewModel.dismissQuickShareModal() },
                onSaveClipInfo = { newTitle, newTag ->
                    viewModel.updateClipInfo(clip, newTitle, newTag)
                },
                onPlayClip = {
                    viewModel.setSelectedClipForPlayback(clip)
                }
            )
        }

        // Video Player Modal
        selectedClipForPlayback?.let { clip ->
            VideoPlayerModal(
                clip = clip,
                onDismiss = { viewModel.setSelectedClipForPlayback(null) },
                onDeleteClip = { viewModel.deleteClip(it) }
            )
        }
    }
}
