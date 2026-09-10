# 架构说明

当前 App 同时保留方舟原有任务链路，并通过通用引擎契约接入边狱。游戏列表、资源声明、设备取帧与输入已有共用接口；方舟业务尚未全部迁入 `engine/arknights`。新增游戏的实施步骤见[新增游戏指南](ADDING_A_GAME.md)。

## 当前模块边界

| 目录 | 当前职责 |
|---|---|
| `app/` | 导航、游戏选择、持久化、通知、调度、更新服务、引擎装配；仍含方舟任务配置、业务编排、回调和面板 |
| `engine/api/` | `GameProfile`、`EngineProvider`、`AutomationEngine`、`EngineUi`、设备与资源契约，以及 App 进程内的执行准入协调器 |
| `engine/arknights/` | MaaCore JNA/AIDL、服务、`MaaCoreSession` 与 `ArknightsEngine`、profile / 包名、资源声明、部分枚举与状态；MaaCore 资产与打包接线已移至此模块，业务仍未全量迁移 |
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

两个引擎都在各自模块提供 provider；`EngineSetup` 只负责装配。注册时检查游戏 ID、全局资源包 ID、资源归属及目录：不同资源包不能指向相同目录或彼此的父/子目录，`./` 与重复分隔符的别名也不能绕过检查。任何检查失败都保留已有注册内容；同一个 provider 实例重复注册是无操作。注册表和通用会话都允许无资源包的游戏，也允许一个游戏声明多个独立资源包。

`EngineResources` 将目录按 `packId` 关联到当前游戏，保留不可变的路径映射；它本身不读取或验证文件。引擎的 `prepare(resources)` 和工作区的 `Content(..., resources)` 使用同一类型，各自通过 `requireDirectory(pack)` / `directory(pack)` 取需要的包。不存在按列表首项推断主资源的约定，也不能查询另一游戏的包。运行前的内容校验和持锁由宿主负责；配置页提供的是路径，必须允许尚未安装资源的状态。

两种执行位置各有装配要求：

| | 边狱 / 当前通用引擎路径 | 方舟遗留路径 |
|---|---|---|
| 业务执行 | App 进程内 Kotlin、OpenCV、ONNX Runtime | 提权进程内 MaaCore，由宿主业务代码编排 |
| 设备访问 | `DeviceHandle`，共享内存 BGR 帧与 AIDL 输入 | MaaCore 与 native 桥及其专属 AIDL |
| App 注册 | `EngineSetup` 中的 provider | provider 可创建独立 `ArknightsEngine`；现有任务页由 `MaaCompositionService` 接线，每轮创建独立引擎 |
| 提权注册 | 只使用通用设备服务，不需注册一个边狱 Binder | [MaaDroidRemoteService](../../../app/src/main/java/com/aliothmoon/maadroid/remote/MaaDroidRemoteService.kt) 注册 `ArknightsRemoteEngineFactory` |

只有需要自有提权引擎服务的游戏才实现 `RemoteEngineFactory`。工厂注册仍在 App 的装配点，不能让 `core:remote` import 新游戏。游戏无关的 `RemoteService.getEngineService(engineId)` 返回 Binder，由该引擎自己的代理还原接口。

## 任务页与配置

[BackgroundGamesView](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/view/background/BackgroundGamesView.kt) 根据注册表生成游戏 tabs，按 engine ID 保存页面状态。方舟进入 `BackgroundTaskView`；其他已注册游戏进入 [EngineTaskContent](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/view/engine/EngineTaskView.kt)。新增通用引擎不应再复制方舟分支。

[EngineTaskStore](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineTaskStore.kt) 使用 `engine.<id>.tasks` 隔离任务开关、参数 JSON 和 workspace 配置。引擎自己解释配置；宿主负责保存和传递。简单面板按声明顺序选择任务；workspace 通过 `initialConfig`、`validate`、`selectedTasks` 处理迁移、校验和执行列表。

边狱工作区保留 LALC 的 `taskConfigs`、`teamConfigs`、`themePackWeights`，再转换为执行分节。图鉴由已下载资源生成，资源更新不会改写用户队伍、权重或饰品允许/排除配置。`EngineWorkspace.Content` 收到当前游戏所有资源路径，边狱明确选择 `LimbusResourcePack`；在页面存活时检查其 manifest revision，更新图鉴与图片，避免同路径替换后继续显示旧内容。

