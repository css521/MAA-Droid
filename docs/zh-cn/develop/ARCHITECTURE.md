# 架构说明

当前 App 同时保留方舟原有任务链路，并通过通用引擎契约接入边狱。游戏列表、资源声明、设备取帧与输入已有共用接口；方舟业务尚未全部迁入 `engine/arknights`。新增游戏的实施步骤见[新增游戏指南](ADDING_A_GAME.md)。

## 当前模块边界

| 目录 | 当前职责 |
|---|---|
| `app/` | 导航、游戏选择、持久化、通知、调度、更新服务、引擎装配；仍含方舟任务配置、业务编排、回调和面板 |
| `engine/api/` | `GameProfile`、`EngineProvider`、`AutomationEngine`、`EngineUi`、设备与资源契约，以及 App 进程内的执行准入协调器 |
| `engine/arknights/` | MaaCore JNA/AIDL、服务与 `MaaCoreSession`、profile / 包名、资源声明、部分枚举与状态；MaaCore 资产与打包接线已移至此模块，业务仍未全量迁移 |
| `engine/limbus/` | LALC 流水线与动作的 Kotlin 实现、识别器、配置转换、任务工作区、资源包校验和动态图鉴 |
| `core/bridge/` | native 截图桥、输入与 AIDL 契约、提权引擎注册契约、源自 scrcpy 的系统 API 封装 |
| `core/remote/` | 提权服务、显示与帧通道、`RemoteDeviceHandle` 等设备契约实现 |
| `core/ui/`、`core/common/` | 共用 Compose 组件与主题；不依赖 Compose 的文本等通用类型 |
| `hidden-api/` | 编译期 Android 隐藏 API 桩 |
| `tooling/`、`build-logic/` | 偏好 KSP 工具及构建校验、原生库打包等约定 |

Gradle 路径与目录对应，例如 `:engine:limbus`、`:core:bridge`。`app` 依赖并装配具体引擎；引擎依赖 `engine:api` 和实际需要的平台模块。`core:remote` 实现设备契约，因此可依赖 `engine:api`；`engine:api` 又使用 `core:bridge` 的底层标识与契约。不能将这些关系简化为一条包含全部模块的线性链。

[ModuleBoundaryContractTest](../../../core/remote/src/test/java/com/aliothmoon/maadroid/ModuleBoundaryContractTest.kt) 从模块目录发现具体引擎，检查 core 不引用具体游戏或宿主、引擎之间不互相依赖、引擎不引用宿主应用层、API 不引用具体引擎。[HostEngineIsolationContractTest](../../../app/src/test/java/com/aliothmoon/maadroid/HostEngineIsolationContractTest.kt) 约束方舟遗留依赖继续收敛。测试中的允许范围不是“全部抽取完成”的证明。

## 引擎声明与宿主装配

[engine/api](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine) 提供以下入口：

| 契约 | 谁提供，谁消费 |
|---|---|
| `GameProfile` | 引擎提供稳定 ID、名称/图标、候选包名、显示规格、资源包与能力声明；宿主据此列出游戏和准备设备 |
| `EngineProvider` | 提供 profile、UI 和轻量的 `createEngine()`；[EngineSetup](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineSetup.kt) 在 App 启动时注册 |
| `AutomationEngine` | 引擎实现资源加载、设备连接、任务追加、启动、停止、释放以及事件流 |
| `EngineUi` | 简单任务用 `taskPanels`；跨任务配置用 `workspace`，由宿主任务页渲染 |
| `ResourcePackSpec` / `UpstreamArchive` | 引擎声明来源、安装目录、归档映射和校验规则；宿主执行下载、暂存和替换 |

`EngineRegistry.createEngine(id)` 只调用 provider 工厂，不缓存运行实例。每个 `EngineSession` 独立持有自己的实例和事件流；订阅事件与后续 `prepare` 必须使用该会话的同一实例，新会话重新创建。资源、模型、native 库的加载放在 `prepare` / `connect`，停止后由会话调用 `release`，不能把可变引擎实例做成跨会话单例。

两种执行位置各有装配要求：

