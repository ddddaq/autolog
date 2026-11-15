package com.autolog.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.autolog.app.data.AppDatabase
import com.autolog.app.data.ProfileStore
import com.autolog.app.data.Purpose
import com.autolog.app.data.TripDao
import com.autolog.app.place.PlaceStore
import com.autolog.app.report.LogbookExporter
import com.autolog.app.share.PhoneShare
import com.autolog.app.trip.Ledger
import com.autolog.app.trip.OpenTripStore
import com.autolog.app.trip.TripRecorder
import com.autolog.app.trip.TripRecorderService
import com.autolog.app.ui.directionParticle
import com.autolog.app.ui.speechKm
import com.autolog.app.vehicle.DrivingRestriction
import com.autolog.app.vehicle.NaviPlace
import com.autolog.app.vehicle.VehicleSignals
import com.autolog.app.vehicle.VoiceGuide
import com.autolog.app.vehicle.VoiceMemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱 프로세스가 살아 있는 동안 운행 기록이 이어지도록, 기록기를 화면이 아니라 Application에 둔다.
 * 화면이 다른 앱에 가려져도 프로세스가 남아 있으면 운행은 계속 잡힌다.
 *
 * 차량 신호와 위치, 음성은 사용자가 기록 항목에 동의한 뒤에만 읽는다.
 */
class AutoLogApp : Application() {

    lateinit var trips: TripDao
        private set
    lateinit var profiles: ProfileStore
        private set
    lateinit var recorder: TripRecorder
        private set
    lateinit var restriction: DrivingRestriction
        private set
    lateinit var voiceMemo: VoiceMemo
        private set
    lateinit var exporter: LogbookExporter
        private set
    lateinit var placeStore: PlaceStore
        private set
    lateinit var navi: NaviPlace
        private set
    lateinit var phoneShare: PhoneShare
        private set

    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, AppDatabase::class.java, "autolog.db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        trips = database.tripDao()
        profiles = ProfileStore(this)
        restriction = DrivingRestriction(this).also { it.start() }
        val voice = VoiceGuide(this)
        voiceMemo = VoiceMemo(this)
        exporter = LogbookExporter(this)
        placeStore = PlaceStore(this)
        navi = NaviPlace(this)
        phoneShare = PhoneShare(log = { Log.i("AUTOLOG", it) })
        recorder = TripRecorder(
            signals = VehicleSignals(this),
            places = navi,
            dao = trips,
            scope = scope,
            openTrips = OpenTripStore(this),
            currentDriver = { profiles.profile.value.driver },
            onTripClosed = { trip ->
                val profile = profiles.profile.value
                // 짧은 이동은 용무를 묻지 않으므로 말하지 않는다.
                if (profile.voiceGuide && !Ledger.isShortMove(trip, profile.minTripKm.toFloat())) {
                    val label = profile.labelOf(trip.purpose)
                    voice.speak(
                        if (trip.purpose == Purpose.UNCLASSIFIED) "${speechKm(trip.distanceKm)}킬로미터 운행을 기록했습니다. 용무는 다음에 골라도 됩니다."
                        else "${speechKm(trip.distanceKm)}킬로미터 운행을 $label${directionParticle(label)} 기록했습니다."
                    )
                }
            },
            onParked = { trip ->
                if (profiles.profile.value.voiceGuide) voice.speak("${speechKm(trip.distanceKm)}킬로미터를 달렸습니다. 내리기 전에 용무를 골라 주세요.")
            },
        )
        // 동의한 뒤에만 기록을 시작한다. 설정이 바뀌면 운행을 닫는 기준도 따라 바꾼다.
        scope.launch {
            var recording: Boolean? = null
            profiles.profile.collect { profile ->
                recorder.configure(profile.parkTimeoutMin * 60_000L, profile.minTripKm.toFloat())
                if (profile.consented == recording) return@collect
                recording = profile.consented
                if (profile.consented) {
                    voice.start()
                    voiceMemo.start()
                    recorder.start()
                    TripRecorderService.start(this@AutoLogApp)
                } else {
                    voiceMemo.cancel()
                    phoneShare.close()
                    recorder.stop()
                    TripRecorderService.stop(this@AutoLogApp)
                }
            }
        }
    }

    /** 운행 기록, 내보낸 파일, 차량·운전자 정보, 이름 붙인 장소, 설정, 동의를 모두 지운다. 이후 첫 실행 안내부터 다시 시작한다. */
    suspend fun wipeAll() {
        phoneShare.close()
        recorder.stop()
        withContext(Dispatchers.IO) {
            trips.deleteAll()
            exporter.deleteAll()
        }
        placeStore.reset()
        profiles.reset()
    }
}
