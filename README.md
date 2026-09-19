# ShakeGuard（AD tool）

一个**免 Root、免 adb、免 Shizuku** 的安卓广告自动跳过工具：开屏广告和弹窗里的「跳过 / 关闭」按钮，由无障碍服务自动帮你点掉。单 APK 侧载即可使用。

## 功能特性

| 功能 | 说明 |
|---|---|
| 自动跳过广告 | 识别「跳过 / 关闭 / × / 知道了 / 暂不」等按钮并自动点击 |
| 图像识别兜底 | 用 OpenCV 模板匹配识别屏幕上自定义绘制的「×」按钮（网页广告、无障碍读不到文案的场景） |
| 按应用管理 | 自主选择要处理哪些应用，支持搜索、全部勾选 / 全部不选 |
| 常驻与自启 | 前台服务保活 + 开机自启，降低被系统清理的概率 |

## 工作原理

应用依赖 Android **无障碍服务**监听窗口变化：

- 遍历当前窗口的控件树，匹配「跳过 / skip / 关闭 / close / ×」等文案或控件 id，命中后自动点击。对系统应用只点「跳过」，避免误触系统自身的删除、关闭等按钮。
- 若控件树读不到按钮（常见于网页广告里的自绘「×」），可选开启**图像识别**：截取屏幕后用 OpenCV 做模板匹配定位「×」并点击。

所有识别只在本地完成，**不采集、不上传任何界面信息**。

## 使用步骤

1. 安装 APK，打开应用。
2. 点击「无障碍服务」卡片右侧的按钮，在系统设置中开启本应用的无障碍服务。
3. 打开「自动跳过广告」总开关。
4. 在应用列表里勾选需要处理的应用（或点「全部勾选」）。
5. 按需开启「图像识别」。

> 建议：收到「允许后台运行」提示时点「去允许」，避免服务被系统回收导致拦截失效。

## 各功能默认值

| 开关 | 默认 |
|---|---|
| 自动跳过广告（总开关） | 关 |
| 图像识别 | 关（Android 11+ 可用） |

图像识别可能误点屏幕上其他形似「×」的内容，故默认关闭，谨慎开启。

## 环境与构建

| 项 | 要求 |
|---|---|
| 系统 | Android 8.0 及以上（minSdk 26）；图像识别需 Android 11+ |
| JDK | 17 |
| 构建 | Android Studio，或命令行 Gradle 8.9 |

依赖版本已固定：AGP 8.5.2 / Gradle 8.9 / Kotlin 2.0.21 / compileSdk 34 / OpenCV 4.9.0。

用 Android Studio 打开本目录同步后 Run 到真机即可。命令行构建：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 目录结构

```
app/src/main/java/com/example/shakeguard/
├── MainActivity.kt
├── data/
│   ├── AppInfo.kt              # 应用信息模型
│   ├── AppRepository.kt        # 查询可启动应用
│   └── SkipSettings.kt         # 全局设置（总开关/图像识别/应用列表/跳过计数）
├── receiver/
│   └── BootReceiver.kt         # 开机自启
├── service/
│   ├── ShakeGuardAccessibilityService.kt  # 核心：识别并点击跳过/关闭按钮
│   └── KeepAliveService.kt     # 前台常驻服务（运行中通知）
├── util/
│   ├── AccessibilityUtil.kt    # 无障碍开关状态判断
│   ├── AppIcon.kt              # 懒加载应用图标
│   ├── ImageRecognizer.kt      # OpenCV 模板匹配识别「×」
│   └── KeepAliveManager.kt     # 按开关启停前台服务
└── ui/
    ├── MainViewModel.kt
    ├── MainScreen.kt           # 主界面（状态/开关/应用列表/调试信息）
    └── theme/Theme.kt
```

## 权限说明

| 权限 | 用途 |
|---|---|
| 无障碍服务 | 读取窗口内容、自动点击（核心） |
| `QUERY_ALL_PACKAGES` | 列出可启动应用以构建选择列表 |
| `FOREGROUND_SERVICE` | 常驻服务保活 |
| `POST_NOTIFICATIONS` | 显示「运行中」通知（Android 13+） |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请加入电池优化白名单 |

## 已知限制

- 依赖无障碍服务，个别国产 ROM 还需额外授予「自启动 / 允许后台运行 / 忽略电池优化」才能持续生效（界面已留入口）。
- 部分 App 把「跳过」做成自定义绘制、无障碍读不到文案：文本匹配点不到时，可开启图像识别兜底（Android 11+）。
- 图像识别基于模板匹配，存在误点风险，且较耗电/耗时，默认关闭。
- `QUERY_ALL_PACKAGES` 在 Google Play 受限制，本工具定位为个人自用 / 侧载。