| | 边狱 / 当前通用引擎路径 | 方舟遗留路径 |
|---|---|---|
| 业务执行 | App 进程内 Kotlin、OpenCV、ONNX Runtime | 提权进程内 MaaCore，由宿主业务代码编排 |
| 设备访问 | `DeviceHandle`，共享内存 BGR 帧与 AIDL 输入 | MaaCore 与 native 桥及其专属 AIDL |
| App 注册 | `EngineSetup` 中的 provider | profile / 资源可用；provider 的 `createEngine()` 仍为 TODO |
| 提权注册 | 只使用通用设备服务，不需注册一个边狱 Binder | [MaaDroidRemoteService](../../../app/src/main/java/com/aliothmoon/maadroid/remote/MaaDroidRemoteService.kt) 注册 `ArknightsRemoteEngineFactory` |

只有需要自有提权引擎服务的游戏才实现 `RemoteEngineFactory`。工厂注册仍在 App 的装配点，不能让 `core:remote` import 新游戏。游戏无关的 `RemoteService.getEngineService(engineId)` 返回 Binder，由该引擎自己的代理还原接口。

## 任务页与配置

[BackgroundGamesView](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/view/background/BackgroundGamesView.kt) 根据注册表生成游戏 tabs，按 engine ID 保存页面状态。方舟进入 `BackgroundTaskView`；其他已注册游戏进入 [EngineTaskContent](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/view/engine/EngineTaskView.kt)。新增通用引擎不应再复制方舟分支。

[EngineTaskStore](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineTaskStore.kt) 使用 `engine.<id>.tasks` 隔离任务开关、参数 JSON 和 workspace 配置。引擎自己解释配置；宿主负责保存和传递。简单面板按声明顺序选择任务；workspace 通过 `initialConfig`、`validate`、`selectedTasks` 处理迁移、校验和执行列表。

边狱工作区保留 LALC 的 `taskConfigs`、`teamConfigs`、`themePackWeights`，再转换为执行分节。图鉴由已下载资源生成，资源更新不会改写用户队伍、权重或饰品允许/排除配置。`EngineWorkspace.Content` 当前只收到资源目录 `File?`；边狱在页面存活时检查 manifest revision，更新图鉴与图片，避免同路径替换后继续显示旧内容。

`EngineUi.SettingsSection()` 与 `OnboardingSteps()` 是预留接口，当前没有宿主调用点。`Capability` 也只是声明；不能仅添加 `SCHEDULE` 或 `COPILOT` 就宣称已接通定时或外部作业入口。

## 通用运行生命周期

入口代码为 [EngineTaskViewModel](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/viewmodel/EngineTaskViewModel.kt)、[EngineSession](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineSession.kt) 和 [EngineDeviceSession](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineDeviceSession.kt)。

1. 启动时取得准入、等待配置保存，验证 workspace，并固定本次执行配置。任务列表为空或配置无效时不启动。
2. 先订阅引擎事件，再准备资源；源码归档包缺失时安装，随后持有资源包锁并重新验证版本与兼容性。
3. `AutomationEngine.prepare` 接收主资源目录。宿主按 profile 选择已安装包、创建后台显示会话、启动游戏并等待尺寸正确的首帧，然后调用 `connect`。
4. 依次 `appendTask(type, paramsJson)`，拒绝无效任务 ID，再调用 `start`。引擎通过 `EngineEvent` 报告状态；`AllTasksFinished` 是宿主自动收尾的终态，`Failure` 本身只是错误报告。
5. 完成、停止或失败收尾时，先停止并等待引擎任务退出，再释放模型、帧映射、输入/显示会话及资源和执行租约。若引擎仍在运行，应保留占用并允许重试停止。

[EngineExecutionCoordinator](../../../engine/api/src/main/java/com/aliothmoon/maadroid/engine/EngineExecutionCoordinator.kt) 负责 App 进程内任务与资源准备的准入；当前通用会话和方舟的 `MaaCompositionService` / `MaaResourceLoader` 已有接入点。它与按包的 `ResourcePackLocks`、提权侧显示租约分工不同：分别限制业务准入、资源换版、实际设备访问。运行时协调仍在收敛，新增入口需检查它是否参与现有准入与清理，不能只靠隐藏按钮实现互斥。

页面切换与预览 Surface 脱离不等于任务停止。引擎只使用宿主传入的设备句柄，不在 Compose 重组中创建显示、加载资源或启动任务。当前 `EngineDeviceSession.open` 仅接受后台模式；前台支持需要另做设备适配。

## 资源下载与更新

资源包的 App 可读根目录由 [EngineDataRoot](../../../core/remote/src/main/java/com/aliothmoon/maadroid/remote/EngineDataRoot.kt) 解析为 `externalFilesDir/Maa/<relativeRoot>`。历史 `Maa` 目录名保持不变；提权侧 `/data/local/tmp/maameow` 是另一个目录，不应直接交给 App 进程内的识别器读取。

