package com.autolog.app.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AAOS 주행 규제 상태. 주행 중(운전자 주의 분산 최소화가 요구되는 상태)이면 true.
 * 화면은 이 값이 true인 동안 상태 한 줄만 남기고 목록과 입력을 감춘다.
 */
class DrivingRestriction(context: Context) {

    private val appContext = context.applicationContext
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    private var car: Car? = null

    fun start() {
        if (car != null) return
        try {
            val c = Car.createCar(appContext) ?: return
            car = c
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux.registerListener { restrictions -> _restricted.value = restrictions.isRequiresDistractionOptimization }
            _restricted.value = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
        } catch (e: Throwable) {
            Log.w("AUTOLOG", "주행 규제 상태를 읽지 못함: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
