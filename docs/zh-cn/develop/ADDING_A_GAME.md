# 新增一个游戏引擎

本指南以当前边狱使用的通用任务页为入口。接入范围包括引擎模块、App 装配、资源来源、配置与设备生命周期；不要求复制方舟业务。方舟现有任务页通过 `MaaCompositionService` 为每轮任务创建独立 `ArknightsEngine`，已使用引擎生命周期契约；任务页和部分业务仍在宿主，不能将它的旧入口当作已完成的通用示例。

开始前先读[架构说明](ARCHITECTURE.md)。下文的 `newgame`、`NewGameProfile` 等是新模块的示例命名，不代表已有游戏实现。

## 1. 确定执行位置与资源边界

先固定实际 Android 包名、目标分辨率/DPI、支持的语言、资源来源及许可。上游桌面截图或相似目录结构不能证明 Android 模板可以直接使用，应先验证取帧尺寸、画面内容及一组最小识别/输入动作。

当前最直接的路线是在 App 进程执行业务，通过 [DeviceHandle](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/AutomationEngine.kt) 使用提权服务提供的帧、输入和应用控制。OpenCV / ONNX Runtime 等 Android Java/JNI 库采用此路线。只有确需自有提权引擎服务时，才增加本模块 AIDL、代理与 `RemoteEngineFactory`，见第 6 步。

`EngineDeviceSession.open` 当前只接受后台运行模式，不要给尚无适配的新引擎承诺前台运行。资源包可以是零个、一个或多个；宿主把全部目录按包 ID 传入 `EngineResources`，引擎按自己声明的包读取，无需猜测主目录或把不同包合并到一个目录。

## 2. 建立模块与宿主依赖

参照 [engine/limbus/build.gradle.kts](../../../engine/limbus/build.gradle.kts) 建立 `engine/newgame/` Android library，使用项目当前 SDK、Java/Kotlin 及依赖版本约定。模块至少依赖 `:engine:api`；有 Compose 页面时启用相应插件与构建功能，需要序列化或原生库时再声明依赖。不要为了编译新模块引入 `:app` 或其他游戏引擎。

接线需要改两处构建声明：

```kotlin
// settings.gradle.kts
include(":engine:newgame")

// app/build.gradle.kts 的 dependencies
implementation(project(":engine:newgame"))
```

资源字符串、图标、consumer rules 由新模块持有。可以使用 `core:ui` 的共用组件；游戏专属的资源解析、配置类型和界面应留在引擎。`ModuleBoundaryContractTest` 会从目录发现新模块，不应通过放宽禁止依赖来绕过它。

新增 native 依赖时核对支持 ABI、JNI 加载位置及同名 `.so`。项目已有 MaaCore 与 ONNX Runtime 的符号兼容检查；不要用 `pickFirst` 掩盖冲突。更新[第三方代码声明](THIRD_PARTY_NOTICES.md)，区分库许可证、模型来源与游戏图片权利。

## 3. 实现 profile、UI 与 provider

[GameProfile](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/GameProfile.kt) 中各字段都要由实际游戏需求确定：

| 字段 | 实施要求 |
|---|---|
| `id` | 唯一且稳定；用于注册、偏好键和诊断。新增内建游戏按现有约定在 `EngineIds` 声明常量；不要复用方舟或边狱 ID |
| `displayNameRes` / `iconRes` | 指向本模块实际存在的资源，并提供项目需要的语言版本 |
| `gamePackages` | 已确认的 Android 包名候选；宿主选择第一个已安装包并在本次显示会话内启动它 |
| `display` | 实测的 `DisplaySpec(width, height, dpi)`，与模板和输入坐标一致 |
| `resourcePacks` | 声明全部所需资源包；纯输入等不需要外部资源的引擎可为空，多个包使用独立目录和包 ID |
| `capabilities` | 只声明已实现能力；这些值不会自动生成定时、作业、前台设备或设置页接线 |

模块直接暴露 provider，将构造保持为轻量操作。可参考 [LimbusEngineProvider](../../../engine/limbus/src/main/java/com/aliothmoon/maadroid/engine/limbus/LimbusEngineProvider.kt)，UI 与工厂的装配均在引擎模块内：

```kotlin
object NewGameProvider : EngineProvider {
    override val profile: GameProfile = NewGameProfile
    override val ui: EngineUi = NewGameUi
    override fun createEngine(): AutomationEngine = NewGameEngine()
}
```

