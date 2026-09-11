# LALC 源码资源安装

Koin 注册单例（HttpClientHelper 与 ResourceDownloader 复用现有实例）：

```kotlin
single { EngineResourceService(androidContext(), get(), get()) }
```

UI 观察每个包的 StateFlow；无需传入 CoroutineScope，调用方负责生命周期：

```kotlin
val state by resourceService.state(LimbusResourcePack).collectAsStateWithLifecycle()
// state.phase: NOT_INSTALLED / CHECKING / DOWNLOADING / INSTALLING / READY / FAILED
// state.download: progress、speed、downloaded、total（total=0 表示服务器未提供大小）
// state.filesInstalled / filesTotal: 解包进度
// state.installedRevision: 上游 tag + 固定的 40 位 commit
// state.installedVersion: 全部文件 path:sha256 的稳定摘要
// state.availableRevision / error: 待更新版本 / 失败原因
```

页面初始版本使用 `resourceService.refreshInstalled(pack)`：返回
`Result<EngineResourceState>` 并刷新同一 StateFlow，仅验证本地文件，不访问网络，
不恢复/删除暂存目录、不创建安装目录。缺资源为 NOT_INSTALLED，校验失败为 FAILED。

开始 EngineSession 前调用：

```kotlin
val directory = resourceService.ensureInstalled(pack).getOrThrow()
```

`ensureInstalled` 校验已安装文件；有效时完全离线，无资源时直接下载上游
`HSLix/LixAssistantLimbusCompany` 的 v5.0.0 固定 commit
`431b432e22f0b0da08b95d7c478fa213be20b3e8`，不访问 tags API。
返回目录与 `EngineDataRoot.forPack(context, pack)` 一致。

Session 持锁顺序：先 ensureInstalled，再 acquire；取得运行锁后再读目录和 manifest、
校验兼容性并 prepare，锁直到 stop/close 后 finally 释放。

```kotlin
resourceService.ensureInstalled(pack).getOrThrow()
val lease = ResourcePackLocks.acquire(pack.packId)
try {
    val directory = EngineDataRoot.forPack(context, pack)
    pack.verifyInstalledFiles(directory)?.let { error(it) }
    // engine.prepare(... directory ...)，持续持有 lease，直至本次运行结束
} finally {
    lease.close()
}
```

不要在已持有同一 pack 锁时调用资源服务（Mutex 不可重入）。资源服务自身在网络、暂存校验、
替换、恢复期间持锁。`update` / `checkForUpdate` / `refreshInstalled` 使用 tryAcquire，
占锁时立即返回 `Result.failure(ResourcePackBusyException)`，状态为 FAILED 且 error 提示资源忙；
不发网络，不停留在 CHECKING 等待。`ensureInstalled` 可以等待锁，再在锁内验证/安装。
同一 App 进程必须共用此 ResourcePackLocks；不支持多个独立进程并行写同一资源目录。

UpdateService 对 `pack.upstreamArchive != null` 的包调用：

```kotlin
val candidate = resourceService.checkForUpdate(pack).getOrThrow() // 仅检查
if (candidate != null) resourceService.update(pack).getOrThrow() // 再解析固定 SHA 并安装
```

宿主服务从 `pack.upstreamArchive` 读取 repository、SHA、归档布局，并按其 repository 校验
已安装清单。当前只有 LimbusResourcePack 声明源码资源源；其他包继续现有更新链路。
检查更新扫描 tags 分页（最多 20 页），按稳定
SemVer 数值排序；忽略 prerelease，拒绝同版本不同 SHA、无效 SHA 和不完整分页。
GitHub 匿名 403/429 明确返回失败，保留旧资源和状态中的已安装版本；没有 token 或备用 Release。

安装目录只保留五个资源目录及 manifest.json。临时收集 backend Python 文件的动作声明，
只读文本、不执行；无法解析的注册方式、未知 handler、未知纯路由均拒绝。
清单包含每个文件的 SHA-256/size、内容 revision、upstream 和 required_actions。
校验复用 PipelineRegistry，核对模板引用、PNG 签名、语言 JSON、OCR det/rec、三种分类模型
及其元数据，并拒绝 Git LFS 指针。ONNX 图的实际加载/推理仍由引擎在设备上验证。

验证后的同级暂存目录通过备份 + rename 切换，失败回滚；进程在切换中断后，下一次操作恢复
.previous。读取者遵循 pack 锁即可避免观察到两次 rename 之间的空窗。
