package com.example.shakeguard.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.example.shakeguard.service.ShakeGuardAccessibilityService

object AccessibilityUtil {

    /**
     * 用 ComponentName 精确比较，兼容系统存储的“全限定类名”与“短类名”两种格式。
     * 之前用字符串拼接 ".service.XXX" 判断，在多数机型上会误报“未开启”。
     */
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ShakeGuardAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { raw ->
            ComponentName.unflattenFromString(raw) == expected
        }
    }
}
