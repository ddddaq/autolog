package com.autolog.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {

    private val minute = 60_000L

    @Test
    fun `기어 D에서 열고 P 후 운전석 문이 열리면 운행 한 건이 닫힌다`() {
        val d = TripDetector()
        d.onOdometer(12_340f)
        assertNull(d.onGear(Gear.DRIVE, 0))
        assertTrue(d.state is DetectorState.Driving)
        d.onOdometer(12_372f)
        assertNull(d.onGear(Gear.PARK, 40 * minute))
        assertTrue(d.state is DetectorState.ParkedPending)

        val trip = d.onDriverDoor(true)

        assertNotNull(trip)
        assertEquals(12_340f, trip!!.startOdoKm, 0f)
        assertEquals(12_372f, trip.endOdoKm, 0f)
        assertEquals(32f, trip.distanceKm, 0f)
        assertEquals(0L, trip.startAtMs)
        assertEquals(40 * minute, trip.endAtMs)
        assertEquals(DetectorState.Idle, d.state)
    }

    @Test
    fun `정차 후 다시 출발하면 같은 운행으로 이어진다`() {
        val d = TripDetector()
        d.onOdometer(100f)
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(110f)
        d.onGear(Gear.PARK, 10 * minute)      // 신호 대기나 잠깐 정차
        d.onGear(Gear.DRIVE, 12 * minute)
        d.onOdometer(125f)
        d.onGear(Gear.PARK, 30 * minute)

        val trip = d.onDriverDoor(true)

        assertEquals(25f, trip!!.distanceKm, 0f)
        assertEquals(0L, trip.startAtMs)
        assertEquals(30 * minute, trip.endAtMs)
    }

    @Test
    fun `문이 열리지 않아도 정차 대기 시간이 지나면 닫힌다`() {
        val d = TripDetector(parkTimeoutMs = 10 * minute)
        d.onOdometer(500f)
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(520f)
        d.onGear(Gear.PARK, 20 * minute)

        assertNull(d.onTick(29 * minute))
        val trip = d.onTick(30 * minute)

        assertEquals(20f, trip!!.distanceKm, 0f)
        assertEquals(20 * minute, trip.endAtMs)
    }

    @Test
    fun `짧은 이동도 계기판 거리가 늘었으면 운행으로 닫는다`() {
        val d = TripDetector()
        d.onOdometer(700f)
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(700.4f)
        d.onGear(Gear.PARK, 3 * minute)

        val trip = d.onDriverDoor(true)

        assertNotNull(trip)
        assertEquals(700f, trip!!.startOdoKm, 0f)
        assertEquals(700.4f, trip.endOdoKm, 0f)
        assertEquals(DetectorState.Idle, d.state)
    }

    @Test
    fun `계기판 거리가 늘지 않았으면 운행을 남기지 않는다`() {
        val d = TripDetector()
        d.onOdometer(700f)
        d.onGear(Gear.DRIVE, 0)
        d.onGear(Gear.PARK, 1 * minute)

        assertNull(d.onDriverDoor(true))
        assertEquals(DetectorState.Idle, d.state)
    }

    @Test
    fun `출발할 때 계기판 거리를 몰랐으면 처음 받은 값이 출발 거리다`() {
        val d = TripDetector()
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(9_000f)
        d.onOdometer(9_015f)
        d.onGear(Gear.PARK, 15 * minute)

        val trip = d.onDriverDoor(true)

        assertEquals(9_000f, trip!!.startOdoKm, 0f)
        assertEquals(15f, trip.distanceKm, 0f)
    }

    @Test
    fun `문이 열린 채로 P에 넣으면 바로 닫힌다`() {
        val d = TripDetector()
        d.onOdometer(1_000f)
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(1_010f)
        d.onDriverDoor(true)

        val trip = d.onGear(Gear.PARK, 20 * minute)

        assertEquals(10f, trip!!.distanceKm, 0f)
    }

    @Test
    fun `운행이 없을 때 문을 여닫거나 P 신호가 와도 아무 일도 없다`() {
        val d = TripDetector()
        d.onOdometer(50f)
        assertNull(d.onDriverDoor(true))
        assertNull(d.onDriverDoor(false))
        assertNull(d.onGear(Gear.PARK, 0))
        assertNull(d.onGear(Gear.UNKNOWN, 0))
        assertNull(d.onTick(60 * minute))
        assertEquals(DetectorState.Idle, d.state)
    }

    @Test
    fun `앱이 꺼졌다 켜져도 열려 있던 운행을 이어서 닫는다`() {
        val d = TripDetector()
        d.restore(startAtMs = 0, startOdoKm = 2_000f)     // 꺼지기 전에 저장해 둔 출발 정보
        assertTrue(d.state is DetectorState.Driving)
        d.onOdometer(2_045f)                               // 다시 켜진 뒤 들어온 현재 계기판 거리
        d.onGear(Gear.PARK, 90 * minute)

        val trip = d.onDriverDoor(true)

        assertEquals(45f, trip!!.distanceKm, 0f)
        assertEquals(0L, trip.startAtMs)
        assertTrue(trip.recovered)
    }

    @Test
    fun `복구 표시는 그 운행에만 붙는다`() {
        val d = TripDetector()
        d.restore(0, 100f)
        d.onOdometer(110f)
        d.onGear(Gear.PARK, 10 * minute)
        assertTrue(d.onDriverDoor(true)!!.recovered)
        d.onDriverDoor(false)

        d.onGear(Gear.DRIVE, 20 * minute)
        d.onOdometer(125f)
        d.onGear(Gear.PARK, 40 * minute)

        assertEquals(false, d.onDriverDoor(true)!!.recovered)
    }

    @Test
    fun `운행 중에는 복구 요청을 무시한다`() {
        val d = TripDetector()
        d.onOdometer(300f)
        d.onGear(Gear.DRIVE, 5 * minute)
        d.restore(0, 1f)

        assertEquals(DetectorState.Driving(5 * minute, 300f), d.state)
    }

    @Test
    fun `후진으로 출발해도 운행이 열린다`() {
        val d = TripDetector()
        d.onOdometer(300f)
        d.onGear(Gear.REVERSE, 0)
        d.onGear(Gear.DRIVE, 1 * minute)
        d.onOdometer(308f)
        d.onGear(Gear.PARK, 25 * minute)

        assertEquals(8f, d.onDriverDoor(true)!!.distanceKm, 0f)
    }

    @Test
    fun `정차 대기 시간을 바꾸면 그 시간에 맞춰 닫힌다`() {
        val d = TripDetector()
        d.parkTimeoutMs = 5 * minute
        d.onOdometer(100f)
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(120f)
        d.onGear(Gear.PARK, 20 * minute)

        assertNull(d.onTick(24 * minute))
        val trip = d.onTick(25 * minute)

        assertNotNull(trip)
        assertEquals(20 * minute, trip!!.endAtMs)
    }

    @Test
    fun `짧은 이동 뒤의 운행은 앞 운행이 끝난 계기판 거리에서 시작한다`() {
        val d = TripDetector()
        d.onOdometer(100f)
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(100.6f)
        d.onGear(Gear.PARK, 5 * minute)
        val short = d.onDriverDoor(true)
        d.onDriverDoor(false)

        d.onGear(Gear.DRIVE, 10 * minute)
        d.onOdometer(105f)
        d.onGear(Gear.PARK, 20 * minute)
        val trip = d.onDriverDoor(true)

        assertEquals(100.6f, short!!.endOdoKm, 0f)
        assertEquals(100.6f, trip!!.startOdoKm, 0f)
        assertEquals(105f, trip.endOdoKm, 0f)
    }

    @Test
    fun `처음 상태로 돌리면 열려 있던 운행을 버린다`() {
        val d = TripDetector()
        d.onOdometer(100f)
        d.onGear(Gear.DRIVE, 0)
        d.onOdometer(130f)
        d.onGear(Gear.PARK, 30 * minute)

        d.reset()

        assertEquals(DetectorState.Idle, d.state)
        assertNull(d.odometerKm)
        assertNull(d.onDriverDoor(true))
    }
}