预览外框、FPS、紧凑标签、左右任务/配置布局、任务选择行、日志面板与行样式、主/次操作按钮在 `core/ui`，方舟和边狱使用同一套组件。首页的两种资源更新卡片也共用 `MaaSurfaceCard`。这些组件只负责呈现；预览 Surface 与设备租约、任务开关、日志详情和执行行为仍由各调用方提供。

宿主 `TaskQuickActionsOverlay` 提供两个游戏共用的快捷菜单：熄屏、关闭游戏、声音控制、导出日志和自动设置。游戏声音复用 `GameMuteCoordinator` 的持久化与恢复逻辑，以已解析的安装包名区分游戏；边狱关闭游戏走当前 `IEngineDeviceSession`，不能通过无归属的全局 API 误关另一个游戏。停止后的预览继续可用，普通视图显示待执行蒙版，点击后进入可交互的大屏；画中画沿用同一预览 Surface。

`EngineUi.SettingsSection()` 与 `OnboardingSteps()` 是预留接口，当前没有宿主调用点。`Capability` 也只是声明；不能仅添加 `SCHEDULE` 或 `COPILOT` 就宣称已接通定时或外部作业入口。

### 多游戏配置备份

[ConfigBackupManager](../../../app/src/main/java/com/aliothmoon/maadroid/data/preferences/ConfigBackupManager.kt) 当前导出 v2，接受 v1、v2 导入；[ConfigBackup](../../../app/src/main/java/com/aliothmoon/maadroid/data/preferences/ConfigBackup.kt) 的缺省版本仍为 1，以读取旧文件。v2 保留通用设置、通知、方舟任务 profiles、活动 profile 和定时策略，新增 `engineTasks: Map<String, String>`。键为 engine ID，值为该引擎任务 envelope 的原始 JSON 字符串，包含任务开关 `enabled`、参数字符串映射 `params` 和可空的工作区字符串 `workspaceConfig`。

`EngineTaskStore.exportSnapshot()` 枚举已经持久化的 `engine.<id>.tasks`，不按当前注册表筛选，因此当前 APK 未注册的未知引擎也能备份、恢复。导入与再次导出保留 envelope 原始字符串；普通开关、参数和 workspace 编辑会合并已知字段，保留 envelope 顶层的未知字段。这个保留保证不涵盖整个 `ConfigBackup` 的未知顶层字段；参数和 workspace 内部的业务结构仍由引擎解释和迁移。

导入只按引擎整条替换 `engineTasks` 中列出的条目，不与本地 envelope 逐字段合并；未包含的引擎保持原样。普通 v1 文件没有 `engineTasks`，缺省为空映射，所以不覆盖本地边狱等引擎配置；若 v1 显式携带该字段，仍会校验并导入其中的引擎。v2 同样保留未包含的引擎，不能把“v1 兼容”理解为忽略所有引擎数据。

备份边界要求 engine ID 非空白、envelope 可按 `EngineTasks` 解析；`params` 中的每个非空白字符串和非空白的 `workspaceConfig` 必须是有效 JSON，拒绝 `not-json` 这样的未加引号字面量。空白参数以及缺省、null 或空白的 workspace 仍允许保留。这里只检查存储格式和 JSON 语法，不调用 workspace 的业务 `validate`；队伍等必填业务内容尚未填完、但 JSON 有效的草稿也能往返。能否执行由启动前的引擎配置校验决定。导入在任何设置写入前完成备份解析、版本和全部引擎配置检查；导出遇到坏的持久化数据也会失败，不以启动读取时的空状态回退替代原数据。

导入读取并关闭输入文件后，先校验、等待方舟任务加载，并直接读取持久化的定时策略；记录旧值后，再依次写入通用设置、通知、方舟 profiles、包含的引擎配置和定时策略，全部持久化写入成功后才取消、重建系统闹钟。定时策略的导出和旧值快照均通过 `ScheduleStrategyRepository.snapshot()` 读取 DataStore 最新已提交数据，不再等待定时仓库的 `isLoaded`，避免 UI 缓存延迟造成旧快照。每次写入尝试前登记撤销步骤，遇到异常或协程取消时，在 `NonCancellable + Dispatchers.IO` 中按逆序尽力恢复；已触及闹钟时也尝试恢复旧调度。单项撤销失败仍继续其余恢复，最终明确报告“部分旧配置未能恢复”，取消情形保留取消语义；恢复成功则继续抛出原异常或取消。

