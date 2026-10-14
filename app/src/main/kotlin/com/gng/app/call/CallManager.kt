package com.gng.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import android.os.Build
import com.gng.app.BuildConfig
import com.gng.app.data.CallRow
import com.gng.app.notify.Notifier
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.decodeJoinsAs
import io.github.jan.supabase.realtime.decodeLeavesAs
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "gng-call"

enum class CallPhase { Ringing, Calling, Connecting, Active, Ended }

data class Participant(
    val userId: String,
    val video: VideoTrack? = null,
    val micOn: Boolean = true,
    val camOn: Boolean = false,
    val connected: Boolean = false,
)

data class CallUi(
    val callId: String,
    val groupId: String,
    val title: String,
    val video: Boolean,
    val phase: CallPhase,
    val incomingFrom: String? = null,
    val participants: List<Participant> = emptyList(),
    val localVideo: VideoTrack? = null,
    val micOn: Boolean = true,
    val camOn: Boolean = true,
    val speakerOn: Boolean = false,
    val connectedAt: Long? = null,
    val endedReason: String? = null,
)

@Serializable
private data class Signal(
    val from: String,
    val to: String,
    val type: String, // hello | here | offer | answer | ice | bye
    val sdp: String? = null,
    val candidate: String? = null,
    val mid: String? = null,
    val index: Int? = null,
)

@Serializable
private data class PresenceState(
    @SerialName("user_id") val userId: String,
    val mic: Boolean = true,
    val cam: Boolean = false,
)

/**
 * Voice and video calls for DMs and gangs.
 *
 * Every pair of people in a call gets its own WebRTC connection (a mesh, fine up to ~6 people).
 * Audio and video are encrypted end to end by WebRTC (DTLS-SRTP). Supabase only carries the
 * handshake, over a private Realtime channel that only members of the conversation can join.
 */
