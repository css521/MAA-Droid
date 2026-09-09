# 通用引擎设备会话接入

入口为 `app/.../engine/EngineDeviceSession.kt`。资源下载、兼容检查和 `engine.prepare(resourceDir)` 仍由宿主负责。本 helper 不改变 Root/Shizuku 后端选择和授权，也不设置资源目录。

## EngineSession 接入示例

在原有资源门闸和 `engine.prepare(...)` 成功之后，用以下逻辑替换直接创建 `RemoteDeviceHandle` 的部分。`profile` 来自当前 provider；运行模式传入当前 `appSettings.runMode.value`。

```kotlin
private var deviceSession: EngineDeviceSession? = null

// 必须与 stop/close 串行；再次 prepare 前先停止并关闭上次会话。
RemoteServiceManager.useRemoteService { service ->
    val device = EngineDeviceSession.open(
        profile = profile,
        service = service,
        mode = runMode, // appSettings.runMode.value 的本次启动快照
        firstFrameTimeoutMs = 10_000,
        framePollIntervalMs = 50,
    )
    // 先登记所有权；外层后续 appendTask/start 失败也能收尾。
    deviceSession = device
    device.connect(engine).getOrThrow()
}
```

`open` 抛出包含原因的异常；协程取消仍抛 `CancellationException`，不能转成成功或普通启动错误。`connect` 返回 `Result<Unit>`，失败自动关闭设备，取消自动关闭并继续抛出。`deviceSession.device` 是 `DeviceHandle`，`packageName`、`displayId` 为实际选择结果。

停止和所有终态统一走：

```kotlin
suspend fun stopAndRelease(engine: AutomationEngine): Boolean =
    withContext(NonCancellable + Dispatchers.IO) {
        val device = deviceSession
        try {
            // stop(engine) 先等待 engine.stop() 返回，再在 finally 关闭设备。
            device?.stop(engine) ?: engine.stop()
        } finally {
            deviceSession = null
        }
    }
```

## 宿主清理契约

- `prepare/start/stop/close` 由宿主同一把生命周期锁串行化；包括方舟与通用引擎间的启动互斥。底层只有一个 native capturer，同时只允许一个设备会话。
- `open` 中途失败、首帧超时、尺寸错误、取消、引擎 connect 失败会自动回收。后续 `appendTask` 失败、`start()` 返回 false、任务完成、运行异常、主动停止、切换引擎、服务断开和 VM 销毁，宿主都必须进入停止/关闭收尾。
- 必须先停止并等待所有识别/输入协程退出，再关闭映射；`Frame.buffer` 不得越过 close 使用。当前 LimbusEngine.stop 会 join 运行 job。VM 清理应在可以完成收尾的 scope 中执行，不能只在已取消的 viewModelScope 中 launch。
- 单独的 `close()` 是同步、幂等的设备释放，不会替宿主停止引擎。服务断开时也应调用它以解除 App 侧映射；远程 Binder 错误可记录后继续清理宿主引用。
- 完成事件应交给独立宿主协程收尾，不能在引擎运行 job 自身调用会 join 自身的 stop。
- close 后旧设备 Binder 拒绝帧/输入/应用控制；再次运行必须新建 helper 并 reconnect。不要继续使用旧 `RemoteDeviceHandle`，不要用全局 `stopVirtualDisplay()` / `closeFrameChannel()` 来关闭通用会话。
- 保留父工程 `RemoteServiceManager.useRemoteService` 和连接时宿主权限初始化路径；不要为了通用引擎调用 `MaaResourceLoader` 或旧 `setup(userDir, isDebug)`。旧 setup 仍保留用户目录校验和 `RemoteEngineRegistry.setupAll` 行为。

## 设备链路与边界

`setupDevice()` → 按 `profile.gamePackages` 顺序选择首个已安装包 → 独占创建 `profile.display` 指定尺寸/DPI 的虚拟显示及 native capture → 与 MaaCompositionService 相同的游戏电池/后台权限申请 → `ActivityUtils.startApp(packageName, displayId)` → 等待实际 BGR 帧。

后台启动复用现有 display flags、Root/Shizuku 系统服务封装和捕获实现。前台旧输入 API 会直接返回，故本 helper 明确拒绝前台，既不调整主显示，也不返回假成功。游戏权限仍采用旧链路的 best-effort 策略。

首帧校验包括实际帧序号、宽高、BGR 行距、映射容量；不把共享内存分配成功当作截图成功。默认 10 秒只约束首帧轮询，不能打断阻塞中的 Android Binder 调用。首帧就绪不代表游戏登录、加载或任务页面已就绪。

新 RemoteService 事务仅追加 `setupDevice = 57`、`openDeviceSession = 58`。独立 `IEngineDeviceSession` Binder 持有自己的 FrameChannel，带 App Binder 死亡回收。全局旧显示/输入变更在通用会话占用期间被拒绝；旧帧通道与新帧通道相互独立。会话关闭不执行全局静音恢复、断网恢复或主屏恢复。

真实设备未验证：当前没有可用 adb 设备。JVM 测试与编译不能证明 OEM ROM 上的虚拟显示、Root/Shizuku 捕获和输入可用。

本次验证：`:core:remote:compileDebugKotlin` 通过；`:core:remote:testDebugUnitTest --tests '*DeviceSessionLeaseTest' --tests '*BgrFrameLayoutTest'` 的 6 个用例通过。app helper 与 `EngineDeviceSessionTest` 使用本地 Kotlin 编译器、实际模块 classpath 和 JDK 17 独立编译，5 个 JUnit 用例通过。未执行全量 Gradle 或 app 全量构建。
