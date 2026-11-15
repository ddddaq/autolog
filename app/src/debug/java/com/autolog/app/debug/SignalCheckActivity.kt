package com.autolog.app.debug

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 개발용 신호 확인 화면. 오토로그가 운행 경계를 잡는 데 쓰는 네 신호(기어, 계기판 거리, 운전석 도어, 운전석 착석)가
 * 앱까지 들어오는지 보여 준다. 값은 화면과 logcat(tag AUTOLOG)에 함께 남긴다.
 */
class SignalCheckActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val values = linkedMapOf(
        "기어 (Pleos SDK)" to "-",
        "기어 (AAOS GEAR_SELECTION)" to "-",
        "주행 제한 (AAOS UX)" to "-",
        "계기판 거리 km" to "-",
        "트립 거리 km" to "-",
        "운전석 도어" to "-",
        "운전석 착석" to "-",
        "VIN" to "-",
        "위치 (FusedLocation)" to "-",
        "현재 주소 (NaviHelper)" to "-",
        "목적지 (NaviHelper)" to "-",
        "음성 안내 (TTS)" to "-",
    )
    private val events = ArrayDeque<String>()
    private lateinit var valueView: TextView
    private lateinit var eventView: TextView

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) = update(
            "위치 (FusedLocation)",
            location?.let { "%.5f, %.5f (신뢰도 %.2f)".format(it.latitude, it.longitude, it.confidence) },
            "location",
        )
        override fun onError(exception: Exception) = fail("위치 (FusedLocation)", exception)
    }
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) =
            update("현재 주소 (NaviHelper)", "${currentLocationInfo.address} (%.5f, %.5f)".format(currentLocationInfo.latitude, currentLocationInfo.longitude), "navi")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) =
            update("목적지 (NaviHelper)", "${destinationInfo.name} / ${destinationInfo.address} / 남은 ${destinationInfo.remainDistance}m", "navi")
        override fun onDestinationChanged() {
            event("NaviHelper: 목적지 변경됨")
            attempt("목적지 (NaviHelper)") { navi?.getDestinationInfo() }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            update("목적지 (NaviHelper)", "도착: ${destinationArrivedInfo.name} / ${destinationArrivedInfo.address}", "navi")
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
            event("NaviHelper: 경로 탐색 시작 ${routeStartInfo.name} ${routeStartInfo.distance}m ${routeStartInfo.duration}s")
        override fun onRoutingCompleted() {
            event("NaviHelper: 경로 탐색 완료")
            attempt("목적지 (NaviHelper)") { navi?.getDestinationInfo() }
        }
        override fun onRouteCancelled() = event("NaviHelper: 경로 취소됨")
        override fun onRouteEnded() = event("NaviHelper: 경로 안내 종료")
        override fun onError(errorCode: NaviErrorCode) = event("NaviHelper 오류: ${errorCode.name}")
    }
    private val ttsListener = object : EventListener {
        override fun onStart() = update("음성 안내 (TTS)", "onStart", "tts")
        override fun onReady() = update("음성 안내 (TTS)", "onReady", "tts")
        override fun onDone() = update("음성 안내 (TTS)", "onDone", "tts")
        override fun onError(errorType: ErrorType) = update("음성 안내 (TTS)", "오류 ${errorType.name}", "tts")
        override fun onStop() = update("음성 안내 (TTS)", "onStop", "tts")
        override fun onUpdatedRms(rms: Double) = Unit
    }
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = update("기어 (Pleos SDK)", gear?.name, "listener")
        override fun onFailed(e: Exception) = fail("기어 (Pleos SDK)", e)
    }
    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(distance: Float?) = update("계기판 거리 km", distance?.toString(), "listener")
        override fun onFailed(e: Exception) = fail("계기판 거리 km", e)
    }
    private val tripListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(distance: Float?) = update("트립 거리 km", distance?.toString(), "listener")
        override fun onFailed(e: Exception) = fail("트립 거리 km", e)
    }
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(position: Int?) = update("운전석 도어", doorText(position), "listener")
        override fun onFailed(e: Exception) = fail("운전석 도어", e)
    }
    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) = update("운전석 착석", occupied?.toString(), "listener")
        override fun onFailed(e: Exception) = fail("운전석 착석", e)
    }
    private val aaosGearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = update("기어 (AAOS GEAR_SELECTION)", gearText(value.value as? Int), "callback")
        override fun onErrorEvent(propId: Int, zone: Int) = update("기어 (AAOS GEAR_SELECTION)", "error event", "callback")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 40, 48, 40) }
        root.addView(TextView(this).apply { text = "AutoLog 신호 확인"; textSize = 30f; setTypeface(typeface, Typeface.BOLD) })
        valueView = TextView(this).apply { textSize = 24f; typeface = Typeface.MONOSPACE; setPadding(0, 24, 0, 24) }
        eventView = TextView(this).apply { textSize = 18f; typeface = Typeface.MONOSPACE }
        root.addView(valueView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TextView(this).apply { text = "이벤트 기록 (최근 것이 위)"; textSize = 20f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(eventView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onStart() {
        super.onStart()
        connectPleos()
        connectAaos()
        connectLocation()
        connectNavi()
        connectTts()
    }

    // SDK 객체는 release()하지 않는다. 같은 프로세스의 운행 기록기가 같은 연결을 쓰기 때문이다.
    override fun onStop() {
        super.onStop()
        main.removeCallbacksAndMessages(null)
        vehicle?.let { v ->
            runCatching { v.getGear().unregisterGearSelection(gearListener) }
            runCatching { v.getOdometer().unregisterDistanceDriven(odometerListener) }
            runCatching { v.getOdometer().unregisterTripCurrentDrivingDistance(tripListener) }
            runCatching { v.getDoor().unregisterDoorPosition(driverDoor, doorListener) }
            runCatching { v.getSeat().unregisterSeatOccupancy(driverSeat, seatListener) }
        }
        vehicle = null
        runCatching { car?.disconnect() }
        car = null
        fused?.let { runCatching { it.unregisterFusedLocationCallback(locationListener) } }
        fused = null
        navi?.let { runCatching { it.removeListener(naviListener) } }
        navi = null
        tts?.let { runCatching { it.removeEventListener(ttsListener) } }
        tts = null
    }

    private fun connectLocation() = attempt("위치 (FusedLocation)") {
        val f = FusedLocation(applicationContext)
        fused = f
        event("FusedLocation isSupported=${f.isSupported()}")
        f.initialize()
        main.postDelayed({
            attempt("위치 (FusedLocation)") { f.getLastKnownLocation(locationListener) }
            attempt("위치 (FusedLocation)") { f.registerFusedLocationCallback(locationListener) }
        }, 1500)
    }

    private fun connectNavi() = attempt("현재 주소 (NaviHelper)") {
        val n = NaviHelper(applicationContext)
        navi = n
        n.initialize(
            {
                event("NaviHelper 초기화 성공")
                main.post {
                    attempt("현재 주소 (NaviHelper)") { n.addListener(naviListener) }
                    attempt("현재 주소 (NaviHelper)") { n.getCurrentLocationInfo() }
                    attempt("목적지 (NaviHelper)") { n.getDestinationInfo() }
                    // 시연 준비: --es route sample 이면 문서의 예시 목적지로 경로를 요청하고, --ez cancelRoute true 면 취소한다.
                    if (intent?.getStringExtra("route") == "sample") attempt("목적지 (NaviHelper)") {
                        n.requestRoute(
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
                        event("NaviHelper: 예시 목적지(42dot)로 경로 요청")
                    }
                    if (intent?.getBooleanExtra("cancelRoute", false) == true) attempt("목적지 (NaviHelper)") {
                        n.cancelRoute()
                        event("NaviHelper: 경로 취소 요청")
                    }
                    // --ez finish true 면 요청만 하고 화면을 닫아 원래 화면으로 돌아간다.
                    if (intent?.getBooleanExtra("finish", false) == true) main.postDelayed({ finish() }, 2_500)
                }
            },
            { e -> fail("현재 주소 (NaviHelper)", e) },
        )
        // 주소는 위치가 바뀐 뒤 다시 물어봐야 갱신되는지 확인하려고 10초마다 다시 조회한다.
        main.postDelayed(object : Runnable {
            override fun run() {
                attempt("현재 주소 (NaviHelper)") { n.getCurrentLocationInfo() }
                main.postDelayed(this, 10_000)
            }
        }, 10_000)
    }

    private fun connectTts() = attempt("음성 안내 (TTS)") {
        val t = TextToSpeech(applicationContext, Mode.ON_DEVICE)
        tts = t
        t.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                event("TTS 초기화 성공 (ON_DEVICE)")
                main.post {
                    // 리스너는 초기화가 끝난 뒤에 붙여야 한다. 먼저 붙이면 SDK 안에서 예외가 난다.
                    attempt("음성 안내 (TTS)") { t.addEventListener(ttsListener) }
                    attempt("음성 안내 (TTS)") { t.setLocale(Locale.KOREA) }
                    attempt("음성 안내 (TTS)") { t.speak("오토로그 음성 안내 시험입니다.") }
                }
            }
            override fun onFailure(errorType: ErrorType) = update("음성 안내 (TTS)", "초기화 실패 ${errorType.name}", "tts")
        })
    }

    private fun connectPleos() {
        val v = try {
            Vehicle(applicationContext).also { it.initialize() }
        } catch (e: Exception) {
            event("Pleos SDK 초기화 실패: ${e.javaClass.simpleName}: ${e.message}"); return
        }
        vehicle = v
        event("Pleos Vehicle SDK initialize() 호출")
        // 서비스 연결 직후에는 조회가 실패할 수 있어 조금 뒤에 조회·구독한다.
        main.postDelayed({ queryPleos(v); registerPleos(v) }, 1000)
        main.postDelayed({ queryPleos(v) }, 4000)
    }

    private fun queryPleos(v: Vehicle) {
        attempt("기어 (Pleos SDK)") { v.getGear().getCurrentGearSelectionState({ g -> update("기어 (Pleos SDK)", g?.name, "get") }, { e -> fail("기어 (Pleos SDK)", e) }) }
        attempt("계기판 거리 km") { v.getOdometer().getCurrentDistanceDriven({ d -> update("계기판 거리 km", d?.toString(), "get") }, { e -> fail("계기판 거리 km", e) }) }
        attempt("트립 거리 km") { v.getOdometer().getTripCurrentDrivingDistance({ d -> update("트립 거리 km", d?.toString(), "get") }, { e -> fail("트립 거리 km", e) }) }
        attempt("운전석 도어") { v.getDoor().getCurrentDoorPosition(driverDoor, { p -> update("운전석 도어", doorText(p), "get") }, { e -> fail("운전석 도어", e) }) }
        attempt("운전석 착석") { v.getSeat().getCurrentSeatOccupancy(driverSeat, { o -> update("운전석 착석", o?.toString(), "get") }, { e -> fail("운전석 착석", e) }) }
        attempt("VIN") { v.getCarInfo().getVINInfo({ vin -> update("VIN", vin?.let { mask(it) }, "get") }, { e -> fail("VIN", e) }) }
    }

    private fun registerPleos(v: Vehicle) {
        attempt("기어 (Pleos SDK)") { v.getGear().registerGearSelection(gearListener) }
        attempt("계기판 거리 km") { v.getOdometer().registerDistanceDriven(odometerListener) }
        attempt("트립 거리 km") { v.getOdometer().registerTripCurrentDrivingDistance(tripListener) }
        attempt("운전석 도어") { v.getDoor().registerDoorPosition(driverDoor, doorListener) }
        attempt("운전석 착석") { v.getSeat().registerSeatOccupancy(driverSeat, seatListener) }
        event("Pleos 리스너 5개 등록 요청")
    }

    private fun connectAaos() {
        try {
            val c = Car.createCar(this) ?: run { event("AAOS Car 연결 실패 (null)"); return }
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            attempt("기어 (AAOS GEAR_SELECTION)") {
                update("기어 (AAOS GEAR_SELECTION)", gearText(pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)), "get")
                val ok = pm.registerCallback(aaosGearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                event("AAOS GEAR_SELECTION 콜백 등록: $ok")
            }
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            attempt("주행 제한 (AAOS UX)") {
                ux.registerListener { r -> update("주행 제한 (AAOS UX)", "DO=${r.isRequiresDistractionOptimization} flags=${r.activeRestrictions}", "listener") }
                val r = ux.currentCarUxRestrictions
                update("주행 제한 (AAOS UX)", "DO=${r?.isRequiresDistractionOptimization} flags=${r?.activeRestrictions}", "get")
            }
        } catch (e: Exception) {
            event("AAOS Car API 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private inline fun attempt(key: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { fail(key, e) }
    }

    private fun update(key: String, value: String?, source: String) {
        main.post {
            val text = value ?: "null"
            values[key] = text
            event("$key = $text ($source)")
        }
    }

    private fun fail(key: String, e: Throwable) {
        main.post {
            values[key] = "실패: ${e.javaClass.simpleName}"
            event("$key 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun event(line: String) {
        Log.i(TAG, line)
        main.post {
            events.addFirst("${clock.format(Date())}  $line")
            while (events.size > 40) events.removeLast()
            render()
        }
    }

    private fun render() {
        valueView.text = values.entries.joinToString("\n") { (k, v) -> k.padEnd(28) + v }
        eventView.text = events.joinToString("\n")
    }

    private fun doorText(position: Int?) = when (position) { null -> null; 0 -> "닫힘 (0)"; else -> "열림 ($position)" }

    private fun gearText(gear: Int?) = when (gear) {
        null -> null; 1 -> "NEUTRAL (1)"; 2 -> "REVERSE (2)"; 4 -> "PARK (4)"; 8 -> "DRIVE (8)"; else -> "기타 ($gear)"
    }

    /** VIN은 식별 정보라 화면과 로그에는 앞 3자리만 남긴다. */
    private fun mask(vin: String) = if (vin.length <= 3) vin else vin.take(3) + "*".repeat(vin.length - 3)

    private companion object { const val TAG = "AUTOLOG" }
}
