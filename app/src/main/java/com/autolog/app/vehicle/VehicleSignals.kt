package com.autolog.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.autolog.app.trip.Gear

/**
 * Pleos Connect Vehicle SDK에서 운행 경계 판정에 필요한 세 신호를 받아 온다.
 * 기어(Gear), 계기판 누적 거리(Odometer), 운전석 도어(Door). 모두 조회·구독 API이고 차량을 제어하지 않는다.
 * 필요한 권한: pleos.car.permission.CAR_DRIVE, pleos.car.permission.CAR_DOORS
 *
 * 리스너는 항상 메인 스레드에서 불린다.
 */
class VehicleSignals(context: Context) {

    interface Listener {
        fun onGear(gear: Gear)
        fun onOdometer(km: Float)
        fun onDriverDoor(open: Boolean)
        fun onProblem(what: String, error: Throwable)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private var vehicle: Vehicle? = null
    private var listener: Listener? = null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { deliverGear(gear) }
        override fun onFailed(e: Exception) { problem("기어 구독", e) }
    }
    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) { deliverOdometer(value) }
        override fun onFailed(e: Exception) { problem("계기판 거리 구독", e) }
    }
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) { deliverDoor(pos) }
        override fun onFailed(e: Exception) { problem("운전석 도어 구독", e) }
    }

    fun start(listener: Listener) {
        if (this.listener != null) return
        this.listener = listener
        val v = vehicle ?: try {
            Vehicle(appContext).also { it.initialize() }
        } catch (e: Throwable) {
            problem("Vehicle SDK 초기화", e)
            return
        }
        vehicle = v
        // initialize() 직후에는 차량 서비스 연결이 끝나지 않았을 수 있어 조금 뒤에 조회·구독한다.
        main.postDelayed({ query(v); register(v) }, CONNECT_DELAY_MS)
        main.postDelayed({ query(v) }, RETRY_DELAY_MS)
    }

    /**
     * 구독을 끊는다. 같은 프로세스의 Pleos SDK 객체들이 서비스 연결을 함께 쓰므로 release()는 부르지 않는다.
     * 부르면 내비·음성 쪽 연결까지 끊긴다.
     */
    fun stop() {
        main.removeCallbacksAndMessages(null)
        vehicle?.let { v ->
            runCatching { v.getGear().unregisterGearSelection(gearListener) }
            runCatching { v.getOdometer().unregisterDistanceDriven(odometerListener) }
            runCatching { v.getDoor().unregisterDoorPosition(driverDoor, doorListener) }
        }
        listener = null
    }

    private fun query(v: Vehicle) {
        attempt("계기판 거리 조회") { v.getOdometer().getCurrentDistanceDriven({ deliverOdometer(it) }, { problem("계기판 거리 조회", it) }) }
        attempt("운전석 도어 조회") { v.getDoor().getCurrentDoorPosition(driverDoor, { deliverDoor(it) }, { problem("운전석 도어 조회", it) }) }
        attempt("기어 조회") { v.getGear().getCurrentGearSelectionState({ deliverGear(it) }, { problem("기어 조회", it) }) }
    }

    private fun register(v: Vehicle) {
        attempt("계기판 거리 구독") { v.getOdometer().registerDistanceDriven(odometerListener) }
        attempt("운전석 도어 구독") { v.getDoor().registerDoorPosition(driverDoor, doorListener) }
        attempt("기어 구독") { v.getGear().registerGearSelection(gearListener) }
    }

    private fun deliverGear(gear: VehicleGear?) {
        val mapped = gear.toGear()
        main.post { listener?.onGear(mapped) }
    }

    private fun deliverOdometer(km: Float?) {
        if (km == null || km.isNaN() || km < 0f) return
        main.post { listener?.onOdometer(km) }
    }

    private fun deliverDoor(position: Int?) {
        if (position == null) return
        main.post { listener?.onDriverDoor(position > 0) }
    }

    private fun problem(what: String, error: Throwable) {
        main.post { listener?.onProblem(what, error) }
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { problem(what, e) }
    }

    private fun VehicleGear?.toGear(): Gear = when (this) {
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        else -> Gear.DRIVE // GEAR_DRIVE와 수동 단수(GEAR_1~9)
    }

    private companion object {
        const val CONNECT_DELAY_MS = 1_000L
        const val RETRY_DELAY_MS = 4_000L
    }
}
