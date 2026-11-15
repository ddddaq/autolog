package com.autolog.app.vehicle

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Pleos Connect NaviHelper SDK에서 운행의 출발지·도착지 이름에 쓸 정보를 받아 온다.
 * - 현재 위치의 주소와 좌표: getCurrentLocationInfo()
 * - 내장 내비에 설정된 목적지 이름: 경로 시작, 목적지 정보, 도착 이벤트
 * 조회 API만 쓰고 경로를 바꾸지 않는다. 필요한 권한: pleos.car.permission.NAVI_ROUTE
 *
 * 콜백은 항상 메인 스레드에서 불린다.
 */
class NaviPlace(context: Context, private val now: () -> Long = System::currentTimeMillis) {

    data class Spot(val address: String, val latitude: Double, val longitude: Double) {
        /** 위치를 모를 때 오는 (0, 0)은 좌표로 치지 않는다. */
        private val known: Boolean get() = latitude != 0.0 || longitude != 0.0
        val knownLatitude: Double? get() = latitude.takeIf { known }
        val knownLongitude: Double? get() = longitude.takeIf { known }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null
    private var ready = false
    private var listening = false
    private val waiting = mutableListOf<(Spot?) -> Unit>()

    /** 마지막으로 받은 현재 위치. */
    var lastSpot: Spot? = null
        private set

    private var destinationName: String? = null
    private var destinationAtMs = 0L

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val address: String? = currentLocationInfo.address
            val spot = Spot(address.orEmpty().trim(), currentLocationInfo.latitude, currentLocationInfo.longitude)
            main.post { deliver(spot) }
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { val name: String? = routeStartInfo.name; main.post { setDestination(name) } }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { val name: String? = destinationInfo.name; main.post { setDestination(name) } }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { val name: String? = destinationArrivedInfo.name; main.post { setDestination(name) } }
        override fun onDestinationChanged() { main.post { runCatching { navi?.getDestinationInfo() } } }
        override fun onRouteCancelled() { main.post { destinationName = null } }
    }

    fun start() {
        if (listening) return
        listening = true
        val existing = navi
        if (existing != null) {
            if (ready) attach(existing)
            return
        }
        try {
            val n = NaviHelper(appContext)
            navi = n
            n.initialize(
                {
                    main.post {
                        ready = true
                        if (listening) attach(n)
                    }
                },
                { error -> Log.w(TAG, "NaviHelper 초기화 실패: ${error.javaClass.simpleName}: ${error.message}") },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "NaviHelper를 쓸 수 없음: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 이벤트 수신을 끊고 기억해 둔 위치와 목적지를 버린다. SDK 연결은 다른 객체와 함께 쓰므로 해제하지 않는다. */
    fun stop() {
        if (!listening) return
        listening = false
        navi?.let { n -> runCatching { n.removeListener(listener) } }
        waiting.clear()
        lastSpot = null
        destinationName = null
    }

    private fun attach(n: NaviHelper) {
        runCatching { n.addListener(listener) }
        runCatching { n.getCurrentLocationInfo() }
        runCatching { n.getDestinationInfo() }
    }

    /** 현재 위치를 다시 물어 결과를 넘긴다. 답이 없으면 마지막으로 알던 위치(없으면 null)를 넘긴다. */
    fun requestCurrentSpot(onResult: (Spot?) -> Unit) {
        val n = navi
        if (!ready || !listening || n == null) {
            onResult(lastSpot)
            return
        }
        waiting += onResult
        runCatching { n.getCurrentLocationInfo() }
        main.postDelayed({ if (waiting.remove(onResult)) onResult(lastSpot) }, ANSWER_TIMEOUT_MS)
    }

    /** [sinceMs] 이후에 내비에 설정된 목적지 이름. 없으면 null. */
    fun recentDestination(sinceMs: Long): String? = destinationName?.takeIf { destinationAtMs >= sinceMs }

    fun clearDestination() {
        destinationName = null
    }

    private fun setDestination(name: String?) {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        destinationName = trimmed
        destinationAtMs = now()
        Log.i(TAG, "내비 목적지: $trimmed")
    }

    private fun deliver(spot: Spot) {
        if (spot.address.isNotEmpty() || lastSpot == null) lastSpot = spot
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(lastSpot) }
    }

    private companion object {
        const val TAG = "AUTOLOG"
        const val ANSWER_TIMEOUT_MS = 3_000L
    }
}
