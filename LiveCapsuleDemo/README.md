# 灵动胶囊 Demo · Android 16 实时通知（Live Updates）

一个最小化的 Android Demo，演示个人开发者**不需要任何厂商生态合作**，
如何让自己的应用出现在荣耀灵动胶囊（以及其它厂商的"灵动岛"式 UI）上。

方案与开源项目 [Kelivo](https://github.com/chevey339/kelivo) 完全一致：
使用 **Android 16（API 36）标准的 Live Updates / promoted ongoing notifications** API，
由系统 ROM 自动把这类常驻通知渲染成胶囊。

## 工作原理

```
常驻通知(ongoing) + Notification.ProgressStyle 进度样式
        + extra「android.requestPromotedOngoing = true」
        + 清单声明 android.permission.POST_PROMOTED_NOTIFICATIONS
                    ↓
      系统判定可以提升 → 打上 FLAG_PROMOTED_ONGOING
                    ↓
   荣耀 MagicOS 把它渲染为灵动胶囊（其他支持该特性的 ROM 类似）
```

关键代码在 [`app/src/main/java/com/demo/livecapsule/TaskNotifier.kt`](app/src/main/java/com/demo/livecapsule/TaskNotifier.kt) 的 `build()`：

```kotlin
val style = Notification.ProgressStyle()
    .addProgressSegment(Notification.ProgressStyle.Segment(100))
    .setStyledByProgress(true)
    .setProgress(progress)

Notification.Builder(context, CHANNEL_ID)
    .setSmallIcon(R.drawable.ic_task)
    .setContentTitle("模拟下载任务")
    .setStyle(style)
    .setOngoing(true)                    // 必须是 ongoing
    .addExtras(Bundle().apply {
        putBoolean("android.requestPromotedOngoing", true)  // 请求提升
    })
    .build()
```

配套 API：
- `NotificationManager.canPostPromotedNotifications()` —— 发布前检查资格
  （对应系统里该应用的"实时通知 / 灵动岛"开关）；
- `Notification.FLAG_PROMOTED_ONGOING` —— 发布后验证系统是否真的提升了它；
- `Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")` —— 跳转该开关的系统设置页。

未被提升时自动降级为普通常驻通知（带经典进度条）。

## 构建

命令行（本机已配好 JDK 17 + Android SDK，API 36 平台已装）：

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开本目录，点 Run。

> 注意：如果工程放在含中文/非 ASCII 字符的路径下，Windows 上 AGP 会直接报
> "Your project path contains non-ASCII characters" 拒绝构建。
> 请把工程移到纯英文路径，或在 `gradle.properties` 里加
> `android.overridePathCheck=true`（不保证 AAPT2 等工具链都正常）。

## 在荣耀手机上体验

1. 安装 APK 并打开，按提示授予**通知权限**；
2. 点「打开系统"实时通知 / 灵动岛"设置页」，把本应用的开关打开
   （就是系统设置 → 通知和状态栏 → 灵动胶囊 里对应本应用的那一项）；
3. 点「开始模拟任务」，通知栏出现下载进度通知，
   若系统成功提升，顶部灵动胶囊会显示该任务，下拉可见 ProgressStyle 进度条；
4. 应用内状态栏会实时显示 `FLAG_PROMOTED_ONGOING` 检测结果，可以确认是否真的上了胶囊。

## 已知限制

- 需要设备的系统基于 Android 16（API 36）且 ROM 支持实时通知；
  老 MagicOS 版本只会看到普通通知。
- 实时通知的内容形态是"标题 + 文本 + 进度 + 按钮"，做不了 12306 那种
  富场景卡片——那类效果仍要走荣耀 YOYO建议 / 智慧服务的生态合作。
- Android 官方目前不允许媒体/音乐类通知走这条通道（音乐请用 MediaSession 方案）。
- 本 Demo 没有前台服务；真实的长时间任务应像 Kelivo 一样配合
  `foregroundServiceType="dataSync"` 的前台服务使用，避免后台被杀。
