package com.example.ui

import android.app.Application
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ReplayClip
import com.example.data.ReplayRepository
import com.example.media.ReplayBufferEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CameraSource(val label: String, val lensFacing: Int, val iconName: String) {
    BACK_MAIN("Traseira Principal", CameraSelector.LENS_FACING_BACK, "camera_rear"),
    FRONT_SELFIE("Frontal (Selfie)", CameraSelector.LENS_FACING_FRONT, "camera_front"),
    BACK_WIDE("Traseira Ultra-Wide (0.5x)", CameraSelector.LENS_FACING_BACK, "camera_wide")
}

class ReplayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ReplayRepository
    val bufferEngine: ReplayBufferEngine

    private val _selectedCourtType = MutableStateFlow("Futebol")
    val selectedCourtType: StateFlow<String> = _selectedCourtType.asStateFlow()

    private val _bufferDurationSeconds = MutableStateFlow(35)
    val bufferDurationSeconds: StateFlow<Int> = _bufferDurationSeconds.asStateFlow()

    private val _isSavingReplay = MutableStateFlow(false)
    val isSavingReplay: StateFlow<Boolean> = _isSavingReplay.asStateFlow()

    private val _lastSavedClip = MutableStateFlow<ReplayClip?>(null)
    val lastSavedClip: StateFlow<ReplayClip?> = _lastSavedClip.asStateFlow()

    private val _selectedClipForPlayback = MutableStateFlow<ReplayClip?>(null)
    val selectedClipForPlayback: StateFlow<ReplayClip?> = _selectedClipForPlayback.asStateFlow()

    private val _isFlashEnabled = MutableStateFlow(false)
    val isFlashEnabled: StateFlow<Boolean> = _isFlashEnabled.asStateFlow()

    private val _selectedCameraSource = MutableStateFlow(CameraSource.BACK_MAIN)
    val selectedCameraSource: StateFlow<CameraSource> = _selectedCameraSource.asStateFlow()

    private val _isAudioEnabled = MutableStateFlow(true)
    val isAudioEnabled: StateFlow<Boolean> = _isAudioEnabled.asStateFlow()

    private val _isRecordingBuffer = MutableStateFlow(true)
    val isRecordingBuffer: StateFlow<Boolean> = _isRecordingBuffer.asStateFlow()

    private val _remoteSaveTriggerSignal = MutableStateFlow(0)
    val remoteSaveTriggerSignal: StateFlow<Int> = _remoteSaveTriggerSignal.asStateFlow()

    val clips: StateFlow<List<ReplayClip>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ReplayRepository(database.replayClipDao())
        bufferEngine = ReplayBufferEngine(application)

        clips = _selectedCourtType
            .flatMapLatest { court ->
                repository.getClipsByCourt(if (court == "Todos") "Geral" else court)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        // Automatically start continuous buffer recording
        bufferEngine.startBufferRecording(enableAudio = _isAudioEnabled.value)
    }

    fun setCourtType(court: String) {
        _selectedCourtType.value = court
    }

    fun setBufferDuration(seconds: Int) {
        _bufferDurationSeconds.value = seconds
        bufferEngine.setBufferDuration(seconds)
    }

    fun toggleBufferRecording() {
        if (_isRecordingBuffer.value) {
            bufferEngine.stopBufferRecording()
            _isRecordingBuffer.value = false
        } else {
            bufferEngine.startBufferRecording(enableAudio = _isAudioEnabled.value)
            _isRecordingBuffer.value = true
        }
    }

    fun toggleFlash() {
        _isFlashEnabled.value = !_isFlashEnabled.value
    }

    fun setCameraSource(source: CameraSource) {
        _selectedCameraSource.value = source
    }

    fun toggleCamera() {
        _selectedCameraSource.value = when (_selectedCameraSource.value) {
            CameraSource.BACK_MAIN -> CameraSource.FRONT_SELFIE
            CameraSource.FRONT_SELFIE -> CameraSource.BACK_WIDE
            CameraSource.BACK_WIDE -> CameraSource.BACK_MAIN
        }
    }

    fun toggleAudio() {
        val newState = !_isAudioEnabled.value
        _isAudioEnabled.value = newState
        if (_isRecordingBuffer.value) {
            bufferEngine.stopBufferRecording()
            bufferEngine.startBufferRecording(enableAudio = newState)
        }
    }

    fun triggerRemoteSaveSignal() {
        _remoteSaveTriggerSignal.value += 1
    }

    fun save35sInstantReplay(customTag: String = "Golaço") {
        if (_isSavingReplay.value) return

        viewModelScope.launch {
            _isSavingReplay.value = true
            try {
                val duration = _bufferDurationSeconds.value
                val court = _selectedCourtType.value

                val result = bufferEngine.saveInstantReplay(
                    courtType = court,
                    requestedDurationSec = duration
                )

                val newClip = ReplayClip(
                    title = "Lance $court ${System.currentTimeMillis() % 1000}",
                    tag = customTag,
                    durationSeconds = duration,
                    filePath = result.localFile.absolutePath,
                    mediaStoreUri = result.galleryUriOrPath,
                    thumbnailPath = result.thumbnailPath,
                    timestamp = System.currentTimeMillis(),
                    courtType = court
                )

                val savedId = repository.saveClip(newClip)
                val fullSavedClip = newClip.copy(id = savedId)

                _lastSavedClip.value = fullSavedClip
                Log.d("ReplayViewModel", "Saved 35s replay clip #${savedId} to gallery and Room db.")
            } catch (e: Exception) {
                Log.e("ReplayViewModel", "Error saving replay", e)
            } finally {
                _isSavingReplay.value = false
            }
        }
    }

    fun updateClipInfo(clip: ReplayClip, newTitle: String, newTag: String) {
        viewModelScope.launch {
            repository.updateClip(clip.copy(title = newTitle, tag = newTag))
        }
    }

    fun deleteClip(clip: ReplayClip) {
        viewModelScope.launch {
            repository.deleteClip(clip)
            if (_selectedClipForPlayback.value?.id == clip.id) {
                _selectedClipForPlayback.value = null
            }
            if (_lastSavedClip.value?.id == clip.id) {
                _lastSavedClip.value = null
            }
        }
    }

    fun dismissQuickShareModal() {
        _lastSavedClip.value = null
    }

    fun setSelectedClipForPlayback(clip: ReplayClip?) {
        _selectedClipForPlayback.value = clip
    }
}
