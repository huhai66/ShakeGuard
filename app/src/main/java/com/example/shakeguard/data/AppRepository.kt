package com.example.shakeguard.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    /** 查询所有带桌面启动入口的应用（即用户能点开的 App）。 */
    suspend fun loadLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo != null }
            .map { resolveInfo ->
                val ai: ApplicationInfo = resolveInfo.activityInfo.applicationInfo
                AppInfo(
                    packageName = ai.packageName,
                    label = resolveInfo.loadLabel(pm).toString(),
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