class CallManager(
    private val context: Context,
    private val client: SupabaseClient,
    private val myId: () -> String?,
    private val notifier: Notifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<CallUi?>(null)
    val state: StateFlow<CallUi?> = _state.asStateFlow()

    val eglBase: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    private val peers = mutableMapOf<String, PeerConnection>()
    private val meeting = mutableSetOf<String>()
    private val restarted = mutableSetOf<String>()
    private val pendingIce = mutableMapOf<String, MutableList<IceCandidate>>()
    private val peerLock = Mutex()
    private var channel: RealtimeChannel? = null
    private var jobs = mutableListOf<Job>()
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var ringing: CallRow? = null
    private var ringingIsDm = false
    private val seenCalls = mutableSetOf<String>()
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }

    // --- starting, ringing, answering --------------------------------------------------

    /** Starts a call in [groupId] (or joins the one already going there). */
    suspend fun start(groupId: String, title: String, video: Boolean) {
        if (_state.value != null) return
        // Show "Calling…" straight away; the call row comes back from the server a moment later.
        _state.value = CallUi("", groupId, title, video, CallPhase.Calling, camOn = video, speakerOn = video)
        val call = try {
            client.postgrest.rpc("start_call", buildJsonObject {
                put("gid", groupId)
                put("p_video", video)
            }).decodeAs<CallRow>()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "start_call failed", e)
            dismiss("Couldn't start the call")
            return
        }
        join(call, title, outgoing = call.startedBy == myId())
    }

    /** Joins an existing call, e.g. from the "started a call" message in a gang chat. */
    suspend fun joinExisting(call: CallRow, title: String) {
        if (_state.value != null || call.endedAt != null) return
        join(call, title, outgoing = false)
    }

    /** Shows an incoming call. Returns false if we're already busy with it (or another call). */
    fun ring(call: CallRow, title: String, caller: String, isDm: Boolean): Boolean {
        // Push and realtime both report new calls; also never re-ring one you've declined.
        if (_state.value != null || !seenCalls.add(call.id)) return false
        ringing = call
        ringingIsDm = isDm
        _state.value = CallUi(call.id, call.groupId, title, call.video, CallPhase.Ringing, incomingFrom = caller, camOn = call.video)
        scope.launch {
            delay(Notifier.RING_TIMEOUT_MS)
            if (_state.value?.phase == CallPhase.Ringing && _state.value?.callId == call.id) dismiss("Missed call")
        }
        return true
    }

    suspend fun accept() {
        val call = ringing ?: return
        val title = _state.value?.title ?: ""
        notifier.cancelCall(call.id)
        ringing = null
        join(call, title, outgoing = false) // replaces the ringing state without passing through null

    }

    suspend fun decline(callId: String) {
        notifier.cancelCall(callId)
        val wasDm = ringing?.id == callId && ringingIsDm
        ringing = null
        if (_state.value?.callId == callId && _state.value?.phase == CallPhase.Ringing) _state.value = null
        // In a DM, declining ends the call so the caller isn't left hanging.
        if (wasDm) runCatching { endCallOnServer(callId) }
    }

    /** The server says the call is over (everyone left, or the caller gave up). */
    fun remoteEnded(callId: String) {
        notifier.cancelCall(callId)
        if (ringing?.id == callId) {
            ringing = null
            dismiss("Missed call")
        } else if (_state.value?.callId == callId) {
            scope.launch { leave(endForEveryone = false, reason = "Call ended") }
        }
    }

    // --- in-call controls ---------------------------------------------------------------

    fun toggleMic() {
        val on = !(_state.value?.micOn ?: true)
        audioTrack?.setEnabled(on)
        _state.update { it?.copy(micOn = on) }
        scope.launch { trackPresence() }
    }

    fun toggleCamera() {
        val track = videoTrack ?: return
        val on = !(_state.value?.camOn ?: true)
        track.setEnabled(on)
        if (on) runCatching { capturer?.startCapture(1280, 720, 30) } else runCatching { capturer?.stopCapture() }
        _state.update { it?.copy(camOn = on) }
        scope.launch { trackPresence() }
    }

    fun flipCamera() {
        capturer?.switchCamera(null)
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val on = !(_state.value?.speakerOn ?: false)
        setSpeaker(on)
        _state.update { it?.copy(speakerOn = on) }
    }

    suspend fun hangUp() = leave(endForEveryone = null, reason = null)

    // --- the actual call ------------------------------------------------------------------

    private suspend fun join(call: CallRow, title: String, outgoing: Boolean) {
        seenCalls += call.id
        try {
            connect(call, title, outgoing)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            leave(endForEveryone = false, reason = "Couldn't connect")
        }
    }

    private suspend fun connect(call: CallRow, title: String, outgoing: Boolean) {
        val me = myId() ?: return
        _state.value = CallUi(
            call.id, call.groupId, title, call.video,
            phase = if (outgoing) CallPhase.Calling else CallPhase.Connecting,
            camOn = call.video, speakerOn = call.video,
        )
        withContext(Dispatchers.Default) { createLocalTracks(call.video) }
        _state.update { it?.copy(localVideo = videoTrack) }
        startAudio(speaker = call.video)
        CallService.start(context)

        val ch = client.channel("call:${call.id}") {
            isPrivate = true
            presence { key = me }
            broadcast { receiveOwnBroadcasts = false }
        }
        channel = ch
        val signals = ch.broadcastFlow<Signal>(event = "signal")
        val presence = ch.presenceChangeFlow()
        val callRow = ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "calls"
            filter("id", FilterOperator.EQ, call.id)
        }
        ch.subscribe(blockUntilSubscribed = true)

        jobs += scope.launch {
            presence.collect { action ->
                val joined = action.decodeJoinsAs<PresenceState>().filter { it.userId != me }
                joined.forEach { p ->
                    upsertParticipant(p.userId) { it.copy(micOn = p.mic, camOn = p.cam) }
                    meet(p.userId)
                }
                // Updating your status (mute, camera) shows up as a leave plus a join for the same person.
                // Only a leave without a matching join means they actually left.
                val stillHere = joined.map { it.userId }.toSet()
                action.decodeLeavesAs<PresenceState>()
                    .filter { it.userId != me && it.userId !in stillHere }
                    .forEach { closePeer(it.userId) }
            }
        }
        jobs += scope.launch {
            signals.collect { s ->
                when {
                    // Someone just joined and is announcing themselves to everyone.
                    s.type == "hello" && s.from != me -> {
                        upsertParticipant(s.from) { it }
                        meet(s.from)
                        // If it's their job to make the offer, tell them we're here so they don't wait for presence.
                        if (me > s.from) runCatching { send(Signal(me, s.from, "here")) }
                    }
                    s.to == me -> runCatching { onSignal(s) }.onFailure { Log.w(TAG, "signal ${s.type} from ${s.from} failed", it) }
                }
            }
        }
        jobs += scope.launch {
            callRow.collect { change ->
                if (change.decodeRecord<CallRow>().endedAt != null) leave(endForEveryone = false, reason = "Call ended")
            }
        }
        trackPresence()
        // Don't wait for presence to notice us: tell whoever is already here.
        runCatching { send(Signal(me, "*", "hello")) }

        if (outgoing) {
            // Nobody picked up.
            jobs += scope.launch {
                delay(Notifier.RING_TIMEOUT_MS)
                if (_state.value?.participants.isNullOrEmpty()) leave(endForEveryone = true, reason = "No answer")
            }
        }
    }

    private suspend fun trackPresence() {
        val me = myId() ?: return
        val s = _state.value ?: return
        runCatching {
            channel?.track(buildJsonObject {
                put("user_id", me)
                put("mic", s.micOn)
                put("cam", s.video && s.camOn)
            })
        }
    }

    /** Starts connecting to [userId] if we aren't already. The smaller id makes the offer, so two people never both do. */
    private suspend fun meet(userId: String) {
        val me = myId() ?: return
        if (me > userId) return // they'll make the offer
        val claimed = peerLock.withLock { (userId !in peers && userId !in meeting).also { if (it) meeting += userId } }
        if (claimed) {
            try {
                offer(userId)
            } finally {
                peerLock.withLock { meeting -= userId }
            }
        }
    }

    private suspend fun offer(userId: String, iceRestart: Boolean = false) {
        val pc = peer(userId)
        val sdp = pc.createOfferAsync(iceRestart)
        pc.setLocalAsync(sdp)
        send(Signal(myId()!!, userId, "offer", sdp = sdp.description))
        Log.d(TAG, "offer -> $userId${if (iceRestart) " (ICE restart)" else ""}")
    }

    private suspend fun onSignal(s: Signal) {
        when (s.type) {
            "offer" -> {
                val pc = peer(s.from)
                pc.setRemoteAsync(SessionDescription(SessionDescription.Type.OFFER, s.sdp))
                flushIce(s.from, pc)
                val answer = pc.createAnswerAsync()
                pc.setLocalAsync(answer)
                send(Signal(myId()!!, s.from, "answer", sdp = answer.description))
            }
            "answer" -> {
                val pc = peerLock.withLock { peers[s.from] } ?: return
                pc.setRemoteAsync(SessionDescription(SessionDescription.Type.ANSWER, s.sdp))
                flushIce(s.from, pc)
            }
            "ice" -> {
                val candidate = IceCandidate(s.mid, s.index ?: 0, s.candidate)
                val pc = peerLock.withLock { peers[s.from] }
                if (pc?.remoteDescription != null) pc.addIceCandidate(candidate)
                else peerLock.withLock { pendingIce.getOrPut(s.from) { mutableListOf() }.add(candidate) }
            }
            "here" -> {
                upsertParticipant(s.from) { it }
                meet(s.from)
            }
            "bye" -> closePeer(s.from)
        }
    }

    private suspend fun flushIce(userId: String, pc: PeerConnection) {
        val queued = peerLock.withLock { pendingIce.remove(userId) }.orEmpty()
        queued.forEach { pc.addIceCandidate(it) }
    }

    private suspend fun peer(userId: String): PeerConnection = peerLock.withLock {
        peers[userId] ?: createPeer(userId).also { peers[userId] = it }
    }

    private fun createPeer(userId: String): PeerConnection {
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
        }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    send(Signal(myId()!!, userId, "ice", candidate = candidate.sdp, mid = candidate.sdpMid, index = candidate.sdpMLineIndex))
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) scope.launch { upsertParticipant(userId) { it.copy(video = track) } }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            upsertParticipant(userId) { it.copy(connected = true) }
                            _state.update { it?.copy(phase = CallPhase.Active, connectedAt = it.connectedAt ?: System.currentTimeMillis()) }
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            val me = myId()
                            // Networks change (Wi-Fi to data, etc.): try once more with fresh routes before giving up.
                            if (me != null && me < userId && restarted.add(userId)) {
                                Log.w(TAG, "connection to $userId failed, restarting ICE")
                                runCatching { offer(userId, iceRestart = true) }
                            } else if (me != null && me > userId && restarted.add(userId)) {
                                Log.w(TAG, "connection to $userId failed, waiting for their ICE restart")
                                delay(8_000)
                                if (peerLock.withLock { peers[userId] }?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) {
                                    closePeer(userId, failed = true)
                                }
                            } else {
                                closePeer(userId, failed = true)
                            }
                        }
                        else -> Unit
                    }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ice $userId: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }) ?: error("Couldn't set up the call")
        audioTrack?.let { pc.addTrack(it, listOf("gng")) }
        videoTrack?.let { pc.addTrack(it, listOf("gng")) }
        return pc
    }

    private suspend fun closePeer(userId: String, failed: Boolean = false) {
        val pc = peerLock.withLock {
            pendingIce.remove(userId)
            peers.remove(userId)
        }
        pc?.dispose()
        _state.update { s -> s?.copy(participants = s.participants.filterNot { it.userId == userId }) }
        val s = _state.value ?: return
        when {
            // Two-person call and the other side left: we're done too.
            s.phase == CallPhase.Active && s.participants.isEmpty() -> leave(endForEveryone = true, reason = "Call ended")
            // Never managed to connect at all.
            failed && s.phase != CallPhase.Active && s.participants.isEmpty() ->
                leave(endForEveryone = true, reason = if (hasTurn) "Couldn't connect" else "Couldn't connect. This network may block calls")
        }
    }

    private val hasTurn get() = BuildConfig.TURN_URLS.isNotBlank()

    private fun upsertParticipant(userId: String, change: (Participant) -> Participant) {
        _state.update { s ->
            s ?: return@update null
            val existing = s.participants.firstOrNull { it.userId == userId } ?: Participant(userId)
            s.copy(participants = s.participants.filterNot { it.userId == userId } + change(existing))
        }
    }

    /**
     * Leaves the call. [endForEveryone]: true ends it on the server, false never does,
     * null ends it only if nobody else is still in it.
     */
    private suspend fun leave(endForEveryone: Boolean?, reason: String?) {
        val s = _state.value ?: return
        if (s.phase == CallPhase.Ended) return
        _state.value = s.copy(phase = CallPhase.Ended, endedReason = reason)
        val me = myId()
        withContext(NonCancellable) {
            val others = peerLock.withLock { peers.keys.toList() }
            if (me != null) others.forEach { runCatching { send(Signal(me, it, "bye")) } }
            val shouldEnd = endForEveryone ?: others.isEmpty()
            if (shouldEnd) runCatching { endCallOnServer(s.callId) }
            jobs.forEach { it.cancel() }
            jobs.clear()
            channel?.let { runCatching { client.realtime.removeChannel(it) } }
            channel = null
            peerLock.withLock {
                peers.values.forEach { it.dispose() }
                peers.clear()
                pendingIce.clear()
                meeting.clear()
                restarted.clear()
            }
            releaseLocalTracks()
            stopAudio()
            CallService.stop(context)
        }
        delay(1200) // let "Call ended" show for a moment
        if (_state.value?.callId == s.callId) _state.value = null
    }

    private fun dismiss(reason: String) {
        val s = _state.value ?: return
        notifier.cancelCall(s.callId)
        _state.value = s.copy(phase = CallPhase.Ended, endedReason = reason)
        scope.launch {
            delay(1200)
            if (_state.value?.callId == s.callId) _state.value = null
        }
    }

    private suspend fun endCallOnServer(callId: String) {
        client.postgrest.rpc("end_call", buildJsonObject { put("p_call", callId) })
    }

    private suspend fun send(signal: Signal) {
        channel?.broadcast(event = "signal", message = signal)
    }

    // --- media ----------------------------------------------------------------------------

    private fun createLocalTracks(video: Boolean) {
        val audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("mic", audioSource).apply { setEnabled(true) }
        if (!video) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return
        val cam = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("camera", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        cam.initialize(helper, context, source.capturerObserver)
        cam.startCapture(1280, 720, 30)
        capturer = cam
        textureHelper = helper
        videoSource = source
        videoTrack = factory.createVideoTrack("cam", source).apply { setEnabled(true) }
    }

    private fun releaseLocalTracks() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        textureHelper?.dispose()
        textureHelper = null
        audioTrack?.dispose()
        audioTrack = null
    }

    private fun startAudio(speaker: Boolean) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audio.setCommunicationDevice(it) }
            } else {
                audio.clearCommunicationDevice()
            }
        } else {
            audio.isSpeakerphoneOn = on
        }
    }

    private fun stopAudio() {
        setSpeaker(false)
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun iceServers(): List<PeerConnection.IceServer> = buildList {
        add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val turn = BuildConfig.TURN_URLS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (turn.isNotEmpty()) {
            add(
                PeerConnection.IceServer.builder(turn)
                    .setUsername(BuildConfig.TURN_USERNAME)
                    .setPassword(BuildConfig.TURN_CREDENTIAL)
                    .createIceServer(),
            )
        }
    }
}

