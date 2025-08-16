package com.codewithkael.webrtcdatachannelty.repository

import com.codewithkael.webrtcdatachannelty.socket.SocketClient
import com.codewithkael.webrtcdatachannelty.utils.DataConverter
import com.codewithkael.webrtcdatachannelty.utils.DataModel
import com.codewithkael.webrtcdatachannelty.utils.DataModelType.*
import com.codewithkael.webrtcdatachannelty.utils.FileMetaDataType
import com.codewithkael.webrtcdatachannelty.webrtc.MyPeerObserver
import com.codewithkael.webrtcdatachannelty.webrtc.WebrtcClient
import com.google.gson.Gson
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import javax.inject.Inject

class MainRepository @Inject constructor(
    private val socketClient: SocketClient,
    private val webrtcClient: WebrtcClient,
    private val gson: Gson
) : SocketClient.Listener, WebrtcClient.Listener, WebrtcClient.ReceiverListener {
    private lateinit var username: String
    private lateinit var target: String
    private val dataConverter = DataConverter()  // Create instance

    var listener: Listener? = null

    fun init(username: String) {
        this.username = username
        initSocket()
        initWebrtcClient()

    }

    private fun initSocket() {
        socketClient.listener = this
        socketClient.init(username)

    }

    private fun initWebrtcClient() {
        webrtcClient.listener = this
        webrtcClient.receiverListener = this
        webrtcClient.initializeWebrtcClient(username, object : MyPeerObserver() {
            override fun onIceCandidate(p0: IceCandidate?) {
                super.onIceCandidate(p0)
                p0?.let {
                    webrtcClient.sendIceCandidate(it, target)
                }
            }

            override fun onDataChannel(p0: DataChannel?) {
                super.onDataChannel(p0)
                // Answerer receives the DataChannel from offerer
                webrtcClient.setDataChannel(p0)
                listener?.onDataChannelReceived()

            }

        })
    }

    fun sendStartConnection(target: String) {
        this.target = target
        socketClient.sendMessageToSocket(
            DataModel(
                type = StartConnection,
                username = username,
                target = target,
                data = null
            )
        )
    }

    fun startCall(target: String) {
        // Offerer creates the DataChannel
        webrtcClient.createDataChannel()
        webrtcClient.call(target)
    }

    fun sendTextToDataChannel(text:String){
        sendBufferToDataChannel(dataConverter.convertToBuffer(FileMetaDataType.META_DATA_TEXT,text))
        sendBufferToDataChannel(dataConverter.convertToBuffer(FileMetaDataType.TEXT,text))
    }

    fun sendImageToChannel(path:String){
        sendBufferToDataChannel(dataConverter.convertToBuffer(FileMetaDataType.META_DATA_IMAGE,path))
        sendBufferToDataChannel(dataConverter.convertToBuffer(FileMetaDataType.IMAGE,path))
    }

    private fun sendBufferToDataChannel(buffer: DataChannel.Buffer){
        val dataChannel = webrtcClient.getDataChannel()
        if (dataChannel?.state() == DataChannel.State.OPEN) {
            dataChannel.send(buffer)
        } else {
            Log.e("MainRepository", "DataChannel not ready. State: ${dataChannel?.state()}")
        }
    }

    override fun onNewMessageReceived(model: DataModel) {
        try {
            when (model.type) {
                StartConnection -> {
                    this.target = model.username ?: return
                    listener?.onConnectionRequestReceived(model.username)
                }
                Offer -> {
                    val sdpData = model.data?.toString() ?: return
                    webrtcClient.onRemoteSessionReceived(
                        SessionDescription(
                            SessionDescription.Type.OFFER, sdpData
                        )
                    )
                    this.target = model.username ?: return
                    webrtcClient.answer(target)
                }
                Answer -> {
                    val sdpData = model.data?.toString() ?: return
                    webrtcClient.onRemoteSessionReceived(
                        SessionDescription(
                            SessionDescription.Type.ANSWER, sdpData
                        )
                    )
                }
                IceCandidates -> {
                    val candidate = try {
                        gson.fromJson(model.data.toString(), IceCandidate::class.java)
                    } catch (e: Exception) {
                        Log.e("MainRepository", "Failed to parse ICE candidate: ${e.message}")
                        null
                    }
                    candidate?.let {
                        webrtcClient.addIceCandidate(it)
                    }
                }
                else -> {
                    Log.w("MainRepository", "Unknown message type: ${model.type}")
                }
            }
        } catch (e: Exception) {
            Log.e("MainRepository", "Error processing message: ${e.message}")
        }
    }

    override fun onTransferEventToSocket(data: DataModel) {
        socketClient.sendMessageToSocket(data)
    }

    override fun onDataReceived(it: DataChannel.Buffer) {
        val model = dataConverter.convertToModel(it)
        model?.let { result ->
            listener?.onDataReceivedFromChannel(result)
        }
    }

    fun cleanup() {
        socketClient.onDestroy()
        // Note: WebRTC cleanup is handled automatically when PeerConnection is garbage collected
    }

    interface Listener {
        fun onConnectionRequestReceived(target: String)
        fun onDataChannelReceived()
        fun onDataReceivedFromChannel(data: Pair<String, Any>)
    }
}