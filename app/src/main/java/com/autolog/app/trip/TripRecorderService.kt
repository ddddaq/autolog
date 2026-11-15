package com.autolog.app.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.autolog.app.R
import com.autolog.app.ui.MainActivity

/**
 * 운행 기록이 화면과 무관하게 이어지도록 앱 프로세스를 포그라운드 서비스 상태로 둔다.
 *
 * 주행 중에는 보통 내비 같은 다른 앱이 화면을 차지한다. 그러면 이 앱은 뒤로 물러나고, 시스템이 곧 프로세스를
 * 동결해 차량 신호를 받지 못한다. 포그라운드 서비스가 떠 있으면 동결되지 않는다.
 * 기록기([TripRecorder]) 자체는 Application이 갖고 있고, 이 서비스는 프로세스를 살려 두는 일만 한다.
 */
class TripRecorderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "운행 기록 서비스 시작")
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "운행 기록", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "차량 신호로 운행을 기록하는 동안 표시됩니다"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_autolog)
            .setContentTitle("AutoLog")
            .setContentText("운행을 자동으로 기록하고 있습니다")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AUTOLOG"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1

        /**
         * 화면이 떠 있을 때와 부팅 직후에 부른다. 그 밖의 백그라운드에서는 시스템이 시작을 막을 수 있어 실패해도 넘어간다.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TripRecorderService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "운행 기록 서비스를 시작하지 못함: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TripRecorderService::class.java)) }
        }
    }
}
