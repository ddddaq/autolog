package com.autolog.app.vehicle

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Pleos Connect SpeechToText SDK로 한 마디를 받아 글자로 바꾼다. 운행의 비고 메모에 쓴다.
 * 차량 안에서 인식하는 ON_DEVICE 모드라 네트워크와 앱 등록이 필요 없다. 필요한 권한: pleos.car.permission.STT_SERVICE
 *
 * 마이크는 사용자가 '음성 메모'를 누른 뒤 한 마디가 끝날 때까지만 열린다. 음성 자체는 저장하지 않는다.
 * 상태 변경과 결과 전달은 메인 스레드에서 한다.
 */
class VoiceMemo(context: Context) {

    sealed interface State {
        data object Idle : State

        /** 듣는 중. [heard]는 지금까지 알아들은 말. */
        data class Listening(val tripId: Long, val heard: String) : State

        /** 듣지 못했다. [reason]은 화면에 보여 줄 문장. */
        data class Failed(val tripId: Long, val reason: String) : State
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var stt: SpeechToText? = null
    private var ready = false
    private var onText: ((String) -> Unit)? = null
    private val timeout = Runnable { finish(timedOut = true) }

    private val results = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(text: String, isFinal: Boolean) {
            main.post { updated(text, isFinal) }
        }
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
        override fun onEndedRecognition() {
            main.post { if (_state.value is State.Listening) finish(timedOut = false) }
        }
        override fun onError(errorType: ErrorType) {
            main.post { failed(errorType) }
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start() {
        if (stt != null) return
        try {
            val s = SpeechToText(appContext, Mode.ON_DEVICE)
            stt = s
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    // 리스너와 언어 설정은 초기화가 끝난 뒤에 해야 한다.
                    runCatching { s.addListener(results) }
                    runCatching { s.setLocale(Locale.KOREA) }
                    main.post { ready = true }
                }
                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "음성 인식 초기화 실패: ${errorType.name}")
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "음성 인식을 쓸 수 없음: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 한 마디를 듣고 [onText]로 넘긴다. [tripId]는 어느 운행의 메모인지 화면이 구분하는 데 쓴다.
     * [recorded]를 주면 마이크 대신 그 소리(16kHz, 16비트, 모노 PCM)를 인식한다. 마이크가 없는 개발 환경에서 확인할 때 쓴다.
     */
    fun listen(tripId: Long, recorded: ByteArray? = null, onText: (String) -> Unit) {
        val s = stt
        if (s == null || !ready) {
            _state.value = State.Failed(tripId, "지금은 음성 인식을 쓸 수 없습니다")
            return
        }
        if (_state.value is State.Listening) return
        this.onText = onText
        _state.value = State.Listening(tripId, "")
        val begun = runCatching {
            if (recorded == null) s.request() else sendRecorded(s, recorded)
        }
        if (begun.isFailure) {
            Log.w(TAG, "음성 인식 시작 실패: ${begun.exceptionOrNull()}")
            this.onText = null
            _state.value = State.Failed(tripId, "음성 인식을 시작하지 못했습니다")
            return
        }
        Log.i(TAG, "음성 메모 듣기 시작" + if (recorded != null) " (녹음 ${recorded.size}바이트)" else "")
        main.postDelayed(timeout, LISTEN_TIMEOUT_MS)
    }

    /** 듣기를 그만둔다. 그때까지 알아들은 말이 있으면 메모로 남긴다. */
    fun stopListening() {
        if (_state.value is State.Listening) finish(timedOut = false)
    }

    /** 듣기를 그만두고 알아들은 말도 버린다. 주행이 시작되면 부른다. */
    fun cancel() {
        main.removeCallbacks(timeout)
        if (_state.value is State.Listening) runCatching { stt?.stop() }
        onText = null
        _state.value = State.Idle
    }

    fun clearFailure() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    private fun updated(text: String, isFinal: Boolean) {
        val listening = _state.value as? State.Listening ?: return
        val heard = text.trim()
        _state.value = listening.copy(heard = heard)
        if (isFinal && heard.isNotEmpty()) finish(timedOut = false)
    }

    private fun finish(timedOut: Boolean) {
        val listening = _state.value as? State.Listening ?: return
        main.removeCallbacks(timeout)
        runCatching { stt?.stop() }
        val deliver = onText
        onText = null
        if (listening.heard.isNotEmpty()) {
            Log.i(TAG, "음성 메모: ${listening.heard}")
            _state.value = State.Idle
            deliver?.invoke(listening.heard)
        } else {
            Log.i(TAG, "음성 메모: 알아들은 말 없음" + if (timedOut) " (시간 초과)" else "")
            _state.value = State.Failed(listening.tripId, "알아듣지 못했습니다. 다시 눌러 말씀해 주세요")
        }
    }

    private fun failed(errorType: ErrorType) {
        Log.w(TAG, "음성 인식 오류: ${errorType.name}")
        val listening = _state.value as? State.Listening ?: return
        main.removeCallbacks(timeout)
        runCatching { stt?.stop() }
        onText = null
        _state.value = State.Failed(
            listening.tripId,
            if (errorType == ErrorType.MIC_ERROR || errorType == ErrorType.AUDIO_ERROR) "마이크를 쓸 수 없습니다" else "음성을 인식하지 못했습니다",
        )
    }

    /** 실제 말하는 속도에 맞춰 0.1초 분량씩 보낸다. */
    private fun sendRecorded(s: SpeechToText, pcm: ByteArray) {
        Thread {
            runCatching {
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + CHUNK_BYTES, pcm.size)
                    s.sendAudio(pcm.copyOfRange(offset, end))
                    offset = end
                    Thread.sleep(CHUNK_MS)
                }
                s.completeAudioSend()
            }.onFailure { Log.w(TAG, "녹음 전송 실패: ${it.javaClass.simpleName}: ${it.message}") }
        }.start()
    }

    private companion object {
        const val TAG = "AUTOLOG"
        const val LISTEN_TIMEOUT_MS = 12_000L
        /** 16kHz, 16비트, 모노 PCM의 0.1초 분량 */
        const val CHUNK_BYTES = 3_200
        const val CHUNK_MS = 100L
    }
}