包含的引擎条目在 `engine_tasks` 的一次 DataStore `edit` 中提交，但整次备份恢复跨多个存储和系统闹钟，**不具备跨 DataStore 的事务原子性**。管理器的 mutex 只串行化自身导入、导出，不阻止其他配置写入入口，也不保证导出取得跨存储的同一时刻快照。撤销记录只在内存中，没有持久化恢复日志；进程退出或被杀时不能依赖回滚，可能留下部分导入状态，不具备 crash atomic 保证。整包导入也不会重放所有设置的运行态副作用，导入成功后仍建议重启应用。

## 通用运行生命周期

入口代码为 [EngineTaskViewModel](../../../app/src/main/java/com/aliothmoon/maadroid/presentation/viewmodel/EngineTaskViewModel.kt)、[EngineSession](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineSession.kt) 和 [EngineDeviceSession](../../../app/src/main/java/com/aliothmoon/maadroid/engine/EngineDeviceSession.kt)。

1. 启动时取得准入、等待配置保存，验证 workspace，并固定本次执行配置。任务列表为空或配置无效时不启动。
2. 先订阅引擎事件，再按包 ID 的稳定顺序准备每个资源包；源码归档包缺失时安装，随后持有其锁并重新验证版本与兼容性。全部成功后才向引擎交付完整目录集合。任何包失败或等待被取消都进入统一收尾流程，确认引擎停止后释放已经取得的锁；停止未确认时保留占用。
3. `AutomationEngine.prepare` 接收 `EngineResources`。无资源包的引擎收到空集合，跳过下载/校验而继续正常生命周期。首次启动时，宿主按 profile 选择已安装包、创建后台显示会话、启动游戏并等待尺寸正确的首帧，然后调用 `connect`。同游戏再次执行时，优先接管保留的设备会话。
4. 依次 `appendTask(type, paramsJson)`，拒绝无效任务 ID，再调用 `start`。引擎通过 `EngineEvent` 报告状态；`AllTasksFinished` 是宿主自动收尾的终态，`Failure` 本身只是错误报告。
5. 完成或手动停止时，先确认引擎停止并等待任务退出，再释放引擎模型、资源锁及执行准入，保留设备、帧通道、预览和手动输入。关闭游戏或退出会话才关闭设备。未确认停止时继续持有占用，允许重试停止。
6. 复用需要同一游戏、运行模式、远程服务 Binder、显示规格和仍有效的设备租约。接管前释放旧页面触点并撤销旧页面对设备的引用；旧回调不能关闭新任务。确认游戏进程仍在或状态未知时不发送启动 Intent；仅确认进程退出才重新打开。准备新引擎失败且尚未转移时恢复原预览。跨游戏或需要重启提权服务的资源准备会先关闭不兼容的旧设备。

`IEngineDeviceSession.getGameFps` 读取该设备租约对应的 `GameFpsMonitor`，与方舟使用相同的系统任务 FPS 回调和帧计数回退。采样器在游戏打开时启动、设备释放时停止；过期设备 Binder 不能读取新游戏的帧率。

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

边狱在激活前同时检查以下运行约束：

- 流水线节点类型、识别方式及模板/阈值/mask 参数。未知识别不会作为“不命中”参与 `inverse`；绕过安装检查的运行时调用也会明确失败。
- Android 邮件适配依赖的路由、识别和点击目标。即便新包默认关闭邮件，仍用启用邮件的内存副本验证；拒绝不兼容包时保留旧资源。兼容的描述、时序、阈值和图片更新不受影响，也不会把验证时的运行配置写回文件。
- `general + zh`、`general + en` 各自的模板引用，以及实际语言表的字符串类型；英文无翻译表时仍允许使用资源标识。饰品图鉴按递归目录、主题包图鉴按直接子文件检查，保持与页面读取方式一致。
- PNG 块结构、长度与 CRC；五个 ONNX 的输入/首输出声明、float 类型、形状，以及 OCR 字符表、分类器标签数量与输出类别的对应关系。分类器尺寸/类别取自新包，不锁死旧版数据；未使用的辅助输出允许保留。

