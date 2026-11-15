package com.autolog.app.vehicle

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Pleos Connect TextToSpeech SDK로 짧은 안내를 말한다. 운전자가 화면을 보지 않아도 운행이 기록됐음을 알 수 있다.
 * 차량 안에서 합성하는 ON_DEVICE 모드라 네트워크와 앱 등록이 필요 없다. 필요한 권한: pleos.car.permission.TTS_SERVICE
 */
class VoiceGuide(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false

    private val events = object : EventListener {
        override fun onStart() { Log.i(TAG, "음성 안내 시작") }
        override fun onReady() = Unit
        override fun onDone() { Log.i(TAG, "음성 안내 끝") }
        override fun onError(errorType: ErrorType) { Log.w(TAG, "음성 안내 오류: ${errorType.name}") }
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start() {
        if (tts != null) return
        try {
            val t = TextToSpeech(appContext, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    // 리스너와 언어 설정은 초기화가 끝난 뒤에 해야 한다.
                    runCatching { t.addEventListener(events) }
                    runCatching { t.setLocale(Locale.KOREA) }
                    ready = true
                }
                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "음성 안내 초기화 실패: ${errorType.name}")
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "음성 안내를 쓸 수 없음: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun speak(text: String) {
        val t = tts ?: return
        if (!ready) return
        runCatching { t.speak(text) }.onFailure { Log.w(TAG, "음성 안내 실패: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private companion object {
        const val TAG = "AUTOLOG"
    }
}
