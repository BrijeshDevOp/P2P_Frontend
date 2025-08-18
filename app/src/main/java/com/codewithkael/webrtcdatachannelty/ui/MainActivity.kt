package com.codewithkael.webrtcdatachannelty.ui

import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import android.widget.MediaController
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.constraintlayout.widget.ConstraintLayout
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.codewithkael.webrtcdatachannelty.R
import com.codewithkael.webrtcdatachannelty.databinding.ActivityMainBinding
import com.codewithkael.webrtcdatachannelty.repository.MainRepository
import com.codewithkael.webrtcdatachannelty.utils.DataConverter
import com.codewithkael.webrtcdatachannelty.utils.getFilePath
import dagger.hilt.android.AndroidEntryPoint
import org.webrtc.DataChannel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), MainRepository.Listener {

    private var username: String? = null
    private var pendingUri: Uri? = null
    private var pendingMime: String? = null
    private var pendingName: String? = null
    private var pendingBytes: ByteArray? = null
    private var peerName: String? = null

    @Inject
    lateinit var mainRepository: MainRepository

    private lateinit var views: ActivityMainBinding

    // Removed: file picking does not require runtime permission; keep old image permission for gallery access if needed

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()){ uri ->
            if (uri == null) return@registerForActivityResult
            pendingUri = uri
            pendingMime = contentResolver.getType(uri) ?: "application/octet-stream"
            pendingName = queryDisplayName(uri)
            try {
                contentResolver.openInputStream(uri)?.use { input -> pendingBytes = input.readBytes() }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
                pendingUri = null; pendingMime = null; pendingName = null; pendingBytes = null
                return@registerForActivityResult
            }
            // Preview only; do not send yet
            when {
                pendingMime?.startsWith("image/") == true -> {
                    Glide.with(this).load(pendingUri).into(views.sendingImageView)
                    views.sendingPreviewName.isVisible = false
                }
                pendingMime?.startsWith("video/") == true -> {
                    val thumb = extractVideoThumbnail(pendingUri!!)
                    if (thumb != null) Glide.with(this).load(thumb).into(views.sendingImageView)
                    views.sendingPreviewName.isVisible = true
                    views.sendingPreviewName.text = pendingName ?: "video"
                }
                else -> {
                    val iconRes = resolveFileIconRes(pendingMime, pendingName)
                    if (iconRes != null) views.sendingImageView.setImageResource(iconRes) else views.sendingImageView.setImageResource(R.drawable.ic_pick_file)
                    views.sendingPreviewName.isVisible = true
                    views.sendingPreviewName.text = pendingName ?: "file"
                }
            }
        }

    private fun openGallery(){
        // No runtime permission required for SAF picker
        pickFileLauncher.launch("*/*")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)
        init()
    }

    private fun init() {
        username = intent.getStringExtra("username")
        if (username.isNullOrEmpty()) {
            finish()
        }

        mainRepository.listener = this
        mainRepository.init(username!!)

        views.apply {
            requestBtn.setOnClickListener {
                if (targetEt.text.toString().isEmpty()) {
                    Toast.makeText(this@MainActivity, "hey, Fill up the target", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                peerName = targetEt.text.toString()
                mainRepository.sendStartConnection(targetEt.text.toString())
            }
            sendTextButton.setOnClickListener {
                if(sendingTextEditText.text.isEmpty()){
                    Toast.makeText(this@MainActivity, "fill send text editText", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }

                mainRepository.sendTextToDataChannel(sendingTextEditText.text.toString())
                sendingTextEditText.setText("")
            }
            sendingImageView.setOnClickListener { openGallery() }
            sendImageButton.setOnClickListener {
                // If we already have a pending file, send it; otherwise open picker
                val bytes = pendingBytes
                val mime = pendingMime
                val name = pendingName
                if (bytes != null && mime != null) {
                    when {
                        mime.startsWith("image/") -> {
                            val path = pendingUri?.getFilePath(this@MainActivity)
                            if (path != null) mainRepository.sendImageToChannel(path)
                            else mainRepository.sendBinary("IMAGE", bytes, mime, name)
                            Toast.makeText(this@MainActivity, "Image sent", Toast.LENGTH_SHORT).show()
                        }
                        mime.startsWith("video/") -> {
                            mainRepository.sendBinary("VIDEO", bytes, mime, name)
                            Toast.makeText(this@MainActivity, "Video sent", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            mainRepository.sendBinary("FILE", bytes, mime, name)
                            Toast.makeText(this@MainActivity, "File sent", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // Reset preview state
                    pendingUri = null; pendingMime = null; pendingName = null; pendingBytes = null
                    views.sendingImageView.setImageResource(R.drawable.ic_pick_file)
                    views.sendingPreviewName.isVisible = false
                } else {
                    openGallery()
                }
            }
        }

    }

    override fun onConnectionRequestReceived(target: String) {
        runOnUiThread {
            views.apply {
                requestLayout.isVisible = false
                notificationLayout.isVisible = true
                notificationTitle.text = "From ${target}"
                peerName = target
                notificationAcceptBtn.setOnClickListener {
                    mainRepository.startCall(target)
                    notificationLayout.isVisible = false
                }
                notificationDeclineBtn.setOnClickListener {
                    notificationLayout.isVisible = false
                    requestLayout.isVisible = true
                }
            }
        }
    }

    override fun onDataChannelReceived() {
        runOnUiThread {
            views.apply {
                requestLayout.isVisible = false
                receivedDataLayout.isVisible = true
                sendDataLayout.isVisible = true
                updateReceivedTitle()
            }
        }
    }

    override fun onDataReceivedFromChannel(data: Pair<String, Any>) {
        runOnUiThread {
            updateReceivedTitle()
            when(data.first){
                "TEXT"->{
                    views.receivedText.text = data.second.toString()
                }
                "IMAGE"->{
                    val bitmap = data.second as Bitmap
                    Glide.with(this).load(bitmap).into(
                        views.receivedImageView
                    )
                    // Automatically save the received image to device storage
                    saveImageToDevice(bitmap)
                    views.receivedImageView.isVisible = true
                    views.receivedVideoContainer.isVisible = false
                    views.receivedFileIcon.isVisible = false
                    views.receivedFileName.isVisible = false
                }
                "VIDEO"->{
                    val received = data.second as com.codewithkael.webrtcdatachannelty.utils.ReceivedFile
                    val uri = saveBytesToMediaStore(received.bytes, received.fileName ?: "video_${System.currentTimeMillis()}.mp4", received.mimeType ?: "video/mp4", isVideo = true)
                    if (uri != null) {
                        views.receivedVideoContainer.isVisible = true
                        views.receivedImageView.isVisible = false
                        views.receivedFileIcon.isVisible = false
                        views.receivedFileName.isVisible = false
                        val controller = MediaController(this)
                        controller.setAnchorView(views.receivedVideoView)
                        views.receivedVideoView.setMediaController(controller)
                        views.receivedVideoView.setVideoURI(uri)
                        // Adjust container ratio to match video
                        updateVideoContainerRatio(uri)
                        views.receivedVideoView.requestFocus()
                        views.receivedVideoView.start()
                    }
                }
                "FILE"->{
                    val received = data.second as com.codewithkael.webrtcdatachannelty.utils.ReceivedFile
                    val displayName = received.fileName ?: "file_${System.currentTimeMillis()}"
                    val savedUri = saveBytesToDownloads(received.bytes, displayName, received.mimeType)
                    views.receivedImageView.isVisible = false
                    views.receivedVideoContainer.isVisible = false
                    views.receivedFileIcon.isVisible = true
                    views.receivedFileName.isVisible = true
                    views.receivedFileName.text = displayName
                    val iconRes = resolveFileIconRes(received.mimeType, displayName)
                    if (iconRes != null) views.receivedFileIcon.setImageResource(iconRes)
                    if (savedUri != null) {
                        views.receivedFileIcon.setOnClickListener { openWithExternalApp(savedUri, received.mimeType ?: "application/octet-stream") }
                        views.receivedFileName.setOnClickListener { openWithExternalApp(savedUri, received.mimeType ?: "application/octet-stream") }
                    }
                }
            }
        }
    }

    private fun updateReceivedTitle() {
        val name = peerName ?: "Peer"
        val text = "From $name"
        val spannable = SpannableString(text)
        val start = text.indexOf(name)
        if (start >= 0) {
            val end = start + name.length
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        views.receivedDataTitle.text = spannable
    }

    private fun saveImageToDevice(bitmap: Bitmap) {
        val timestamp = System.currentTimeMillis()
        val filename = "WebRTC_Image_$timestamp.jpg"

        try {
            // Preferred: MediaStore so the image appears in the Gallery automatically
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri: Uri? = resolver.insert(collection, values)

            if (uri == null) {
                throw IllegalStateException("Failed to create MediaStore record")
            }

            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw IllegalStateException("Bitmap compression failed")
                }
                output.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Toast.makeText(this, "Image saved to Gallery", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            // Fallback: legacy file save (may not appear in gallery immediately on newer Android)
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                if (!picturesDir.exists()) picturesDir.mkdirs()
                val imageFile = File(picturesDir, filename)
                val outputStream = FileOutputStream(imageFile)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                outputStream.flush()
                outputStream.close()
                Toast.makeText(this, "Image saved: ${imageFile.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (inner: Exception) {
                Toast.makeText(this, "Failed to save image: ${inner.message}", Toast.LENGTH_SHORT).show()
                android.util.Log.e("MainActivity", "Save image failed", inner)
            }
        }
    }

    private fun saveBytesToMediaStore(bytes: ByteArray, filename: String, mimeType: String, isVideo: Boolean = false): Uri? {
        return try {
            val values = ContentValues().apply {
                if (isVideo) {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                } else {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
            }
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(collection, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBytesToDownloads(bytes: ByteArray, filename: String, mimeType: String?): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                return uri
            } else {
                // Legacy path for API < 29
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.exists()) downloads.mkdirs()
                val file = File(downloads, filename)
                FileOutputStream(file).use { it.write(bytes) }
                // Trigger media scan
                val uri = Uri.fromFile(file)
                sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                uri
            }
        } catch (e: Exception) { null }
    }

    private fun openWithExternalApp(uri: Uri, mimeType: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) name = it.getString(idx)
        }
        return name
    }

    private fun extractVideoThumbnail(uri: Uri): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val bitmap = retriever.getFrameAtTime(0)
            retriever.release()
            bitmap
        } catch (e: Exception) { null }
    }

    private fun updateVideoContainerRatio(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            retriever.release()
            if (w != null && h != null && w > 0 && h > 0) {
                val params = views.receivedVideoContainer.layoutParams as ConstraintLayout.LayoutParams
                params.dimensionRatio = "$w:$h"
                views.receivedVideoContainer.layoutParams = params
            }
        } catch (_: Exception) { }
    }

    private fun resolveFileIconRes(mimeType: String?, fileName: String?): Int? {
        val nameLower = (fileName ?: "").lowercase()
        val mime = (mimeType ?: "").lowercase()
        val key = when {
            nameLower.endsWith(".pdf") || mime.contains("pdf") -> "ic_pdf"
            nameLower.endsWith(".doc") || nameLower.endsWith(".docx") || mime.contains("word") -> "ic_docx"
            nameLower.endsWith(".ppt") || nameLower.endsWith(".pptx") || mime.contains("presentation") || mime.contains("powerpoint") -> "ic_ppt"
            nameLower.endsWith(".csv") || mime.contains("csv") -> "ic_csv"
            nameLower.endsWith(".xls") || nameLower.endsWith(".xlsx") || mime.contains("excel") || mime.contains("sheet") -> "ic_xlsx"
            nameLower.endsWith(".txt") || mime.startsWith("text/") -> "ic_txt"
            else -> null
        }
        return key?.let { resources.getIdentifier(it, "drawable", packageName).takeIf { id -> id != 0 } }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup WebSocket and WebRTC connections
        mainRepository.cleanup()
    }
}