| | 方舟 | 边狱 |
|---|---|---|
| 声明 | `MaaResourcePack`，`upstreamArchive == null` | `LimbusResourcePack`，固定 LALC 仓库、初始 tag 与完整 commit |
| 安装/更新 | `MaaResourceLoader`、`UpdateService` 与既有 GitHub / MirrorChyan 链路 | `EngineResourceService` + `AtomicResourceInstaller` |
| 包内/落盘布局 | APK 前缀 `MaaSync/MaaResource`；运行目录继续使用 `cache/resource`，按配置投递至提权侧 | `engines/limbus`；包含 `config/task`、`config/language`、`img`、`ai/model`、`recognize/models` |
| UI 素材 | 方舟现有资源路径 | 与执行资源同包；不再内置批量图鉴 PNG 或 `catalog.json` |
| 更新限制 | 与 MaaCore 版本及既有资源规则匹配 | manifest schema、最低引擎版本、动作声明、模板/模型及文件哈希校验 |

[EngineResourceService](../../../app/src/main/java/com/aliothmoon/maadroid/engine/resource/EngineResourceService.kt) 当前处理带 `upstreamArchive` 的源码 ZIP。首次安装直接使用固定 commit；检查更新时查询 GitHub tags，选择更新的稳定语义版本并固定 SHA。可用的本地安装不需要联网才能再次使用。

[AtomicResourceInstaller](../../../app/src/main/java/com/aliothmoon/maadroid/engine/resource/AtomicResourceInstaller.kt) 限制归档路径、重复项及大小，在相邻暂存目录提取并调用引擎校验；通过后替换活动目录，失败保留或恢复旧版本。安装器生成的逐文件摘要用于校验安装内容与后续完整性，不是上游签名。运行会话持有同一资源包锁，避免任务中途换版。

当前边狱直接下载 LALC 源码归档，不依赖 Windows 整包重打包 CI 或本仓库 Release。`scripts/pack_engine_resource.py` 只作为离线工具；`scripts/sync_limbus_ui.py` 只检查/导出本地图鉴。新的动作实现、原生库或不兼容资源协议仍需 App 更新。

## 方舟迁移的实际边界

已迁入 `engine/arknights` 的部分包括 `core/` 下的 JNA、MaaCore 管理与服务、对应 AIDL、profile、包名、资源声明及部分枚举/状态。[MaaCoreSession](../../../engine/arknights/src/main/java/com/aliothmoon/maadroid/engine/arknights/core/MaaCoreSession.kt) 已承担实例初始化、连接回调确认、完整任务队列提交与停止确认；宿主 `MaaCompositionService` 调用它，仍负责资源/设备准备、任务参数与业务回调接线。以下边界仍需区分：

- `ArknightsEngineProvider.createEngine()` 尚未实现，现有任务由宿主 `MaaCompositionService` 编排；不能从通用注册表直接启动方舟。
- 任务配置/资源模型、业务回调、面板和部分状态仍在 `app`。`maa.DriverClass` 也仍由 native JNI 按旧类名查找。
- MaaCore 下载路径现为 `engine/arknights/src/main/assets/MaaSync/MaaResource` 与 `engine/arknights/src/main/jniLibs`；[setup_maa_core.py](../../../scripts/setup_maa_core.py) 和[方舟模块构建声明](../../../engine/arknights/build.gradle.kts)已接线，模块生成自己的 JNI 打包目录与资源 manifest。[app 构建声明](../../../app/build.gradle.kts)仍为旧调用点转接版本字段。构建接线迁移不等于整包验收或业务迁移完成，APK 资源前缀及已安装用户的数据路径继续保持。
- 方舟持久化任务中的 `@SerialName` 保留旧全限定名。搬包不能改这个存档标识；[TaskConfigWireFormatTest](../../../app/src/test/java/com/aliothmoon/maadroid/data/model/TaskConfigWireFormatTest.kt) 是迁移时需要保留的约束。

边狱的页面、资源和执行字段对照见 [LIMBUS_UI_PARITY](LIMBUS_UI_PARITY.md)；许可证与依赖来源见[第三方代码声明](THIRD_PARTY_NOTICES.md)。文档中的接口接入与主机检查不替代 Android 设备上的识别、输入、取消和完整任务验证。