这些是安装前的结构与接口校验，不执行 Python，也不加载原生库。它们不证明 PNG 像素能解码、ONNX 算子/权重可推理或画面识别准确；`connect` 时的模型加载和能力检查仍然必要。`LimbusResourceInstallTest` 使用小型合成归档验证拒绝更新后旧目录/manifest 字节不变，真实上游归档与模型另做本地验证，不能混同为手机端流程验收。

当前边狱直接下载 LALC 源码归档，不依赖 Windows 整包重打包 CI 或本仓库 Release。`scripts/pack_engine_resource.py` 只作为离线工具；`scripts/sync_limbus_ui.py` 只检查/导出本地图鉴。新的动作实现、原生库或不兼容资源协议仍需 App 更新。

边狱每次共用一条上游流水线，`LimbusTaskRun` 通过执行器观察接口区分各任务的实际入口与完成检查。只有达到检查次数才报告该任务完成；后续失败或中止保留已经完成的结果。栈空但所选任务尚未完成时报告未完成。具体移植差异与测试范围见 [LALC_ANDROID_PORT.md](LALC_ANDROID_PORT.md)。

## 方舟迁移的实际边界

已迁入 `engine/arknights` 的部分包括 `core/` 下的 JNA、MaaCore 管理与服务、对应 AIDL、profile、包名、资源声明及部分枚举/状态。[MaaCoreSession](../../../engine/arknights/src/main/java/com/aliothmoon/maadroid/engine/arknights/core/MaaCoreSession.kt) 承担实例初始化、连接回调确认、完整任务队列提交与停止确认，由 `ArknightsEngine` 使用；宿主 `MaaCompositionService` 已改为每轮创建该引擎，仍负责资源/设备适配、任务参数与业务回调接线。以下边界仍需区分：

- `ArknightsEngineProvider.createEngine()` 已实现，注册和创建不读取 Koin 或加载 native；`prepare` 才固定客户端/暂停部署选项，并通过 `MaaResourcePreparation` 调用宿主资源适配。`ArknightsEngine` 使用设备契约提供的显示规格和专属 Binder，经 `MaaCoreSession` 连接、追加真实 task ID、启动和确认停止。失败或取消时未确认停止不能释放；旧实例失效后不再访问旧 Binder。
- 当前方舟任务页仍由宿主 `MaaCompositionService` 编排，provider 的 `taskPanels` 仍为空；执行入口已使用 `ArknightsEngine`。`ArknightsDeviceAdapter` 惰性打开显示，核心 BUSY 时不能先改变当前游戏画面。同步业务回调通过本轮引擎的 `setTaskParams` 回写；库存刷新不再查询全局 MaaCore Binder。自然完成先确认停止，再发出业务终态、释放执行准入，保留 VD；服务死亡和旧回调按绑定实例隔离。已有 Fake MaaCoreClient 与真实宿主入口的生命周期测试，尚未经这次改动后的真实 Binder/native 联调。
- 任务配置/资源模型、业务回调、面板和部分状态仍在 `app`。`maa.DriverClass` 也仍由 native JNI 按旧类名查找。
- MaaCore 下载路径现为 `engine/arknights/src/main/assets/MaaSync/MaaResource` 与 `engine/arknights/src/main/jniLibs`；[setup_maa_core.py](../../../scripts/setup_maa_core.py) 和[方舟模块构建声明](../../../engine/arknights/build.gradle.kts)已接线，模块生成自己的 JNI 打包目录与资源 manifest。[app 构建声明](../../../app/build.gradle.kts)仍为旧调用点转接版本字段。构建接线迁移不等于整包验收或业务迁移完成，APK 资源前缀及已安装用户的数据路径继续保持。
- 方舟持久化任务中的 `@SerialName` 保留旧全限定名。搬包不能改这个存档标识；[TaskConfigWireFormatTest](../../../app/src/test/java/com/aliothmoon/maadroid/data/model/TaskConfigWireFormatTest.kt) 是迁移时需要保留的约束。

边狱的页面、资源和执行字段对照见 [LIMBUS_UI_PARITY](LIMBUS_UI_PARITY.md)；许可证与依赖来源见[第三方代码声明](THIRD_PARTY_NOTICES.md)。文档中的接口接入与主机检查不替代 Android 设备上的识别、输入、取消和完整任务验证。
