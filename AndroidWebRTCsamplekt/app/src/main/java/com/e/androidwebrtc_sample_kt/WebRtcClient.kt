package com.e.androidwebrtc_sample_kt

import android.app.Application
import android.content.Context
import android.util.Log

import com.github.nkzawa.emitter.Emitter
import com.github.nkzawa.socketio.client.IO
import com.github.nkzawa.socketio.client.Socket
import org.json.JSONArray

import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import java.lang.IllegalStateException

import java.net.URISyntaxException
import java.util.HashMap
import java.util.LinkedList

class WebRtcClient(
    url: String,
    private val app: Application,
    private val eglContext: EglBase.Context,
    private val webrtcListener: RtcListener
) {

    private val endPoints = BooleanArray(MAX_PEER)
    private val factory: PeerConnectionFactory
    private val peers = HashMap<String, Peer>()
    private val iceServers = LinkedList<PeerConnection.IceServer>()
    private val pcConstraints = MediaConstraints()
    private var localMS: MediaStream? = null
    private var videoSource: VideoSource? = null
    private var socket: Socket? = null
    private val vc by lazy { getVideoCapturer() }

    init {
        //初始化 PeerConnectionFactory 配置
        PeerConnectionFactory.initialize(
            PeerConnectionFactory
                .InitializationOptions
                .builder(app)
                .createInitializationOptions()
        )

        //初始化视频编码/解码信息
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglContext)
            )
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglContext, true, true
                )
            )
            .createPeerConnectionFactory()

        // 初始化 Socket 通信
        val messageHandler = MessageHandler()

        try {
            socket = IO.socket(url)
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }

        socket?.on("id", messageHandler.onId)
        socket?.on("message", messageHandler.onMessage)
        socket?.on("ids", messageHandler.onIdsChanged)
        socket?.connect()

        //初始化 ICE 服务器创建 PC 时使用
        iceServers.add(PeerConnection.IceServer("stun:23.21.150.121"))
        iceServers.add(PeerConnection.IceServer("stun:stun.l.google.com:19302"))

        //初始化本地的 MediaConstraints 创建 PC 时使用，是流媒体的配置信息
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        pcConstraints.optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
    }


    fun refreshIds() {
        socket?.emit("refreshids", null)
    }

    fun callByClientId(clientId: String) {
        sendMessage(clientId, "init", JSONObject())
    }


    /**
     * Send a message through the signaling server
     *
     * @param to      id of recipient
     * @param type    type of message
     * @param payload payload of message
     * @throws JSONException
     */
    @Throws(JSONException::class)
    private fun sendMessage(to: String, type: String, payload: JSONObject) {
        val message = JSONObject()
        message.put("to", to)
        message.put("type", type)
        message.put("payload", payload)
        socket?.emit("message", message)
    }

    private inner class MessageHandler {

        val onMessage = Emitter.Listener { args ->
            val data = args[0] as JSONObject
            try {
                val from = data.getString("from")
                val type = data.getString("type")
                var payload: JSONObject? = null
                if (type != "init") {
                    payload = data.getJSONObject("payload")
                }
                //用于检查是否 PC 是否已存在已经是否达到最大的2个 PC 的限制
                if (!peers.containsKey(from)) {
                    val endPoint = findEndPoint()
                    if (endPoint == MAX_PEER) return@Listener
                    else addPeer(from, endPoint)
                }
                //根据不同的指令类型和数据响应相应步骤的方法
                when (type) {
                    "init" -> createOffer(from)
                    "offer" -> createAnswer(from, payload)
                    "answer" -> setRemoteSdp(from, payload)
                    "candidate" -> addIceCandidate(from, payload)
                }

            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        val onId = Emitter.Listener { args ->
            val id = args[0] as String
            webrtcListener.onCallReady(id)
        }

        val onIdsChanged = Emitter.Listener { args ->
            Log.d(TAG, args.toString())
            val ids = args[0] as JSONArray

            webrtcListener.onOnlineIdsChanged(ids)
        }
    }


    private fun createOffer(peerId: String) {
        Log.d(TAG, "CreateOfferCommand")
        val peer = peers[peerId]
        peer?.pc?.createOffer(peer, pcConstraints)
    }

    private fun createAnswer(peerId: String, payload: JSONObject?) {
        Log.d(TAG, "CreateAnswerCommand")
        val peer = peers[peerId]
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(payload?.getString("type")),
            payload?.getString("sdp")
        )
        peer?.pc?.setRemoteDescription(peer, sdp)
        peer?.pc?.createAnswer(peer, pcConstraints)
    }

    private fun setRemoteSdp(peerId: String, payload: JSONObject?) {
        Log.d(TAG, "SetRemoteSDPCommand")
        val peer = peers[peerId]
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(payload?.getString("type")),
            payload?.getString("sdp")
        )
        peer?.pc?.setRemoteDescription(peer, sdp)
    }

    private fun addIceCandidate(peerId: String, payload: JSONObject?) {
        Log.d(TAG, "AddIceCandidateCommand")
        val pc = peers[peerId]!!.pc
        if (pc!!.remoteDescription != null) {
            val candidate = IceCandidate(
                payload!!.getString("id"),
                payload.getInt("label"),
                payload.getString("candidate")
            )
            pc.addIceCandidate(candidate)
        }
    }


    private inner class Peer(val id: String, val endPoint: Int) : SdpObserver,
        PeerConnection.Observer {

        val pc: PeerConnection?


        init {
            Log.d(TAG, "new Peer: $id $endPoint")
            this.pc = factory.createPeerConnection(iceServers, pcConstraints, this)
            pc?.addStream(localMS!!) //, new MediaConstraints()
            webrtcListener.onStatusChanged("CONNECTING")
        }

        override fun onCreateSuccess(sdp: SessionDescription) {
            // TODO: modify sdp to use pcParams prefered codecs
            try {
                val payload = JSONObject()
                payload.put("type", sdp.type.canonicalForm())
                payload.put("sdp", sdp.description)
                sendMessage(id, sdp.type.canonicalForm(), payload)
                pc!!.setLocalDescription(this@Peer, sdp)
            } catch (e: JSONException) {
                e.printStackTrace()
            }

        }

        override fun onSetSuccess() {}

        override fun onCreateFailure(s: String) {}

        override fun onSetFailure(s: String) {}

        override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}

        override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
            webrtcListener.onStatusChanged(iceConnectionState.name)
            Log.d(TAG, "onIceConnectionChange ${iceConnectionState.name}")
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                removePeer(id)
            }
        }

        override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {}

        override fun onIceCandidate(candidate: IceCandidate) {
            try {
                val payload = JSONObject()
                payload.put("label", candidate.sdpMLineIndex)
                payload.put("id", candidate.sdpMid)
                payload.put("candidate", candidate.sdp)
                sendMessage(id, "candidate", payload)
            } catch (e: JSONException) {
                e.printStackTrace()
            }

        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        }

        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        }

        override fun onAddStream(mediaStream: MediaStream) {
            Log.d(TAG, "onAddStream " + mediaStream.id)
            // remote streams are displayed from 1 to MAX_PEER (0 is localStream)
            webrtcListener.onAddRemoteStream(mediaStream, endPoint + 1)
        }

        override fun onRemoveStream(mediaStream: MediaStream) {
            Log.d(TAG, "onRemoveStream " + mediaStream.id)
            removePeer(id)
        }

        override fun onDataChannel(dataChannel: DataChannel) {}

        override fun onRenegotiationNeeded() {

        }
    }

    private fun addPeer(id: String, endPoint: Int): Peer {
        val peer = Peer(id, endPoint)
        peers[id] = peer

        endPoints[endPoint] = true
        return peer
    }

    private fun removePeer(id: String) {
        val peer = peers[id]
        webrtcListener.onRemoveRemoteStream(peer!!.endPoint)
        peer.pc!!.close()
        peers.remove(peer.id)
        endPoints[peer.endPoint] = false
    }

    /**
     * Call this method in Activity.onPause()
     */
    fun onPause() {
        videoSource?.capturerObserver?.onCapturerStopped()
    }

    /**
     * Call this method in Activity.onResume()
     */
    fun onResume() {
        videoSource?.capturerObserver?.onCapturerStarted(true)
    }

    /**
     * Call this method in Activity.onDestroy()
     */
    fun onDestroy() {
        for (peer in peers.values) {
            peer.pc?.dispose()
        }
        videoSource?.dispose()
        factory.dispose()
        socket?.disconnect()
        socket?.close()
    }

    private fun findEndPoint(): Int {
        for (i in 0 until MAX_PEER)
            if (!endPoints[i]) return i
        return MAX_PEER
    }

    /**
     * Start the socket.
     *
     *
     * Set up the local stream and notify the signaling server.
     * Call this method after onCallReady.
     *
     * @param name socket name
     */

    private fun getVideoCapturer() =
        Camera2Enumerator(app).run {
            deviceNames.find {
                isFrontFacing(it)
            }?.let {
                createCapturer(it, null)
            } ?: throw IllegalStateException()
        }

    fun startLocalCamera(name: String, context: Context) {
        //init local media stream
        val localVideoSource = factory.createVideoSource(false)
        val surfaceTextureHelper =
            SurfaceTextureHelper.create(
                Thread.currentThread().name, eglContext
            )
        (vc as VideoCapturer).initialize(
            surfaceTextureHelper,
            context,
            localVideoSource.capturerObserver
        )
        vc.startCapture(320, 240, 60)
        localMS = factory.createLocalMediaStream("LOCALMEDIASTREAM")
        localMS?.addTrack(factory.createVideoTrack("LOCALMEDIASTREAM", localVideoSource))
        webrtcListener.onLocalStream(localMS!!)

        try {
            val message = JSONObject()
            message.put("name", name)
            socket?.emit("readyToStream", message)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    /**
     * Implement this interface to be notified of events.
     */
    interface RtcListener {
        fun onCallReady(callId: String)

        fun onStatusChanged(newStatus: String)

        fun onLocalStream(localStream: MediaStream)

        fun onAddRemoteStream(remoteStream: MediaStream, endPoint: Int)

        fun onRemoveRemoteStream(endPoint: Int)

        fun onOnlineIdsChanged(jsonArray: JSONArray) {}
    }

    companion object {
        private val TAG = WebRtcClient::class.java.canonicalName
        private const val MAX_PEER = 2
    }
}
