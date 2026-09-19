package com.example.shakeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.shakeguard.util.KeepAliveManager

/** 开机后自动拉起前台服务（前提：已开启无障碍并授予自启动权限）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            KeepAliveManager.update(context)
        }
    }
}
