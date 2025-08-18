package com.codewithkael.webrtcdatachannelty.ui

import android.content.pm.PackageManager
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
    private var imagePathToSend:String? = null

    @Inject
    lateinit var mainRepository: MainRepository

    private lateinit var views: ActivityMainBinding

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if(isGranted){
                //open gallery
                pickImageLauncher.launch("image/*")
            }else{
                Toast.makeText(this, "we need storage permission", Toast.LENGTH_SHORT).show()
            }

        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()){uri ->
            //convert the uri to real path
            uri?.let {
                val imagePath : String? = it.getFilePath(this)
                if (imagePath!=null){
                    // Immediately send after selection and return to sharing screen
                    mainRepository.sendImageToChannel(imagePath)
                    this.imagePathToSend = null
                    views.sendingImageView.setImageResource(R.drawable.ic_add_photo)
                    Toast.makeText(this, "Image sent", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "image was not found", Toast.LENGTH_SHORT).show()
                }
            }

        }

    private fun openGallery(){
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        if (ContextCompat.checkSelfPermission(
                this,permission
        ) != PackageManager.PERMISSION_GRANTED ) {
            requestPermissionLauncher.launch(permission)
        } else {
            pickImageLauncher.launch("image/*")
        }
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
                mainRepository.sendStartConnection(targetEt.text.toString())
            }
            sendImageButton.setOnClickListener {
                if (imagePathToSend.isNullOrEmpty()){
                    Toast.makeText(this@MainActivity, "select image first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                mainRepository.sendImageToChannel(imagePathToSend!!)
                imagePathToSend = null
                sendingImageView.setImageResource(R.drawable.ic_add_photo)

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
            sendingImageView.setOnClickListener {
                openGallery()
            }
        }

    }

    override fun onConnectionRequestReceived(target: String) {
        runOnUiThread {
            views.apply {
                requestLayout.isVisible = false
                notificationLayout.isVisible = true
                notificationTitle.text = "$target is trying to connect."
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
            }
        }
    }

    override fun onDataReceivedFromChannel(data: Pair<String, Any>) {
        runOnUiThread {
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
                }
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup WebSocket and WebRTC connections
        mainRepository.cleanup()
    }
}