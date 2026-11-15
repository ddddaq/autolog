package com.autolog.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autolog.app.AutoLogApp
import com.autolog.app.trip.TripRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 개발용 확인. 마이크로 말하는 대신 녹음해 둔 소리(files/demo_voice.pcm, 16kHz 16비트 모노)를
 * 음성 인식에 넣고 알아들은 말을 정차 중인 운행(없으면 가장 최근 운행)의 메모로 적는다. 화면의 '음성 메모' 버튼과 같은 경로를 탄다.
 */
class DemoVoiceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AutoLogApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val pcm = withContext(Dispatchers.IO) { File(app.filesDir, "demo_voice.pcm").takeIf { it.exists() }?.readBytes() }
                if (pcm == null) {
                    Log.w("AUTOLOG", "음성 확인: 녹음 파일이 없음")
                    return@launch
                }
                // 정차 대기 중인 운행이 있으면 그 카드의 메모로 적는다. 화면의 버튼이 하는 일과 같다.
                if (app.recorder.status.value.parked != null) {
                    app.voiceMemo.listen(TripRecorder.PARKED_ID, recorded = pcm) { text ->
                        CoroutineScope(Dispatchers.Main).launch { app.recorder.setParkedNote(text.take(60)) }
                    }
                    return@launch
                }
                val trip = withContext(Dispatchers.IO) { app.trips.latest() }
                if (trip == null) {
                    Log.w("AUTOLOG", "음성 확인: 운행이 없음")
                    return@launch
                }
                app.voiceMemo.listen(trip.id, recorded = pcm) { text ->
                    CoroutineScope(Dispatchers.IO).launch { app.trips.setNote(trip.id, text.take(60)) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
