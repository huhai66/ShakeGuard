package com.example.shakeguard.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 全局设置（进程内单例）：UI 与无障碍服务共享同一份状态。
 *
 * 语义：masterEnabled = 总开关；enabledPackages = 用户勾选需要拦截广告的应用。
 * 默认：总开关关、应用列表为空 —— 装完什么都不做，由用户自己开、自己勾。
 */
class SkipSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("skip_settings", Context.MODE_PRIVATE)

    private val _masterEnabled = MutableStateFlow(prefs.getBoolean(KEY_MASTER, false))
    val masterEnabled: StateFlow<Boolean> = _masterEnabled

    private val _enabledPackages = MutableStateFlow(
        prefs.getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()
    )
    val enabledPackages: StateFlow<Set<String>> = _enabledPackages

    private val _skipCount = MutableStateFlow(prefs.getInt(KEY_COUNT, 0))
    val skipCount: StateFlow<Int> = _skipCount

    private val _debugLines = MutableStateFlow<List<String>>(emptyList())
    val debugLines: StateFlow<List<String>> = _debugLines

    /**
     * 无障碍事件线程与截图线程会同时调用，读-改-写必须原子，否则并发写入会互相覆盖丢日志。
     * 带时间戳是为了能分辨“哪条是广告出现那一刻的”，否则面板里全是上一屏的残留噪音。
     * 用 java.time 而非 SimpleDateFormat：后者非线程安全，正好撞上这里的多线程调用。
     */
    fun addDebugLine(line: String) {
        val stamped = "${LocalTime.now().format(TIME_FORMAT)} $line"
        _debugLines.update { (it + stamped).takeLast(30) }
    }

    fun clearDebugLines() {
        _debugLines.value = emptyList()
    }

    private val _imageRecognitionEnabled = MutableStateFlow(prefs.getBoolean(KEY_IMAGE_RECOG, false))
    val imageRecognitionEnabled: StateFlow<Boolean> = _imageRecognitionEnabled

    fun setImageRecognitionEnabled(enabled: Boolean) {
        _imageRecognitionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_IMAGE_RECOG, enabled).apply()
    }

    fun setMasterEnabled(enabled: Boolean) {
        _masterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_MASTER, enabled).apply()
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        val next = _enabledPackages.value.toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        _enabledPackages.value = next
        prefs.edit().putStringSet(KEY_ENABLED, next).apply()
    }

    fun setEnabledPackages(packages: Set<String>) {
        _enabledPackages.value = packages
        prefs.edit().putStringSet(KEY_ENABLED, packages).apply()
    }

    fun onSkipped() {
        _skipCount.value = _skipCount.value + 1
        prefs.edit().putInt(KEY_COUNT, _skipCount.value).apply()
    }

    private val _lastEventTime = MutableStateFlow(0L)
    val lastEventTime: StateFlow<Long> = _lastEventTime

    private var lastEventReport = 0L

    /**
     * 无障碍服务每收到一个事件就调用，用于判断服务是否存活。
     *
     * **必须节流**：微博这类应用每秒能发上百个事件，而每个事件都写一次 StateFlow 会在
     * 主线程（事件回调所在的线程）上唤醒 UI 侧的收集协程。UI 判断存活的阈值是 10 秒
     * （见 MainScreen 的 `nowTick - lastEvent < 10000`），1 秒粒度绰绰有余。
     */
    fun onEvent() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventReport < EVENT_REPORT_MS) return
        lastEventReport = now
        _lastEventTime.value = System.currentTimeMillis()
    }

    companion object {
        private const val KEY_MASTER = "master"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_COUNT = "count"
        private const val KEY_IMAGE_RECOG = "image_recognition"

        /** 「服务还活着」的刷新粒度，见 onEvent()。 */
        private const val EVENT_REPORT_MS = 1000L

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        @Volatile
        private var instance: SkipSettings? = null

        fun get(context: Context): SkipSettings =
            instance ?: synchronized(this) {
                instance ?: SkipSettings(context).also { instance = it }
            }
    }
}
