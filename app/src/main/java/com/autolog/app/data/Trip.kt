package com.autolog.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 운행의 용무. 운행기록부 서식의 출퇴근용·일반 업무용 구분에 대응한다. */
enum class Purpose(val label: String) {
    UNCLASSIFIED("미분류"),
    BUSINESS_VISIT("거래처 방문"),
    COMMUTE("출퇴근"),
    BUSINESS_GENERAL("일반 업무"),
    PRIVATE("사적");

    /** 업무용 사용거리에 들어가는가. 출퇴근도 업무용 사용거리에 포함된다. */
    val isBusiness: Boolean get() = this == BUSINESS_VISIT || this == COMMUTE || this == BUSINESS_GENERAL

    companion object {
        /** 용무 카드에 버튼으로 내놓는 선택지. */
        val choices = listOf(BUSINESS_VISIT, COMMUTE, BUSINESS_GENERAL, PRIVATE)

        /** 이름을 바꿀 수 있는 버튼. 둘 다 서식의 일반 업무용 거리에 들어간다. 출퇴근과 사적은 서식의 구분이라 바꾸지 않는다. */
        val renamable = listOf(BUSINESS_VISIT, BUSINESS_GENERAL)
    }
}

/** 운행 한 건. 운행기록부 한 줄이 된다. */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAtMs: Long,
    val endAtMs: Long,
    /** 주행 전 계기판 거리 */
    val startOdoKm: Float,
    /** 주행 후 계기판 거리 */
    val endOdoKm: Float,
    val distanceKm: Float,
    val purpose: Purpose = Purpose.UNCLASSIFIED,
    val note: String = "",
    /** AUTO: 차량 신호로 자동 기록, RECOVERED: 앱이 꺼졌다 켜진 뒤 이어서 닫음, MANUAL: 손으로 추가, DEMO: 시연용 예시 */
    val source: String = SOURCE_AUTO,
    /** 사용자(부서·성명). 운행이 닫힐 때의 운전자이며 카드에서 바꿀 수 있다. */
    val driverName: String = "",
    val driverDept: String = "",
    /** 출발지: 운행이 열릴 때의 주소 */
    val startPlace: String = "",
    /** 도착지: 내비 목적지 이름, 없으면 정차한 곳의 주소 */
    val endPlace: String = "",
    /** 출발한 곳과 정차한 곳의 좌표. 저장한 장소를 찾는 데 쓴다. 모르면 null */
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    /** 도착지 이름이 내비 목적지에서 왔는가 */
    val endFromNavi: Boolean = false,
    /** 합치거나 나눠서 생긴 운행이면, 이 운행이 대신하는 원래 운행의 id들(쉼표로 구분). 되돌릴 때 쓴다. */
    val replaces: String = "",
    /** 지운 표시. 목록과 운행기록부에서 빠지지만 행은 남는다. */
    val deleted: Boolean = false,
    /** 왜 지운 표시가 붙었는가. 사용자가 지운 것만 '지운 운행'에서 되살린다. */
    val deletedReason: String = "",
) {
    companion object {
        const val SOURCE_AUTO = "AUTO"
        const val SOURCE_RECOVERED = "RECOVERED"
        const val SOURCE_DEMO = "DEMO"
        /** 사용자가 손으로 추가한 운행 */
        const val SOURCE_MANUAL = "MANUAL"
        /** 두 운행을 합쳐 만든 운행 */
        const val SOURCE_MERGED = "MERGED"
        /** 한 운행을 나눠 만든 운행 */
        const val SOURCE_SPLIT = "SPLIT"

        /** 사용자가 '이 운행 삭제'로 지움 */
        const val DELETED_BY_USER = "USER"
        /** 합치거나 나눠서 다른 운행으로 바뀜 */
        const val DELETED_BY_EDIT = "EDIT"
        /** 합치기나 나누기를 되돌리면서 사라짐 */
        const val DELETED_BY_UNDO = "UNDO"
    }
}
