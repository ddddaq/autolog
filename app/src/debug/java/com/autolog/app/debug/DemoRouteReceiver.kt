package com.autolog.app.debug

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 시연 준비용. 내장 내비에 예시 목적지(Pleos 개발 문서의 예시 POI)를 넣는다. 디버그 빌드에만 있다.
 *   adb shell am broadcast --user 10 -n com.autolog.app/.debug.DemoRouteReceiver
 * 앱 본체는 경로를 바꾸지 않는다. 여기서 만든 NaviHelper는 해제하지 않는다.
 * SDK가 프로세스 안에서 연결을 함께 쓰기 때문에, 해제하면 기록기 쪽 연결까지 끊긴다.
 */
class DemoRouteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val navi = NaviHelper(context.applicationContext)
        navi.initialize(
            {
                runCatching {
                    navi.requestRoute(
                        RouteInfo(
                            latitude = 37.48544722,
                            longitude = 127.03636666,
                            displayLongitude = null,
                            displayLatitude = null,
                            poiName = "42dot",
                            poiId = "36khp37hh8gqcm5nqhe5",
                            poiSubId = "0",
                            address = "서울시 강남구 남부순환로 2621",
                        )
                    )
                }.onSuccess { Log.i("AUTOLOG", "시연용: 내비 목적지(42dot) 경로 요청") }
                    .onFailure { Log.w("AUTOLOG", "시연용 경로 요청 실패: ${it.message}") }
            },
            { error -> Log.w("AUTOLOG", "시연용 NaviHelper 초기화 실패: ${error.message}") },
        )
    }
}
