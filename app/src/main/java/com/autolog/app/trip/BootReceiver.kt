package com.autolog.app.trip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autolog.app.AutoLogApp

/**
 * 차량이 켜진 뒤(부팅 완료)와 앱이 업데이트된 뒤에 운행 기록을 다시 시작한다.
 * 운전자가 앱을 열지 않아도 첫 운행부터 기록된다. 동의하지 않은 상태에서는 아무것도 하지 않는다.
 *
 * 기록기는 Application이 프로세스 시작과 함께 띄우므로, 여기서는 프로세스가 동결되지 않도록 서비스만 올린다.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? AutoLogApp ?: return
        if (!app.profiles.profile.value.consented) return
        Log.i("AUTOLOG", "자동 시작: $action")
        TripRecorderService.start(context)
    }
}