// --- SDP callbacks as suspend functions ---------------------------------------------------

private suspend fun PeerConnection.createOfferAsync(iceRestart: Boolean = false): SessionDescription = suspendCoroutine { cont ->
    val constraints = MediaConstraints().apply {
        if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
    }
    createOffer(sdpObserver(onCreate = { cont.resume(it) }, onFail = { cont.resumeWithException(IllegalStateException(it)) }), constraints)
}

private suspend fun PeerConnection.createAnswerAsync(): SessionDescription = suspendCoroutine { cont ->
    createAnswer(sdpObserver(onCreate = { cont.resume(it) }, onFail = { cont.resumeWithException(IllegalStateException(it)) }), MediaConstraints())
}

private suspend fun PeerConnection.setLocalAsync(sdp: SessionDescription): Unit = suspendCoroutine { cont ->
    setLocalDescription(sdpObserver(onSet = { cont.resume(Unit) }, onFail = { cont.resumeWithException(IllegalStateException(it)) }), sdp)
}

private suspend fun PeerConnection.setRemoteAsync(sdp: SessionDescription): Unit = suspendCoroutine { cont ->
    setRemoteDescription(sdpObserver(onSet = { cont.resume(Unit) }, onFail = { cont.resumeWithException(IllegalStateException(it)) }), sdp)
}

private fun sdpObserver(
    onCreate: (SessionDescription) -> Unit = {},
    onSet: () -> Unit = {},
    onFail: (String) -> Unit,
) = object : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
    override fun onSetSuccess() = onSet()
    override fun onCreateFailure(error: String?) = onFail(error ?: "createFailure")
    override fun onSetFailure(error: String?) = onFail(error ?: "setFailure")
}
