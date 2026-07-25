package com.example.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

object MediaStoreHelper {
    private const val TAG = "MediaStoreHelper"

    /**
     * Saves a video file into the Public Device Gallery (Movies/QuadraReplay).
     * Returns the Public MediaStore content URI string or file path string.
     */
    fun saveVideoToGallery(context: Context, videoFile: File, title: String): String? {
        val fileName = "QuadraReplay_${System.currentTimeMillis()}.mp4"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuadraReplay")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues)

                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { outputStream ->
                        FileInputStream(videoFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)

                    Log.d(TAG, "Saved video to MediaStore Q+: $itemUri")
                    itemUri.toString()
                } else {
                    fallbackSaveFile(context, videoFile, fileName)
                }
            } else {
                fallbackSaveFile(context, videoFile, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video to gallery", e)
            fallbackSaveFile(context, videoFile, fileName)
        }
    }

    private fun fallbackSaveFile(context: Context, videoFile: File, fileName: String): String {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val quadraDir = File(moviesDir, "QuadraReplay")
        if (!quadraDir.exists()) {
            quadraDir.mkdirs()
        }
        val targetFile = File(quadraDir, fileName)
        videoFile.copyTo(targetFile, overwrite = true)

        // Scan file so it appears in gallery apps
        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )

        Log.d(TAG, "Saved video via legacy method: ${targetFile.absolutePath}")
        return targetFile.absolutePath
    }

    /**
     * Extracts a thumbnail bitmap from a video file and saves it to app cache.
     */
    fun createThumbnail(context: Context, videoPathOrUri: String): String? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            if (videoPathOrUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(videoPathOrUri))
            } else {
                retriever.setDataSource(videoPathOrUri)
            }

            val bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()

            if (bitmap != null) {
                val thumbDir = File(context.cacheDir, "thumbnails")
                if (!thumbDir.exists()) thumbDir.mkdirs()

                val thumbFile = File(thumbDir, "thumb_${System.currentTimeMillis()}.png")
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                }
                bitmap.recycle()
                thumbFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail", e)
            null
        } finally {
            retriever?.release()
        }
    }

    /**
     * Creates an Intent to quickly share the 35s replay video via WhatsApp, Instagram, Telegram, etc.
     */
    fun getShareIntent(context: Context, videoPathOrUri: String, title: String): Intent {
        val uri: Uri = if (videoPathOrUri.startsWith("content://")) {
            Uri.parse(videoPathOrUri)
        } else {
            val file = File(videoPathOrUri)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "⚡ Replay de 35s - Quadra Replay")
            putExtra(Intent.EXTRA_TEXT, "Confira este lance incrível ($title) gravado no Quadra Replay! ⚽🎾🏐")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(shareIntent, "Compartilhar Replay de 35s")
    }
}
