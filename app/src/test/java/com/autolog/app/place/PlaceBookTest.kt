package com.autolog.app.place

import com.autolog.app.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceBookTest {

    // 판교역 근처의 두 지점. 약 100m와 약 1km 떨어져 있다.
    private val lat = 37.3947
    private val lng = 127.1112
    private val near = 37.3956 to 127.1112     // 북쪽으로 약 100m
    private val far = 37.4037 to 127.1112      // 북쪽으로 약 1km

    private fun here(raw: String = "분당구 백현동") = PlaceBook.Endpoint(raw, lat, lng, byCoordinates = true)

    @Test
    fun `두 좌표 사이의 거리를 미터로 잰다`() {
        assertEquals(100.0, PlaceBook.distanceM(lat, lng, near.first, near.second), 5.0)
        assertEquals(1000.0, PlaceBook.distanceM(lat, lng, far.first, far.second), 10.0)
        assertEquals(0.0, PlaceBook.distanceM(lat, lng, lat, lng), 0.001)
    }

    @Test
    fun `이름을 붙인 자리 근처에서 출발하면 그 이름으로 읽는다`() {
        val places = PlaceBook.named(emptyList(), here(), "회사")
        assertEquals("회사", PlaceBook.label(PlaceBook.Endpoint("분당구 백현동", near.first, near.second, true), places))
    }

    @Test
    fun `주소 글자가 같아도 멀리 떨어진 곳은 다른 곳이다`() {
        val places = PlaceBook.named(emptyList(), here(), "회사")
        val elsewhere = PlaceBook.Endpoint("분당구 백현동", far.first, far.second, true)
        assertNull(PlaceBook.find(elsewhere, places))
        assertEquals("분당구 백현동", PlaceBook.label(elsewhere, places))
    }

    @Test
    fun `내비 목적지 이름은 좌표가 아니라 이름으로 찾는다`() {
        // 회사 자리에 서 있지만 도착지는 내비 목적지 '42dot'이다. 회사로 읽으면 안 된다.
        val places = PlaceBook.named(emptyList(), here(), "회사")
        val destination = PlaceBook.Endpoint("42dot", lat, lng, byCoordinates = false)
        assertEquals("42dot", PlaceBook.label(destination, places))

        val renamed = PlaceBook.named(places, destination, "한빛상사 본점")
        assertEquals("한빛상사 본점", PlaceBook.label(destination, renamed))
        assertEquals("회사", PlaceBook.label(here(), renamed))
    }

    @Test
    fun `좌표를 모르는 지난 운행은 원래 이름으로 찾는다`() {
        val places = PlaceBook.named(emptyList(), here(), "회사")
        assertEquals("회사", PlaceBook.label(PlaceBook.Endpoint("분당구 백현동", null, null, true), places))
        assertEquals("수정구 시흥동", PlaceBook.label(PlaceBook.Endpoint("수정구 시흥동", null, null, true), places))
    }

    @Test
    fun `이미 이름이 있는 곳에 다른 이름을 붙이면 이름이 바뀐다`() {
        val first = PlaceBook.named(emptyList(), here(), "회사")
        val second = PlaceBook.named(first, here(), "집")
        assertEquals(listOf("집"), second.map { it.name })
        assertEquals("집", PlaceBook.label(here(), second))
    }

    @Test
    fun `같은 이름을 다른 곳에 붙이면 한 장소로 합친다`() {
        val first = PlaceBook.named(emptyList(), here(), "회사")
        val destination = PlaceBook.Endpoint("회사 주차장", null, null, byCoordinates = false)
        val second = PlaceBook.named(first, destination, "회사")
        assertEquals(1, second.size)
        assertEquals(setOf("분당구 백현동", "회사 주차장"), second.single().rawNames)
        assertEquals(lat, second.single().latitude!!, 0.0)
    }

    @Test
    fun `이름을 떼면 원래 이름으로 돌아간다`() {
        val places = PlaceBook.named(emptyList(), here(), "회사")
        val removed = PlaceBook.unnamed(places, here())
        assertTrue(removed.isEmpty())
        assertEquals("분당구 백현동", PlaceBook.label(here(), removed))
    }

    @Test
    fun `운행 목록의 출발지와 도착지를 저장한 이름으로 바꿔 읽는다`() {
        val places = PlaceBook.named(emptyList(), here(), "회사")
        val trips = listOf(
            Trip(id = 1, startAtMs = 0, endAtMs = 1, startOdoKm = 0f, endOdoKm = 32f, distanceKm = 32f,
                startPlace = "분당구 백현동", endPlace = "42dot", startLat = lat, startLng = lng, endLat = lat, endLng = lng, endFromNavi = true),
            Trip(id = 2, startAtMs = 2, endAtMs = 3, startOdoKm = 32f, endOdoKm = 50f, distanceKm = 18f,
                startPlace = "수정구 시흥동", endPlace = "분당구 백현동", startLat = far.first, startLng = far.second, endLat = near.first, endLng = near.second),
        )
        val shown = PlaceBook.resolve(trips, places)
        assertEquals("회사" to "42dot", shown[0].startPlace to shown[0].endPlace)
        assertEquals("수정구 시흥동" to "회사", shown[1].startPlace to shown[1].endPlace)
        // 원래 기록은 그대로다.
        assertEquals("분당구 백현동", trips[0].startPlace)
    }

    @Test
    fun `좌표 없이 붙여 둔 이름은 좌표가 있는 새 운행에도 원래 이름으로 이어진다`() {
        // 좌표를 남기기 전의 운행에서 '회사'라고 붙였다.
        val legacy = PlaceBook.named(emptyList(), PlaceBook.Endpoint("분당구 백현동", null, null, true), "회사")
        assertNull(legacy.single().latitude)
        // 좌표가 있는 새 운행도 같은 주소면 회사로 읽는다.
        assertEquals("회사", PlaceBook.label(here(), legacy))
        // 새 운행에서 다시 이름을 붙이면 그 장소에 좌표가 채워진다.
        val filled = PlaceBook.named(legacy, here(), "회사")
        assertEquals(1, filled.size)
        assertEquals(lat, filled.single().latitude!!, 0.0)
        // 좌표가 채워진 뒤에는 멀리 떨어진 같은 주소를 회사로 읽지 않는다.
        assertEquals("분당구 백현동", PlaceBook.label(PlaceBook.Endpoint("분당구 백현동", far.first, far.second, true), filled))
    }
}