这些类型由新模块实现。随后在 [EngineSetup.install](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineSetup.kt) 中显式注册 `EngineRegistry.register(NewGameProvider)`。Application 已调用此装配点；无需新建一套启动服务或 ServiceLoader。

注册会拒绝重复游戏 ID、错误的包归属、重复资源包 ID，以及相同或嵌套的资源目录。失败不会覆盖原游戏，也不会预留部分包名。请为新游戏声明独立目录；不要依靠注册顺序覆盖已有 provider。只有同一个 provider 实例重复注册才保持幂等。

[BackgroundGamesView](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/view/background/BackgroundGamesView.kt) 从注册表生成 tabs，并把非方舟游戏交给 `EngineTaskContent`。名称、任务和面板应通过契约提供，不再添加按新游戏 ID 分支的页面。游戏选择以字符串保存，旧 ID 不再可用时由注册表回退到已注册方案，因此改 ID 也会改变配置归属。

## 4. 选择任务页配置形式

[EngineUi](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/EngineUi.kt) 支持两种形式，宿主优先使用 `workspace`。

| 形式 | 需要实现的内容 | 宿主行为 |
|---|---|---|
| 独立任务面板 | `taskPanels: List<TaskPanelSpec>`；每项提供 `taskType`、标题、默认开关和参数编辑回调 | 依声明顺序展示并选择任务，参数 JSON 原样持久化和下发 |
| 共享工作区 | `workspace: EngineWorkspace` | 引擎掌握队伍、跨任务策略、图鉴等页面及配置校验，宿主提供日志、资源目录和运行状态 |

任务 type 必须与 `appendTask` 接受的值一致。workspace 具体实现：

- `initialConfig(enabled, taskParams)`：生成首次配置，或迁移旧面板数据。不要覆盖已经保存的 workspace JSON。
- `validate(configJson)`：在启动前报告配置错误；不要依赖运行阶段才发现必填项缺失。
- `selectedTasks(configJson)`：从同一配置快照生成有序的 `(type, paramsJson)` 列表。
- `Content(...)`：通过 `onConfigChange` 回交完整新配置；`editable == false` 时禁止修改，包括已经打开的导入/复制对话框。

`Content` 的 `resources: EngineResources` 包含当前游戏声明的资源路径，不是“资源已安装”的标志。用 `resources.directory(MyCatalogPack)` 选择对应包，缺目录/缺版本时提供下载引导；多资源包的界面可分别读取模型说明、图鉴等内容，不再依赖 `resourcePacks.first()`。

[EngineTaskStore](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineTaskStore.kt) 在 DataStore 的 `engine.<id>.tasks` 下保存参数与 workspace JSON；资源目录不是配置存储目录。升级配置结构应保留原 ID 和字段语义，并提供迁移测试。

简单 `TaskPanelSpec.content` 当前没有 `editable` 入参。宿主 ViewModel 在运行中拒绝配置回写；如新界面需要明确禁用复杂控件，使用 workspace 或先扩展通用接口，不要读取方舟运行状态。

`SettingsSection()` 和 `OnboardingSteps()` 虽已定义，当前尚无宿主调用点。重要的语言、渠道或授权前提应先放入已接通的 workspace；如需独立设置或引导流程，应单独完成宿主接线和验证。

任务工作区应按需依赖 `:core:ui`，复用 `TaskPanelTabs`、`MaaSurfaceCard`、`TaskLogPanel` 等呈现组件，避免每个游戏复制一套颜色和布局。开始/停止、预览全屏与画中画仍由宿主任务页提供；引擎面板不应自行管理显示会话。

## 5. 实现资源包与更新

参考 [ResourcePackSpec](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/ResourcePackSpec.kt)、[UpstreamArchive](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/UpstreamArchive.kt) 和 [LimbusResourcePack](../../../engine/limbus/src/main/java/com/aliothmoon/maadroid/engine/limbus/LimbusResourcePack.kt)。不要直接复用边狱的 pack ID、仓库或动作兼容表。

| 实现点 | 当前安装器的约定 |
|---|---|
| `packId`、`engineId`、`relativeRoot` | 全局唯一包 ID、归属游戏 ID、App 数据根下的相对目录，例如 `engines/newgame`；不使用绝对路径或 `..` |
| `upstreamArchive` | GitHub 仓库、固定初始 tag 与完整 commit、资源前缀、允许提取的目录；现有 transport 下载 codeload 源码 ZIP |
| `finalizeUpstreamInstall(root, revision)` | 在暂存目录校验该游戏的资源结构，生成安装 manifest；不在这里写用户配置或执行上游脚本 |
| `checkCompatibility(manifestJson)` | 检查引擎协议、最低版本、支持的动作等；失败给出用户可理解的原因 |
| `verifyInstalledFiles(root)` | 检查文件缺失/损坏、完整性及必要的资源语义；不能只确认目录存在 |
| `readInstalledVersion` / `invalidateInstalledVersion` | 使用稳定内容版本标识；无效安装返回 null，失效时删除版本标记 |
| `mapZipEntry` | 旧式平铺 ZIP 的映射接口；源码 ZIP 安装实际调用 `upstreamArchive.mapEntry`，只改前者不会改变源码归档的提取范围 |

