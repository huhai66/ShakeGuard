package com.example.shakeguard.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.shakeguard.data.SkipSettings
import com.example.shakeguard.service.KeepAliveService

/** 根据当前开关状态启动/停止前台服务。 */
object KeepAliveManager {

    fun update(context: Context) {
        val settings = SkipSettings.get(context)
        // 现在只有总开关决定要不要保活：拉回功能已移除，没有第二个理由让服务常驻
        val shouldRun = settings.masterEnabled.value
        val intent = Intent(context, KeepAliveService::class.java)
        if (shouldRun) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
    }
}
