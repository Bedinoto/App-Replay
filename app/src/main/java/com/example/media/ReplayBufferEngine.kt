package com.example.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class ReplayBufferEngine(private val context: Context) {
    private val TAG = "ReplayBufferEngine"

    data class VideoSegment(
        val file: File,
        val startTimeMs: Long,
        var endTimeMs: Long = 0L
    )

    data class SavedReplayResult(
        val galleryUriOrPath: String,
        val localFile: File,
        val thumbnailPath: String?,
        val durationSeconds: Int
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val segments = mutableListOf<VideoSegment>()

    private var activeRecording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private val _isRecordingBuffer = MutableStateFlow(false)
    val isRecordingBuffer: StateFlow<Boolean> = _isRecordingBuffer.asStateFlow()

    private val _bufferDurationSeconds = MutableStateFlow(35)
    val bufferDurationSeconds: StateFlow<Int> = _bufferDurationSeconds.asStateFlow()

    private var activeSegmentFile: File? = null
    private var activeSegmentStartTimeMs: Long = 0L

    private var pendingFinalizeContinuation: CancellableContinuation<VideoSegment?>? = null

    fun setBufferDuration(seconds: Int) {
        _bufferDurationSeconds.value = seconds
    }

    /**
     * Sets or updates the active VideoCapture instance safely.
     */
    fun setVideoCapture(vCapture: VideoCapture<Recorder>?, enableAudio: Boolean = false) {
        if (this.videoCapture != vCapture) {
            try {
                activeRecording?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping active recording during camera switch", e)
            }
            activeRecording = null
            this.videoCapture = vCapture

            if (vCapture != null && _isRecordingBuffer.value) {
                startNewSegment(enableAudio)
            }
        }
    }

    /**
     * Prepares and binds CameraX VideoCapture.
     */
    fun setupCamera(
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit
    ) {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            Log.w(TAG, "Camera permission not granted yet. Skipping setupCamera.")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()

                val vCapture = VideoCapture.withOutput(recorder)
                this.videoCapture = vCapture
                onVideoCaptureReady(vCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Starts continuous rolling buffer recording.
     */
    fun startBufferRecording(enableAudio: Boolean = false) {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            Log.w(TAG, "Camera permission not granted. Cannot start buffer recording.")
            return
        }

        _isRecordingBuffer.value = true
        startNewSegment(enableAudio)
    }

    /**
     * Stops buffer recording.
     */
    fun stopBufferRecording() {
        _isRecordingBuffer.value = false
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping buffer recording", e)
        }
        activeRecording = null
        cleanUpOldSegments(0)
    }

    private fun startNewSegment(enableAudio: Boolean = false) {
        val vCapture = videoCapture
        if (vCapture == null) {
            Log.w(TAG, "VideoCapture not ready. Simulation mode active.")
            return
        }

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            Log.w(TAG, "Camera permission missing, skipping segment start.")
            return
        }

        val cacheDir = File(context.cacheDir, "replay_buffer")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val segmentFile = File(cacheDir, "seg_${System.currentTimeMillis()}.mp4")
        activeSegmentFile = segmentFile
        activeSegmentStartTimeMs = System.currentTimeMillis()

        val fileOutputOptions = FileOutputOptions.Builder(segmentFile).build()

        try {
            var recordingBuilder = vCapture.output.prepareRecording(context, fileOutputOptions)
            val hasAudioPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (enableAudio && hasAudioPermission) {
                try {
                    recordingBuilder = recordingBuilder.withAudioEnabled()
                } catch (e: SecurityException) {
                    Log.w(TAG, "Audio permission not granted", e)
                }
            }

            activeRecording = recordingBuilder.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    val endTime = System.currentTimeMillis()
                    val seg = if (!event.hasError() && segmentFile.exists() && segmentFile.length() > 0) {
                        VideoSegment(segmentFile, activeSegmentStartTimeMs, endTime)
                    } else null

                    if (seg != null) {
                        synchronized(segments) {
                            segments.add(seg)
                        }
                    }

                    val cont = pendingFinalizeContinuation
                    pendingFinalizeContinuation = null
                    if (cont?.isActive == true) {
                        cont.resume(seg)
                    }

                    cleanUpOldSegments(bufferDurationSeconds.value * 1000L + 15000L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera recording segment", e)
        }
    }

    private suspend fun stopAndFlushActiveSegment(): VideoSegment? = withContext(Dispatchers.Main) {
        val recording = activeRecording
        val file = activeSegmentFile
        val startTime = activeSegmentStartTimeMs

        if (recording == null || file == null) {
            return@withContext null
        }

        suspendCancellableCoroutine { cont ->
            pendingFinalizeContinuation = cont
            try {
                recording.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping active recording", e)
                pendingFinalizeContinuation = null
                if (file.exists() && file.length() > 0) {
                    cont.resume(VideoSegment(file, startTime, System.currentTimeMillis()))
                } else {
                    cont.resume(null)
                }
            }
        }
    }

    private fun cleanUpOldSegments(keepMaxAgeMs: Long) {
        val now = System.currentTimeMillis()
        synchronized(segments) {
            val iterator = segments.iterator()
            while (iterator.hasNext()) {
                val seg = iterator.next()
                if (keepMaxAgeMs > 0 && (now - seg.endTimeMs) > keepMaxAgeMs) {
                    if (seg.file.exists()) {
                        seg.file.delete()
                    }
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Trims the last requestedDurationSec from buffered MP4 segments and saves to external gallery.
     */
    suspend fun saveInstantReplay(
        courtType: String,
        requestedDurationSec: Int
    ): SavedReplayResult = withContext(Dispatchers.IO) {
        // Flush active camera recording so CameraX finalizes the MP4 video on disk
        val flushedSeg = stopAndFlushActiveSegment()

        // Resume continuous buffer recording immediately for upcoming plays
        withContext(Dispatchers.Main) {
            if (_isRecordingBuffer.value) {
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                startNewSegment(enableAudio = hasAudioPermission)
            }
        }

        val allSegments = synchronized(segments) { segments.toList() }
        val candidateSegments = if (flushedSeg != null && !allSegments.contains(flushedSeg)) {
            allSegments + flushedSeg
        } else {
            allSegments
        }.filter { it.file.exists() && it.file.length() > 0L }

        val outputDir = File(context.filesDir, "replays")
        if (!outputDir.exists()) outputDir.mkdirs()

        val fileName = "QuadraReplay_${courtType}_${System.currentTimeMillis()}.mp4"
        val outputFile = File(outputDir, fileName)

        if (candidateSegments.isNotEmpty()) {
            if (candidateSegments.size == 1) {
                trimMp4File(candidateSegments[0].file, outputFile, requestedDurationSec)
            } else {
                mergeMp4Files(candidateSegments.map { it.file }, outputFile)
            }
        } else {
            Log.w(TAG, "No active camera recording segments available, creating fallback video.")
            createFallbackVideo(outputFile, requestedDurationSec)
        }

        val thumbnailPath = MediaStoreHelper.createThumbnail(context, outputFile.absolutePath)

        val galleryUri = MediaStoreHelper.saveVideoToGallery(
            context = context,
            videoFile = outputFile,
            title = "Lance $courtType ${System.currentTimeMillis() % 1000}"
        )

        SavedReplayResult(
            galleryUriOrPath = galleryUri ?: outputFile.absolutePath,
            localFile = outputFile,
            thumbnailPath = thumbnailPath,
            durationSeconds = requestedDurationSec
        )
    }

    private fun trimMp4File(inputFile: File, outputFile: File, durationSec: Int) {
        if (!inputFile.exists() || inputFile.length() == 0L) return

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            var videoTrackIndex = -1
            var videoDurationUs = 0L

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                }
            }

            val targetDurationUs = durationSec * 1_000_000L
            if (videoDurationUs <= targetDurationUs || videoDurationUs == 0L) {
                extractor.release()
                inputFile.copyTo(outputFile, overwrite = true)
                return
            }

            val startCutUs = videoDurationUs - targetDurationUs
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Preserve video orientation from source file
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(inputFile.absolutePath)
                val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val rotationDegrees = rotationStr?.toIntOrNull() ?: 0
                muxer.setOrientationHint(rotationDegrees)
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting rotation metadata for trim", e)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                }
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            trackMap.keys.forEach { trackIdx ->
                extractor.selectTrack(trackIdx)
                extractor.seekTo(startCutUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                var firstPtsUs = -1L
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    val pts = extractor.sampleTime
                    if (firstPtsUs < 0) {
                        firstPtsUs = pts
                    }

                    bufferInfo.presentationTimeUs = maxOf(0L, pts - firstPtsUs)
                    bufferInfo.flags = extractor.sampleFlags

                    val muxerTrack = trackMap[trackIdx]!!
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    extractor.advance()
                }
                extractor.unselectTrack(trackIdx)
            }

            extractor.release()
            muxer.stop()
            muxer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming MP4 file, copying full camera file instead", e)
            inputFile.copyTo(outputFile, overwrite = true)
        }
    }

    private fun mergeMp4Files(files: List<File>, outputFile: File) {
        val validFiles = files.filter { it.exists() && it.length() > 0L }
        if (validFiles.isEmpty()) return
        if (validFiles.size == 1) {
            validFiles[0].copyTo(outputFile, overwrite = true)
            return
        }

        try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Preserve video orientation from first source file
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(validFiles[0].absolutePath)
                val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val rotationDegrees = rotationStr?.toIntOrNull() ?: 0
                muxer.setOrientationHint(rotationDegrees)
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting rotation metadata for merge", e)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            val firstExtractor = MediaExtractor()
            firstExtractor.setDataSource(validFiles[0].absolutePath)

            var muxerVideoTrackIndex = -1
            var muxerAudioTrackIndex = -1

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    muxerVideoTrackIndex = muxer.addTrack(format)
                } else if (mime.startsWith("audio/")) {
                    muxerAudioTrackIndex = muxer.addTrack(format)
                }
            }
            firstExtractor.release()

            muxer.start()

            var ptsOffsetVideoUs = 0L
            var ptsOffsetAudioUs = 0L
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (file in validFiles) {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)

                var lastVideoPtsUs = 0L
                var lastAudioPtsUs = 0L

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")

                    if (!isVideo && !isAudio) continue
                    val muxerTrack = if (isVideo) muxerVideoTrackIndex else muxerAudioTrackIndex
                    if (muxerTrack < 0) continue

                    extractor.selectTrack(i)
                    val ptsOffsetUs = if (isVideo) ptsOffsetVideoUs else ptsOffsetAudioUs

                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) break

                        val samplePts = extractor.sampleTime
                        bufferInfo.presentationTimeUs = maxOf(0L, samplePts + ptsOffsetUs)
                        bufferInfo.flags = extractor.sampleFlags

                        if (isVideo) lastVideoPtsUs = maxOf(lastVideoPtsUs, samplePts)
                        if (isAudio) lastAudioPtsUs = maxOf(lastAudioPtsUs, samplePts)

                        muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(i)
                }

                ptsOffsetVideoUs += lastVideoPtsUs + 33_000L
                ptsOffsetAudioUs += lastAudioPtsUs + 33_000L
                extractor.release()
            }

            muxer.stop()
            muxer.release()
        } catch (e: Exception) {
            Log.e(TAG, "MediaMuxer merge failed, falling back to last segment copy", e)
            validFiles.last().copyTo(outputFile, overwrite = true)
        }
    }

    private fun createFallbackVideo(outputFile: File, durationSec: Int) {
        val width = 720
        val height = 1280
        val fps = 30
        val frameCount = fps * durationSec

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            for (i in 0 until frameCount) {
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(android.graphics.Color.rgb(26, 28, 30))

                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(210, 228, 255)
                    textSize = 48f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                canvas.drawText("⚡ QUADRA REPLAY", width / 2f, height / 2f - 30, paint)

                paint.textSize = 32f
                paint.color = android.graphics.Color.rgb(196, 198, 207)
                canvas.drawText("Lance Gravado (${i / fps}s / ${durationSec}s)", width / 2f, height / 2f + 40, paint)

                surface.unlockCanvasAndPost(canvas)

                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputBufferIndex >= 0) {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            codec.stop()
            codec.release()
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating fallback video", e)
        }
    }
}