兼容性检查要对齐该引擎的真实读取方式：例如分别检查所支持语言的模板、页面图鉴的目录层级、模型输入尺寸与标签/字典对应关系。只检查文件非空，或把各语言图片合并后检查，都可能在激活后才暴露错误。静态模型接口校验不能代替原生加载/试推理；两层应各自保留。用真实 pack 与安装器测试坏更新，断言旧 manifest 和文件未变，同时保留合法资源更新成功的用例。

当前 [EngineResourceService](../../../app/src/main/java/com/aliothmoon/maadroid/engine/resource/EngineResourceService.kt) 要求 manifest 的 `upstream` 包含 `repo`、`tag`、`commit`，以恢复已安装提交。首次安装直接使用固定 commit，更新检查选择更新的稳定语义版本 tag；自定义 feed、预发布版本策略或不同归档结构需要对应的宿主适配，不能仅填写一个任意 URL。

`upstreamArchive == null` 的包不会经此通用服务自动安装。`bundledAssetPrefix` 和 `requiresPrivilegedDelivery` 也不会单独生成安装或提权投递流程；方舟目前使用自己的旧链路。如果新游戏采用这种方式，要完成自己的安装接线，并在 `prepare` 前确保目录可读。

宿主的 [EngineResourceCard](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/view/engine/EngineResourceCard.kt) 会为源码归档包显示资源状态及操作。安装在 [AtomicResourceInstaller](../../../app/src/main/java/com/aliothmoon/maadroid/engine/resource/AtomicResourceInstaller.kt) 的暂存目录中完成，校验通过后才激活；任务和更新通过同一 `ResourcePackLocks` 互斥。不要绕过服务直接覆盖正在运行的资源。

需要图鉴时，优先由同包的语言表和图片生成。参考 `LimbusCatalog` / `LimbusArtwork`：缺资源提供下载引导，仍允许配置不依赖图鉴的任务；读取图片与扫描目录放在 IO 线程；缓存至少绑定安装 revision。`File` 路径不变不代表内容不变，不能用一次 `remember(root)` 永久保存图鉴。

## 6. 实现会话独立的执行生命周期

[AutomationEngine](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/AutomationEngine.kt) 的实例由会话持有。`EngineRegistry.createEngine(id)` 不缓存运行实例，provider 每次应返回一个独立实例；同一会话的事件订阅、准备和执行则共用该实例。不要返回可变的单例引擎，也不要在 `createEngine()` 时下载资源、加载模型或启动游戏。

| 方法/事件 | 必须完成的行为 |
|---|---|
| `prepare(resources)` | 用 `resources.requireDirectory(MyPack)` 按包获取目录；宿主已完成所有声明包的安装/校验并持锁，引擎加载自己的资源及检查跨包约束，失败时清理部分初始化 |
| `connect(device)` | 保存传入句柄，验证实际需要的识别/模型能力；显示创建、包选择和首帧等待已经由宿主完成 |
| `appendTask` / `setTaskParams` | 解析本引擎的 type 与 JSON；拒绝非法任务，不把参数交给宿主业务类型解释 |
| `start()` | 成功启动本轮任务，维护 `isRunning`；事件流在此之前已由宿主订阅 |
| `stop()` | 响应取消并等待工作协程真正退出，释放按键/触点；不能仍在读帧却报告已停止 |
| `release()` | 在停止后释放本实例的模型和缓存；下一会话使用新实例，不擅自结束其他会话 |
| `EngineEvent` | 用 `Log`、`Task`、`Failure` 报告过程。任务终止需发 `AllTasksFinished(success)`，包括失败终态；仅发 `Failure` 不会触发当前宿主自动结束会话 |

[DeviceIo](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/DeviceIo.kt) 的帧为 BGR 三通道，按 `stride` 读取。共享缓冲只在本次 `grab` 到下次 `grab` 之间有效，需要跨帧保留时复制。耗时识别和循环应支持协程取消；手势和按键在 `finally` 中成对释放。不要自行创建另一个虚拟显示器或重启提权服务。

