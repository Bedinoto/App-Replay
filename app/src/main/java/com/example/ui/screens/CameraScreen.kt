package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.media.ReplayBufferEngine
import com.example.ui.CameraSource
import com.example.ui.theme.DarkBg
import com.example.ui.theme.LiveBufferRed
import com.example.ui.theme.OnPrimaryDark
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    bufferEngine: ReplayBufferEngine,
    selectedCourt: String,
    bufferDurationSec: Int,
    isSavingReplay: Boolean,
    isFlashEnabled: Boolean,
    selectedCameraSource: CameraSource,
    isAudioEnabled: Boolean,
    isRecordingBuffer: Boolean,
    totalSavedCount: Int,
    onSelectCourt: (String) -> Unit,
    onSelectDuration: (Int) -> Unit,
    onSave35sReplay: () -> Unit,
    onToggleFlash: () -> Unit,
    onSelectCameraSource: (CameraSource) -> Unit,
    onToggleCamera: () -> Unit,
    onToggleAudio: () -> Unit,
    onOpenGallery: () -> Unit,
    remoteSaveTriggerSignal: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    val isCameraGranted = permissionsState.permissions.find {
        it.permission == Manifest.permission.CAMERA
    }?.status?.isGranted == true || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    val isAudioGranted = permissionsState.permissions.find {
        it.permission == Manifest.permission.RECORD_AUDIO
    }?.status?.isGranted == true || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) {
        if (!isCameraGranted || !isAudioGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var isTriggeringFlashSignal by remember { mutableStateOf(false) }

    var showCameraPickerSheet by remember { mutableStateOf(false) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var activeVideoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var detectedMinZoomRatio by remember { mutableStateOf(0.5f) }
    var detectedMaxZoomRatio by remember { mutableStateOf(10.0f) }
    var currentZoomRatio by remember { mutableStateOf(1.0f) }
    var isZoomSliderVisible by remember { mutableStateOf(false) }

    val applyZoomRatio: (Float) -> Unit = remember(activeCamera, detectedMinZoomRatio, detectedMaxZoomRatio) {
        { targetZoom: Float ->
            val minR = if (detectedMinZoomRatio <= 0.1f) 0.5f else detectedMinZoomRatio
            val maxR = if (detectedMaxZoomRatio <= 1.0f) 10.0f else detectedMaxZoomRatio
            val clamped = targetZoom.coerceIn(minR, maxR)
            currentZoomRatio = clamped
            activeCamera?.let { cam ->
                try {
                    cam.cameraControl.setZoomRatio(clamped)
                } catch (e: Exception) {
                    Log.w("CameraScreen", "Error setting zoom ratio $clamped", e)
                }
            }
        }
    }

    // Dynamic Orientation Listener to update targetRotation on device rotation (Portrait / Landscape)
    val orientationEventListener = remember(context) {
        object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rotation = when (orientation) {
                    in 45..134 -> android.view.Surface.ROTATION_270
                    in 135..224 -> android.view.Surface.ROTATION_180
                    in 225..314 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }

                activeVideoCapture?.targetRotation = rotation
            }
        }
    }

    DisposableEffect(orientationEventListener, activeVideoCapture) {
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
        onDispose {
            orientationEventListener.disable()
        }
    }

    // Trigger save replay & camera flash when Bluetooth remote controller signal is received
    LaunchedEffect(remoteSaveTriggerSignal) {
        if (remoteSaveTriggerSignal > 0 && !isSavingReplay && !isTriggeringFlashSignal) {
            coroutineScope.launch {
                isTriggeringFlashSignal = true
                try {
                    activeCamera?.let { cam ->
                        if (cam.cameraInfo.hasFlashUnit()) {
                            cam.cameraControl.enableTorch(true)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("CameraScreen", "Error enabling torch pulse", e)
                }

                delay(600)

                try {
                    activeCamera?.let { cam ->
                        if (cam.cameraInfo.hasFlashUnit()) {
                            cam.cameraControl.enableTorch(isFlashEnabled)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("CameraScreen", "Error restoring torch state", e)
                }
                isTriggeringFlashSignal = false

                onSave35sReplay()
            }
        }
    }

    val courtTypes = listOf(
        "Futebol" to "⚽ Futebol",
        "Padel" to "🎾 Padel",
        "Vôlei" to "🏐 Vôlei",
        "Beach Tennis" to "🎾 Beach Tennis",
        "Futmesa" to "🏓 Futmesa",
        "Geral" to "🏆 Geral"
    )

    val durationOptions = listOf(15, 30, 35, 60)

    // Pulse animation for recording status badge & save button
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Bind / Re-bind CameraX when camera source, previewView, or permissions change
    LaunchedEffect(selectedCameraSource, previewViewRef, isCameraGranted) {
        val pView = previewViewRef ?: return@LaunchedEffect
        if (!isCameraGranted) return@LaunchedEffect

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val displayRotation = pView.display?.rotation ?: android.view.Surface.ROTATION_0

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val vCapture = VideoCapture.Builder(recorder)
                    .setTargetRotation(displayRotation)
                    .build()

                val preview = Preview.Builder()
                    .setTargetRotation(displayRotation)
                    .build()
                preview.setSurfaceProvider(pView.surfaceProvider)

                val availableInfos = cameraProvider.availableCameraInfos
                val backInfos = availableInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }

                val getMinFocalLength: (androidx.camera.core.CameraInfo) -> Float = { info ->
                    try {
                        val c2Info = Camera2CameraInfo.from(info)
                        val focals = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        focals?.minOrNull() ?: 4.0f
                    } catch (e: Exception) {
                        4.0f
                    }
                }

                val targetCameraInfo = when (selectedCameraSource) {
                    CameraSource.FRONT_SELFIE -> {
                        availableInfos.find { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                    }
                    CameraSource.BACK_WIDE -> {
                        if (backInfos.size > 1) {
                            backInfos.minByOrNull { getMinFocalLength(it) } ?: backInfos.firstOrNull()
                        } else {
                            backInfos.firstOrNull()
                        }
                    }
                    CameraSource.BACK_MAIN -> {
                        if (backInfos.size > 1) {
                            val sortedByFocal = backInfos.sortedBy { getMinFocalLength(it) }
                            sortedByFocal.getOrNull(1) ?: sortedByFocal.lastOrNull()
                        } else {
                            backInfos.firstOrNull()
                        }
                    }
                }

                val primarySelector = if (targetCameraInfo != null) {
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { it == targetCameraInfo }
                        }
                        .build()
                } else {
                    CameraSelector.Builder()
                        .requireLensFacing(selectedCameraSource.lensFacing)
                        .build()
                }

                val fallbackSelector = CameraSelector.Builder()
                    .requireLensFacing(selectedCameraSource.lensFacing)
                    .build()

                val selectorToUse = if (cameraProvider.hasCamera(primarySelector)) primarySelector else fallbackSelector

                cameraProvider.unbindAll()

                if (cameraProvider.hasCamera(selectorToUse)) {
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selectorToUse,
                        preview,
                        vCapture
                    )
                    activeCamera = camera
                    activeVideoCapture = vCapture

                    if (camera.cameraInfo.hasFlashUnit()) {
                        camera.cameraControl.enableTorch(isFlashEnabled)
                    }

                    bufferEngine.setVideoCapture(vCapture, enableAudio = isAudioGranted && isAudioEnabled)
                } else {
                    Log.w("CameraScreen", "Selected camera $selectedCameraSource not found on device.")
                }
            } catch (e: Exception) {
                Log.e("CameraScreen", "Error setting up camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Handle torch toggle dynamically
    LaunchedEffect(isFlashEnabled, activeCamera) {
        activeCamera?.let { cam ->
            if (cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(isFlashEnabled)
            }
        }
    }

    // Keep detected zoom bounds up-to-date and apply target zoom ratio whenever zoomState or camera source changes
    DisposableEffect(activeCamera, selectedCameraSource, lifecycleOwner) {
        val cam = activeCamera ?: return@DisposableEffect onDispose {}
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoomState ->
            if (zoomState != null) {
                val minZoom = zoomState.minZoomRatio
                val maxZoom = zoomState.maxZoomRatio
                detectedMinZoomRatio = minZoom
                detectedMaxZoomRatio = maxZoom

                val targetZoom = when (selectedCameraSource) {
                    CameraSource.BACK_WIDE -> {
                        if (minZoom < 0.95f) minZoom else 0.5f.coerceIn(minZoom, maxZoom)
                    }
                    CameraSource.BACK_MAIN -> {
                        1.0f.coerceIn(minZoom, maxZoom)
                    }
                    CameraSource.FRONT_SELFIE -> {
                        1.0f.coerceIn(minZoom, maxZoom)
                    }
                }

                currentZoomRatio = targetZoom
                try {
                    cam.cameraControl.setZoomRatio(targetZoom)
                } catch (e: Exception) {
                    Log.w("CameraScreen", "Error setting zoom ratio $targetZoom", e)
                }
            }
        }
        cam.cameraInfo.zoomState.observe(lifecycleOwner, observer)
        onDispose {
            cam.cameraInfo.zoomState.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .testTag("camera_screen")
    ) {
        if (isCameraGranted) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { pView ->
                        previewViewRef = pView
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Simulation Canvas View when camera permissions are requested / fallback
            CourtSimulationCanvas(selectedCourt = selectedCourt)

            Surface(
                color = SurfaceDark.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Acesso à Câmera Necessário",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Conceda permissão de câmera e áudio para gravar e salvar os replays das suas jogadas.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { permissionsState.launchMultiplePermissionRequest() },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Conceder Permissão",
                            color = OnPrimaryDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Top Controls Overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Live Buffer Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceDark.copy(alpha = 0.9f))
                        .border(1.dp, LiveBufferRed, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(LiveBufferRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BUFFER DE ${bufferDurationSec}s ATIVO",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Quick camera toggles
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flash Toggle
                    IconButton(
                        onClick = onToggleFlash,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SurfaceDark.copy(alpha = 0.85f))
                    ) {
                        Icon(
                            imageVector = if (isFlashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = if (isFlashEnabled) PrimaryBlue else TextPrimary
                        )
                    }

                    // Quick Lens Selector Pills (0.6x / 1.0x / Frontal)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceDark.copy(alpha = 0.85f))
                            .border(1.dp, SurfaceVariantDark, RoundedCornerShape(20.dp))
                            .padding(3.dp)
                    ) {
                        val wideZoomLabel = if (detectedMinZoomRatio < 0.95f) "${String.format(java.util.Locale.US, "%.1f", detectedMinZoomRatio)}x" else "0.6x"
                        val lenses = listOf(
                            Triple(CameraSource.BACK_WIDE, wideZoomLabel, "Ultra-Wide"),
                            Triple(CameraSource.BACK_MAIN, "1.0x", "Principal"),
                            Triple(CameraSource.FRONT_SELFIE, "Frontal", "Selfie")
                        )

                        lenses.forEach { (source, tag, name) ->
                            val isSelected = selectedCameraSource == source
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) PrimaryBlue else Color.Transparent)
                                    .clickable { onSelectCameraSource(source) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = tag,
                                    color = if (isSelected) OnPrimaryDark else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Audio Toggle
                    IconButton(
                        onClick = onToggleAudio,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SurfaceDark.copy(alpha = 0.85f))
                    ) {
                        Icon(
                            imageVector = if (isAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Áudio",
                            tint = if (isAudioEnabled) PrimaryBlue else TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sport Type Selector Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(courtTypes) { (key, label) ->
                    val isSelected = selectedCourt == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) PrimaryBlue else SurfaceDark.copy(alpha = 0.85f))
                            .border(
                                1.dp,
                                if (isSelected) PrimaryBlue else SurfaceVariantDark,
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { onSelectCourt(key) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) OnPrimaryDark else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bluetooth Remote Ready Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark.copy(alpha = 0.85f))
                    .border(0.5.dp, PrimaryBlue.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Controle Bluetooth / Volume Ativo",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Bottom Controls Overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Camera Zoom Control Panel (Preset Buttons + Manual Slider)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark.copy(alpha = 0.9f))
                    .border(1.dp, SurfaceVariantDark, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Preset Zoom Buttons Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val wideLabel = if (detectedMinZoomRatio < 0.95f) "${String.format(java.util.Locale.US, "%.1f", detectedMinZoomRatio)}x" else "0.6x"
                    
                    val presets = mutableListOf<Pair<Float, String>>()
                    if (detectedMinZoomRatio < 0.95f) {
                        presets.add(detectedMinZoomRatio to wideLabel)
                    } else {
                        presets.add(0.6f to "0.6x")
                    }
                    presets.add(1.0f to "1.0x")
                    if (detectedMaxZoomRatio >= 2.0f) {
                        presets.add(2.0f to "2.0x")
                    }
                    if (detectedMaxZoomRatio >= 5.0f) {
                        presets.add(5.0f to "5.0x")
                    }

                    presets.forEach { (ratio, label) ->
                        val isSelected = kotlin.math.abs(currentZoomRatio - ratio) < 0.15f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) PrimaryBlue else Color.Transparent)
                                .clickable {
                                    if (ratio < 0.95f && selectedCameraSource != CameraSource.BACK_WIDE) {
                                        onSelectCameraSource(CameraSource.BACK_WIDE)
                                    } else if (ratio >= 0.95f && selectedCameraSource == CameraSource.BACK_WIDE) {
                                        onSelectCameraSource(CameraSource.BACK_MAIN)
                                    }
                                    applyZoomRatio(ratio)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) OnPrimaryDark else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                            )
                        }
                    }

                    // Manual Slider Toggle Pill Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isZoomSliderVisible) PrimaryBlue.copy(alpha = 0.2f) else Color.Transparent)
                            .border(0.5.dp, if (isZoomSliderVisible) PrimaryBlue else SurfaceVariantDark, RoundedCornerShape(14.dp))
                            .clickable { isZoomSliderVisible = !isZoomSliderVisible }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ZoomIn,
                                contentDescription = "Ajuste de Zoom",
                                tint = if (isZoomSliderVisible) PrimaryBlue else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isZoomSliderVisible) "${String.format(java.util.Locale.US, "%.1f", currentZoomRatio)}x" else "Slider",
                                color = if (isZoomSliderVisible) PrimaryBlue else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Interactive Continuous Zoom Slider
                if (isZoomSliderVisible) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            text = "${String.format(java.util.Locale.US, "%.1f", detectedMinZoomRatio)}x",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Slider(
                            value = currentZoomRatio.coerceIn(detectedMinZoomRatio, detectedMaxZoomRatio.coerceAtLeast(1.0f)),
                            onValueChange = { newVal ->
                                applyZoomRatio(newVal)
                            },
                            valueRange = detectedMinZoomRatio..detectedMaxZoomRatio.coerceAtLeast(1.0f).coerceAtMost(10.0f),
                            colors = SliderDefaults.colors(
                                thumbColor = PrimaryBlue,
                                activeTrackColor = PrimaryBlue,
                                inactiveTrackColor = SurfaceVariantDark
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                        )

                        Text(
                            text = "${String.format(java.util.Locale.US, "%.1f", detectedMaxZoomRatio.coerceAtMost(10.0f))}x",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Duration selector pills
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark.copy(alpha = 0.9f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                durationOptions.forEach { dur ->
                    val isSelected = bufferDurationSec == dur
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) PrimaryBlue else Color.Transparent)
                            .clickable { onSelectDuration(dur) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${dur}s",
                            color = if (isSelected) OnPrimaryDark else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Massive Instant Replay Save Button & Gallery Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(56.dp)) // Spacer to balance gallery button

                // Main "SALVAR REPLAY 35s" Trigger Button (Elegant Dark Palette)
                Box(
                    modifier = Modifier
                        .scale(pulseScale)
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(PrimaryBlue)
                        .border(3.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                        .clickable(enabled = !isSavingReplay && !isTriggeringFlashSignal) {
                            coroutineScope.launch {
                                isTriggeringFlashSignal = true
                                try {
                                    activeCamera?.let { cam ->
                                        if (cam.cameraInfo.hasFlashUnit()) {
                                            cam.cameraControl.enableTorch(true)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("CameraScreen", "Error enabling torch pulse", e)
                                }

                                delay(600)

                                try {
                                    activeCamera?.let { cam ->
                                        if (cam.cameraInfo.hasFlashUnit()) {
                                            cam.cameraControl.enableTorch(isFlashEnabled)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("CameraScreen", "Error restoring torch state", e)
                                }
                                isTriggeringFlashSignal = false

                                onSave35sReplay()
                            }
                        }
                        .testTag("save_35s_replay_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = OnPrimaryDark,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "CLIP ${bufferDurationSec}s",
                            color = OnPrimaryDark,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "SALVAR",
                            color = OnPrimaryDark,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Gallery Shortcut Floating Button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark)
                        .border(1.dp, SurfaceVariantDark, CircleShape)
                        .clickable { onOpenGallery() }
                        .testTag("open_gallery_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Galeria",
                            tint = PrimaryBlue,
                            modifier = Modifier.size(24.dp)
                        )

                        if (totalSavedCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(LiveBufferRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$totalSavedCount",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Camera Source Picker Modal Bottom Sheet
        if (showCameraPickerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showCameraPickerSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = SurfaceDark,
                contentColor = TextPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SELECIONAR CÂMERA",
                            color = PrimaryBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Escolha qual lente do seu dispositivo deseja usar para gravar o buffer contínuo dos lances:",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    CameraSource.values().forEach { source ->
                        val isSelected = selectedCameraSource == source
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) SurfaceVariantDark else DarkBg)
                                .border(
                                    1.dp,
                                    if (isSelected) PrimaryBlue else SurfaceVariantDark,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    onSelectCameraSource(source)
                                    showCameraPickerSheet = false
                                }
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when (source) {
                                            CameraSource.BACK_MAIN -> Icons.Default.CameraRear
                                            CameraSource.FRONT_SELFIE -> Icons.Default.PhotoCamera
                                            CameraSource.BACK_WIDE -> Icons.Default.Cameraswitch
                                        },
                                        contentDescription = null,
                                        tint = if (isSelected) PrimaryBlue else TextSecondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = source.label,
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Text(
                                            text = when (source) {
                                                CameraSource.BACK_MAIN -> "Lente traseira padrão para visão nítida da quadra"
                                                CameraSource.FRONT_SELFIE -> "Câmera frontal para gravação e reação ao vivo"
                                                CameraSource.BACK_WIDE -> "Campo de visão amplo para pegar toda a arena"
                                            },
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selecionado",
                                        tint = PrimaryBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Camera / Screen Flash Pulse Overlay
        if (isTriggeringFlashSignal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .testTag("flash_signal_overlay")
            )
        }

        // Saving Replay Progress Dialog Overlay
        if (isSavingReplay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .testTag("saving_replay_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = SurfaceDark,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(28.dp)
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryBlue,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "⚡ PROCESSANDO REPLAY...",
                            color = PrimaryBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Recortando e salvando os últimos ${bufferDurationSec}s na galeria!",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated Sports Court Graphic Canvas when in camera simulation / fallback mode.
 */
@Composable
fun CourtSimulationCanvas(selectedCourt: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "courtSim")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "animOffset"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Dark stadium turf color
        drawRect(color = Color(0xFF1A1C1E))

        // Court boundary lines
        val linePaintColor = Color(0xFFD2E4FF).copy(alpha = 0.3f)
        val strokeW = 3.dp.toPx()

        drawRect(
            color = linePaintColor,
            topLeft = Offset(40f, 120f),
            size = androidx.compose.ui.geometry.Size(w - 80f, h - 240f),
            style = Stroke(width = strokeW)
        )

        // Center line & circle
        drawLine(
            color = linePaintColor,
            start = Offset(40f, h / 2),
            end = Offset(w - 40f, h / 2),
            strokeWidth = strokeW
        )

        drawCircle(
            color = linePaintColor,
            center = Offset(w / 2, h / 2),
            radius = 120f,
            style = Stroke(width = strokeW)
        )

        // Bouncing match ball simulation graphic
        val ballX = w / 2 + Math.sin(animOffset * 0.05).toFloat() * 180f
        val ballY = h / 2 + Math.cos(animOffset * 0.05).toFloat() * 120f

        drawCircle(
            color = Color(0xFFD2E4FF),
            center = Offset(ballX, ballY),
            radius = 20f
        )
    }
}
