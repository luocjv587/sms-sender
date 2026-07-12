package com.example.smssender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SmsForwarderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                "forward_status",
                "短信转发状态",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "配置测试及短信转发结果"
            },
        )
    }
}
