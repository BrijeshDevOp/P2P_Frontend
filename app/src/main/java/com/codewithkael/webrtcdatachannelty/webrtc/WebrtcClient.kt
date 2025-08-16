package com.codewithkael.webrtcdatachannelty.webrtc

import android.content.Context
import com.codewithkael.webrtcdatachannelty.utils.DataModel
import com.codewithkael.webrtcdatachannelty.utils.DataModelType
import com.google.gson.Gson
import android.util.Log
import org.webrtc.*
import org.webrtc.PeerConnection.Observer
import javax.inject.Inject

class WebrtcClient @Inject constructor(
    context: Context, private val gson: Gson
) {

    private lateinit var username: String
    private lateinit var observer: Observer
    var listener: Listener? = null
    var receiverListener : ReceiverListener?=null

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null  // Add this to store the created DataChannel
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory by lazy { createPeerConnectionFactory() }
    private val mediaConstraint = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("RtpDataChannels", "true"))

    }

    private val dataChannelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(p0: Long) {
            Log.d("WebrtcClient", "DataChannel buffered amount: $p0")
        }

        override fun onStateChange() {
            Log.d("WebrtcClient", "DataChannel state changed to: ${dataChannel?.state()}")
        }

        override fun onMessage(p0: DataChannel.Buffer?) {
            p0?.let { receiverListener?.onDataReceived(it) }
        }

    }

    private val iceServer = listOf(
        // STUN servers for better connectivity
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // TURN server for NAT traversal
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    init {
        initPeerConnectionFactory(context)
    }

    fun initializeWebrtcClient(
        username: String, observer: Observer
    ) {
        this.username = username
        this.observer = observer
        peerConnection = createPeerConnection(observer)
        // Don't create DataChannel here - only offerer will create it
    }

    // Only call this for the offerer
    fun createDataChannel() {
        if (dataChannel == null) {
            val initDataChannel = DataChannel.Init()
            dataChannel = peerConnection?.createDataChannel("dataChannelLabel", initDataChannel)
            dataChannel?.registerObserver(dataChannelObserver)
        }
    }

    // Get the DataChannel (for offerer) or null (for answerer)
    fun getDataChannel(): DataChannel? = dataChannel

    // Set the DataChannel (for answerer when receiving from remote)
    fun setDataChannel(channel: DataChannel?) {
        dataChannel = channel
        dataChannel?.registerObserver(dataChannelObserver)
    }


    private fun initPeerConnectionFactory(application: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(application)
            .setEnableInternalTracer(true).setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder().setVideoDecoderFactory(
            DefaultVideoDecoderFactory(eglBaseContext)
        ).setVideoEncoderFactory(
            DefaultVideoEncoderFactory(
                eglBaseContext, true, true
            )
        ).setOptions(PeerConnectionFactory.Options().apply {
            disableEncryption = false
            disableNetworkMonitor = false
        }).createPeerConnectionFactory()
    }

    private fun createPeerConnection(observer: Observer): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(
            iceServer, observer
        )
    }

    fun call(target: String) {
        peerConnection?.createOffer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        listener?.onTransferEventToSocket(
                            DataModel(
                                type = DataModelType.Offer, username, target, desc?.description
                            )
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }


    fun answer(target: String) {
        peerConnection?.createAnswer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        listener?.onTransferEventToSocket(
                            DataModel(
                                type = DataModelType.Answer,
                                username = username,
                                target = target,
                                data = desc?.description
                            )
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    fun onRemoteSessionReceived(sessionDescription: SessionDescription){
        peerConnection?.setRemoteDescription(MySdpObserver(),sessionDescription)
    }

    fun addIceCandidate(iceCandidate: IceCandidate){
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun sendIceCandidate(candidate: IceCandidate,target: String){
        // Don't add local candidate to local PeerConnection - only send it to remote
        listener?.onTransferEventToSocket(
            DataModel(
                type = DataModelType.IceCandidates,
                username = username,
                target = target,
                data = gson.toJson(candidate)
            )
        )
    }

    interface Listener {
        fun onTransferEventToSocket(data: DataModel)
    }

    interface ReceiverListener{
        fun onDataReceived(it:DataChannel.Buffer)
    }
}