`EngineResources` 只冻结包 ID 到路径的映射，不冻结磁盘内容；运行时的全部资源锁由会话持续持有，直到确认停止并释放引擎。各包仍独立安装/更新，多包版本间若有依赖，需要引擎在 `prepare` 中验证，不能假设多个上游会同步发布。无资源引擎仍需实现 `prepare`，可完成自身初始化后返回成功，不要伪造一个空目录来满足接口。

通用运行使用 `EngineSession`，已参与 `EngineExecutionCoordinator` 的准入与资源锁。以后增加调度、深链或其他启动入口时，需复用这条会话与收尾路径；只查看当前可见页面或方舟的 running 状态不能阻止跨入口冲突。预览脱离、切换 tab 不代表结束任务。

如果确需在提权进程运行自有引擎服务：

1. 在新模块持有专属 AIDL、服务实现和 App 侧 `AutomationEngine` 代理；保留 consumer rules 所需的 Binder/JNI 入口。
2. 实现 [RemoteEngineFactory](../../../core/bridge/src/main/java/com/aliothmoon/maadroid/remote/RemoteEngineRegistry.kt) 的 `engineId`、`create`，及必要的 setup / close / version 回调。
3. 只在 [MaaDroidRemoteService](../../../app/src/main/java/com/aliothmoon/maadroid/remote/MaaDroidRemoteService.kt) 注册工厂；通过游戏无关的 `getEngineService(id)` 获得 Binder。App 内执行的引擎无需此注册。
4. 验证实际部署目录、native 库、进程死亡清理与远端停止语义。方舟的 JNA/AIDL、`ArknightsEngine` 与会话测试可作桥接参考；其宿主资源适配、旧任务页和业务回调接线仍属方舟专用，不能直接复制为新引擎的完整启动模板。

## 7. 验证接入闭环

测试应覆盖真正的配置与生命周期行为，而不止“注册表能列出名字”。可复用以下测试的结构：

| 验证对象 | 现有参考 |
|---|---|
| 模块依赖、游戏 ID / 资源隔离 | [ModuleBoundaryContractTest](../../../core/remote/src/test/java/com/aliothmoon/maadroid/ModuleBoundaryContractTest.kt)、[MultiEngineContractTest](../../../app/src/test/java/com/aliothmoon/maadroid/engine/MultiEngineContractTest.kt) |
| 配置重载、旧数据迁移、任务顺序 | [EngineTaskSelectionTest](../../../app/src/test/java/com/aliothmoon/maadroid/engine/EngineTaskSelectionTest.kt)、本引擎的 workspace 测试 |
| 缺文件、未知动作、坏 ZIP、取消与旧资源恢复 | [EngineResourceInstallTest](../../../app/src/test/java/com/aliothmoon/maadroid/engine/resource/EngineResourceInstallTest.kt)、本引擎 manifest 测试 |
| 重复启动、失败/停止收尾、设备归属 | [EngineTaskViewModelTest](../../../app/src/test/java/com/aliothmoon/maadroid/presentation/viewmodel/EngineTaskViewModelTest.kt)、[EngineDeviceSessionTest](../../../app/src/test/java/com/aliothmoon/maadroid/engine/EngineDeviceSessionTest.kt)、[EngineExecutionCoordinatorTest](../../../engine/api/src/test/java/com/aliothmoon/maadroid/engine/EngineExecutionCoordinatorTest.kt) |
| 多包目录传递、部分失败/取消、无资源任务 | [EngineSessionLifecycleTest](../../../app/src/test/java/com/aliothmoon/maadroid/engine/EngineSessionLifecycleTest.kt)、[EngineResourcesTest](../../../engine/api/src/test/java/com/aliothmoon/maadroid/engine/EngineResourcesTest.kt) |

在 Android 上再完成：无资源时配置并下载 → 首次启动取到正确帧 → 运行最小真实任务 → 停止并再次启动 → 切换页面/预览 → 检查更新并确认同路径的新图鉴生效。覆盖两种权限后端中实际支持的一种或两种，记录 ABI 与设备条件。检查另一游戏正在运行或准备资源时，新的启动/资源操作是否按既有准入规则拒绝，且没有影响原会话。

最后更新 README 的游戏状态与第三方来源。仅在真实入口完成接线和验证后声明相应能力；编译成功、存在 profile 或完成 MaaCore 文件迁移，都不等于整个游戏接入已完成。
