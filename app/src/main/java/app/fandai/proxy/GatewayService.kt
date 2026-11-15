package app.fandai.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD

class GatewayService : Service() {
    private var server: NanoHTTPD? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("反代 AI API")
                .setContentText("本地网关正在运行")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("反代 AI API")
                .setContentText("本地网关正在运行")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)

        server = object : NanoHTTPD("127.0.0.1", PORT) {
            override fun serve(session: IHTTPSession): Response {
                return when (session.uri) {
                    "/health" -> newFixedLengthResponse(
                        Response.Status.OK, "application/json",
                        """{"status":"ok","service":"fandai-ai-api"}"""
                    )
                    "/v1/models" -> newFixedLengthResponse(
                        Response.Status.OK, "application/json",
                        """{"object":"list","data":[]}"""
                    )
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "application/json",
                        """{"error":{"message":"route not found","type":"not_found"}}"""
                    )
                }
            }
        }.also { it.start(SOCKET_READ_TIMEOUT, false) }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "API 网关", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val PORT = 8765
        private const val SOCKET_READ_TIMEOUT = 5_000
        private const val CHANNEL_ID = "gateway"
        private const val NOTIFICATION_ID = 8765
    }
}
