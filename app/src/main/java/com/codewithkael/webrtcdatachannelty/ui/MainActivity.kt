package com.codewithkael.webrtcdatachannelty.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
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
                    this.imagePathToSend = imagePath
                    Glide.with(this).load(imagePath).into(views.sendingImageView)
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
        try {
            // Create a unique filename with timestamp
            val timestamp = System.currentTimeMillis()
            val filename = "WebRTC_Image_$timestamp.jpg"
            
            // Save to Pictures directory
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            if (!picturesDir.exists()) {
                picturesDir.mkdirs()
            }
            
            val imageFile = File(picturesDir, filename)
            
            // Create output stream and compress bitmap to JPEG
            val outputStream = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
            outputStream.close()
            
            // Show success message with file path
            val message = "Image saved to Pictures folder: $filename"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            
            // Log for debugging
            android.util.Log.d("MainActivity", "Image saved successfully: ${imageFile.absolutePath}")
            
        } catch (e: Exception) {
            val errorMessage = "Failed to save image: ${e.message}"
            Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
            android.util.Log.e("MainActivity", errorMessage, e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup WebSocket and WebRTC connections
        mainRepository.cleanup()
    }
}