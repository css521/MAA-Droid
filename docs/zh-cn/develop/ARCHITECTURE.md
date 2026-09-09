# 架构说明

本项目是**多游戏自动化宿主**：同一个 App 内可切换游戏，每个游戏是一个可插拔的引擎模块。

## 模块

目录结构即依赖方向，分层一眼可见：

```
app/                    宿主：导航、游戏切换、资源中心、定时、通知、引擎装配
engine/
  api/                  插件契约：GameProfile / AutomationEngine / EngineUi / ResourcePackSpec
  arknights/            明日方舟：任务配置、资源模型、游戏声明、MaaCore JNA/AIDL 与提权服务
  limbus/               边狱公司（移植自 LALC）    ┘ 每个游戏一个模块
core/
  bridge/               native 截图桥、AIDL 契约、输入注入
  remote/               提权进程实现、虚拟显示器、设备侧抽象实现
  ui/                   引擎与宿主共用的 Compose 组件与主题
  common/               与 UI 无关的通用能力（i18n 文本模型；偏好/通知/更新框架待拆）
hidden-api/             framework 隐藏 API 桩，仅编译期
tooling/
  annotation-api/       偏好 KSP 的注解
  ksp-processor/        偏好 KSP 的处理器
build-logic/            构建约定插件
```

Gradle 路径与目录一致：`:engine:limbus`、`:core:bridge`、`:tooling:ksp-processor`。

方舟的 `MaaCoreService` / `MaaCoreCallback` AIDL 和 JNA 桥接已由 `engine:arknights`
持有，AIDL 包名与接口内容保持不变。模块通过 consumer rules 保留 C 符号和 Binder
入口；宿主只在提权进程装配点注册工厂。`maa.DriverClass` 仍由 native JNI 按原名查找，
暂留宿主。方舟的任务编排、面板、原生库与打包素材也尚未全部迁出宿主，
`ArknightsEngineProvider.createEngine()` 尚未实现，不能视为已经完成全量引擎抽取。

`app` 装配具体引擎；引擎依赖 `engine:api` 及需要的平台模块。
`core:remote` 实现设备契约，因此可以依赖 `engine:api`，但不依赖任何具体游戏。
底层截图、输入与 IPC 在 `core:bridge`，共用界面和文本能力分别在 `core:ui`、`core:common`。

四条边界由 `ModuleBoundaryContractTest` 钉住，且它**动态发现模块目录**（不写死名字，
挪模块与加引擎都不必回来改）：

| 规则 | 破了会怎样 |
|---|---|
| `core:*` 不得依赖具体引擎或宿主 | 第二个游戏再也接不进来 |
| 引擎之间不得互相依赖 | 引擎无法独立增删 |
| 引擎不得依赖宿主应用层 | 该能力本应下沉到 `core:*` |
| `engine:api` 不得认识具体引擎 | 契约不再通用 |

注意区分 `engine.*`（契约所在包，`core:remote` 依赖它是设计如此）与
`engine.<游戏>.*`（具体引擎，禁止被 core 引用）。规则只禁后者。

第三条规则同时**编码了抽取 `engine/arknights` 的前置条件**：方舟代码用着仍在 `:app`
里的东西，直接搬过去会立刻触犯它。这些依赖由 `HostEngineIsolationContractTest`
按宿主包逐项计数、只允许下降，进度一目了然：

| 方舟依赖的宿主包 | 起始 | 当前 | 去向 |
|---|---|---|---|
| `presentation.components.` | 76 | **4** | `core:ui`（已下沉；残留 4 处是误放在宿主的方舟组件）|
| `theme.` | 22 | **2** | `core:ui`（已下沉；残留 2 处是日志色板，依赖宿主日志模型）|
| `utils.i18n.` | 20 | **4** | `core:common`（已下沉；残留 4 处是方舟专属文案）|
| `presentation.viewmodel.` | 15 | 15 | 方舟改构造函数注入即可消除，非前置 |
| `data.preferences.` | 10 | 10 | 随 `TaskChainState` 迁入方舟 |

### `core:ui` 的范围是怎么划的

**按引擎实际需要，而不是把 `:app` 的组件目录整个搬过来。** 实测方舟只用到 18 个组件符号
（`presentation/components/` 共 33 个文件），其中还要剔掉两类：

- `ResourceLoadingOverlay` 依赖 `MaaResourceLoader` —— 它是**方舟专属**的，搬进 `core:ui`
  会让平台层反向依赖引擎，正是边界契约要拦的事
- `RecruitTimeSelector` / `CoreCharSelector` 是误放在宿主目录里的方舟组件

theme 同理：22 次引用里 19 次只指向 `MaaMotion.kt` 一个文件。

最终 `core:ui` = 13 个组件文件 + theme（除 `LogColors.kt`）+ 6 条通用文案，约 2.4k 行。
`LogColors.kt` 留在 `:app`：它依赖 `data/model` 里的日志模型，那批东西的去处是
`core:common`，把它塞进 UI 模块是错误的分层。

### 抽方舟前必须先解开的一个数据陷阱

`TaskChainNode.config` 的类型是 sealed 的 `TaskParamProvider`，而 kotlinx 的多态判别符
**默认取全限定类名**。十个配置类都没有 `@SerialName`，`JsonUtils.common` 也没设
`classDiscriminator` —— 于是用户设备上的存档里写着：

```json
{ "config": { "type": "com.aliothmoon.maadroid.data.model.FightConfig" } }
```

**包名进了存档。** 把这些类挪进 `engine/arknights` 会让所有已装用户的任务链与配置档
读不出来，表现为任务链变空、用户以为配置丢了。

解法是把判别符与包名解绑：给十个类各加 `@SerialName`，值**固定为旧的全限定名**。
它现在是一个持久化字符串而不是包引用，所以：

- 不要改成短名 —— 同样等于数据丢失
- 不要跟着新包名更新 —— 同上

`TaskConfigWireFormatTest` 钉住了这件事，也顺带定义了迁移的验收标准：
**方案正确的判据是那几条断言一条都不用改**。已做变异验证 —— 去掉任一 `@SerialName`
或把它改成短名，用例都会失败。

### 下一个前置：引擎读设置需要一个契约

判别符解绑之后，试搬 `data/model` 的方舟任务配置（26 个文件 2,768 行），撞到了真正的墙。
依赖闭包实测如下：

| 被依赖 | 处数 | 状态 |
|---|---|---|
| `maa/task/`（`MaaTaskParams`） | 23 | 方舟，3 个文件 55 行、零外部依赖，可随时搬 |
| `data/resource/` | 7 | 方舟，23 个文件 2,611 行 —— **但它依赖宿主设置** |
| `R.string` | 22 个 id | 可控，随模块自带 res |
| `utils.JsonUtils` | 3 | 已在 `core:remote`，加依赖即可 |

墙在 `data/resource/` 上：它 import `data.preferences.AppSettingsManager`（3 处）与
`data/config/MaaPathConfig`（5 处，而后者自己也依赖 `AppSettingsManager`）。
搬过去会立刻触犯「引擎不得依赖宿主应用层」—— 这不是搬文件能解决的。

这一环已解开，而且**不需要造设置契约** —— 量清之后发现三处「依赖宿主设置」里两处是误判：

| 处 | 实情 |
|---|---|
| `MaaPathConfig` | 只需要「数据是否落在提权目录」**一个布尔值**。而该设置本就「进程内固定、改了要重启」，所以在装配点读一次传进去即可 |
| `ResourceDataManager.displayLanguageCode` | 只是枚举→字符串的便利函数，转换本该由调用方（宿主）做；`load()` 早就只收字符串 |
| `BackgroundImageStore` | **不是方舟的** —— 它读写「自定义背景图」偏好，是宿主的 UI 功能，只是被误放在 `data/resource/` |

为一个布尔值造一整套 `HostSettings` 接口是过度设计。真正的教训是：**先量再设计** ——
按「它 import 了宿主设置类」下判断会得出「需要设置契约」，按「它到底读了什么」下判断
只需要改一个构造参数。

`data/config` 现已零宿主设置依赖。`data/resource` 对 `TaskChainState` 的依赖也已解开 ——
那原本是一个**三方互咬的环**：

```
data/model  ──需要──▶  data/resource   （MaaCoreVersion / MiniGameTextRegistry）
data/resource ─需要─▶  TaskChainState  （clientType）
TaskChainState ─需要─▶ data/model      （TaskChainNode / TaskProfile / 各配置类）
```

成环的话三者谁都搬不进 `engine/arknights`。破环点选最细的那一环：`ActivityManager`
实测只用到 `chainState.clientType` 一个属性，换成 `() -> String` 的 provider 即可
（必须是 provider 而非快照，用户切换渠道服要立即生效）。

剩下 `data/model ↔ data/resource` 仍互相依赖，但两者**同属方舟**，作为一个单元一起搬即可，
不构成阻塞。

koin 的静态图校验（`AppModuleVerifyTest`）在此处正好发挥了作用：构造参数改成内联提供后
它立刻报缺定义，按文件已有约定用 `injectedParameters` 放行即可 —— 清点预言的「抽模块时
这个测试第一个红」确有其事。

### `core:common` 为什么不依赖 Compose

`UiText` 的类型与 `resolve(Context)` 在 `core:common`，而 `@Composable` 的取值器
`asString()` 在 `core:ui`。这个拆分不是洁癖：`FightConfig`、`ActivityManager`、
`CopilotManager` 这些**非 UI 类**都在构造 `UiText`（配置校验、资源加载、异常原因都要产出
面向用户的文案，而那时拿不到 Context 也不知当前语言），若把整个 `UiText` 放进 `core:ui`，
它们就得为一个数据类型背上 Compose 依赖。

同时纠正两处原先的判断 —— 它们看着像通用模型，实则是方舟的：

- `data/model/LogLevel.kt` 与 `LogColorRole.kt` 的取值大半是**公招星级**（`RECRUIT_STAR_1..6`）
  与**肉鸽事件**（`ROGUELIKE_*`），是伪装成通用日志模型的方舟枚举，该随
  `engine/arknights` 走而非进 `core:common`。只有 `LogSeverity`（TRACE/MESSAGE/INFO/
  WARNING/ERROR）是真通用，但它仅 2 处使用，暂不搬。
- `formatToolboxSyncTime` 只有方舟三个面板在用，同样归方舟。

`LogSeverity` 与 `EngineEvent.LogLevel` **刻意不合并**：后者是引擎→宿主的事件词汇，
前者是宿主**持久化**的过滤级别（改动取值会让已存日志读不出来），两者分属不同层。

一处解耦值得记下：`ThemeMode` 原本是 `AppSettingsManager` 的嵌套枚举，于是 `Theme.kt`
想下沉就得连整个设置管理器一起拖走。主题模式本就属于主题，故提到 `core:ui`；
宿主的设置只负责持久化它的 `name`（**枚举名即持久化值，不可改名**，改了会让已装用户的
主题设置读不出来）。同理 `MaaDroidTheme` 不再直接调宿主的日志调色板，改为只提供
`LocalIsDarkTheme`，由宿主自己在其上叠加。

## 两层引擎注册

引擎要在**两个进程**里各注册一次，因为两类引擎的执行位置不同：

| | 明日方舟 | 边狱公司 |
|---|---|---|
| 核心实现 | MaaCore（C++，JNA 加载） | Kotlin + OpenCV + ONNX |
| 跑在哪 | **提权进程**（native 库只能在那里访问帧缓冲） | **App 进程**（OpenCV / onnxruntime 的 Java 绑定在 `app_process` 里加载不可靠） |
| 怎么取帧 | 进程内直接访问 native 帧缓冲 | 经共享内存跨进程取帧 |
| 怎么注入 | 进程内直接调 `InputControlUtils` | 经 AIDL 转发 |

```
提权进程                              App 进程
┌────────────────────────────┐      ┌──────────────────────────────┐
│ MaaDroidRemoteService      │      │ EngineSetup                  │
│   init { register(方舟) }  │      │   register(ArknightsProvider)│
│                            │      │   register(LimbusProvider)   │
│ RemoteEngineRegistry       │      │ EngineRegistry               │
│   getEngineService(id)     │◀AIDL▶│   engine(id) / profiles()    │
│                            │      │                              │
│ FrameChannel（共享内存）   │──帧─▶│ RemoteDeviceHandle           │
└────────────────────────────┘      └──────────────────────────────┘
```

`RemoteService.aidl` 只有游戏无关的 `IBinder getEngineService(String engineId)` —— 它不认识任何具体引擎，引擎自己把 binder 转回自己的接口。这是「一个 App 控多个游戏」在进程层的关键。

引擎在提权侧还通过 `RemoteEngineFactory.onRemoteSetup` / `versionInfo` 自述初始化与版本，避免 `RemoteServiceImpl` 里出现某个游戏的专属调用。

## 资源热更：各引擎跟随各自上游

宿主的更新服务只遍历 `EngineRegistry.allResourcePacks()`，两个上游的差异被 `ResourcePackSpec` 吸收：

| | 明日方舟 | 边狱公司 |
|---|---|---|
| 上游发布形态 | 直接发布 `MaaResource`（GitHub + MirrorChyan） | 只发 249 MB Windows 整包，无清单、无逐文件校验 |
| 我们怎么做 | 沿用既有下载链路，只做声明 | CI 从其 tag 重打包成约 43 MB 带 sha256 清单的包 |
| 内置于 APK | 是（`assets/MaaSync/MaaResource`） | 否，首次使用时下载 |
| 兼容门闸 | 由 MaaCore 版本 stamp 保证 | 清单协议版本 + `min_engine_version` + `required_actions` |

边狱的门闸解决的是这个问题：**上游改流程 / 模板 / 阈值应当无感跟随，但上游若新增了本 App 未实现的动作，必须在装载前拒绝并提示升级**，而不是挂机到一半崩在某个节点上。

相关文件：`scripts/pack_engine_resource.py`、`.github/workflows/limbus-resource.yml`、`engine/limbus/actions.txt`。

## 边狱引擎：为什么照抄上游的数据结构

`engine-limbus` 的流水线模型（`PipelineNode` / `PipelineRegistry`）字段名与语义严格照抄上游 `workflow/task_node.py` 与 `task_registry.py`。**照抄的目的是让上游改动能经资源包热更直接生效、不需要转换层** —— 这也是选 LALC 而非 AALC 移植的根本理由：业务流程住在数据里（133 个节点），不在 Kotlin 里。

几处容易被「优化」掉、而改了不会报错只会静默走错分支的细节：

| 细节 | 改了会怎样 |
|---|---|
| `interrupt` 缺省 `["error_handler"]`，但 `error.json` 的节点必须清空 | 异常处理自我递归 |
| 模板按**基名**注册，`img/zh` 与 `img/en` 同名文件是语言变体 | 按基名去重会丢掉语言覆盖 |
| 目录逐层累积打 tag（`ego_gifts` / `ego_gifts_Burn`） | 镜牢「按体系挑饰品」失效 |
| `click` 的 `target` 缺省是空**字符串**，即「点识别结果」 | 上游 10 个只配 template 的 click 节点什么都不点 |
| `next` 取声明序第一个命中，不是分数最高 | 优先级错乱 |
| `enable && (hit xor inverse)` | inverse 反了会变成「在主界面时才回主界面」 |
| 队伍轮换取 `<cfg>_check` 节点计数的模 | 每轮都用第一套队伍 |
| 黑名单在体系展开**之后**生效 | 拉黑的饰品被买回来 |

断引用与重名一律让装配失败，绝不产出半可用流水线。

### 与上游有意的行为差异

| 处 | 上游 | 本项目 | 为什么 |
|---|---|---|---|
| 识别持续不中 | 无限空转 | 8000 步后报「疑似死循环」 | 上游会让用户对着不动的界面干等 |
| 未知 `recognition` | `raise` 炸掉任务链 | 该分支不命中 + 告警 | 本该由兼容门闸在装载前拦住；漏到运行期时一个分支走不通远好过全盘崩 |
| `recognize_result` 为空时点击 | `IndexError` | 跳过并记日志 | 识别不中是有定义的状态 |
| 节点计数 | 写在节点 `params` 里原地自增 | 外置到 `ActionContext` | 节点要能被资源包整体替换，故不可变 |

### 识别能力现状

识别未命中返回空结果；模型缺失、初始化或推理失败则明确报错，避免与画面里没有目标混淆。

- **OCR**：已接入（`ocr/` 下 `PpOcrEngine` / `DbDetector` / `CtcDecoder` / `TextMerge` / `OcrGeometry`）。
  - 模型随资源包下发（PP-OCRv5 det 4.6 MB + rec 15.9 MB）。**字符表嵌在 rec 模型的 `metadata_props["character"]` 里**（18383 个字符），不必另向 PaddleOCR 取字典，也就没有「字典与模型版本对不上」这类风险。
  - 装载时核对字符表组装后的类别数（18383 + 空格 + blank = 18385）与模型输出层维度；不一致则拒绝连接并报出两个数字。
  - 归一化用 mean=std=0.5，**与三个分类器的 ImageNet 那组不同**，混用会让检测整体失准。
  - 仍需真机验证实际命中率。
- **三个 ONNX 分类器**：已接入。`mirror_legend`（130×110/8 类/单标签）与 `skill_icon`（80×80/7 类/单标签）走 argmax；`mirror_path`（224×224/9 连接/**多标签**）走 sigmoid 逐位判定，阈值取 `training_config.json` 的 `best_thresholds`，上游 v5.0.0 未提供故回退 0.5。连接时试运行三个模型，检查输出形状和数值；加载或推理失败会报错。
- **四个专用匹配器**：`AdvancedTemplateMatcher` 提供 BGR 直方图评分、ORB + FLANN-LSH、多尺度、原始灰度精确匹配。裁剪与坐标还原共用识别器适配层；多尺度循环响应取消。修复上游彩色 CLAHE 通道错误、奇数模板 ROI 偏移和特征点 2× 坐标未还原的问题。
- **`battle_winrate` 的 EGO 主动触发**：已接入罪人头像检测、带坐标的技能分类、危险拼点筛选和逐卡 0% 侵蚀标识。按 `ego_enable` 决定是否执行；确认面板关闭才继续，丢帧不会被视作成功。技能定位复用上述多尺度匹配。
- **语言表**：按当前资源包和游戏语言读取 `config/language`，中文名称匹配后保留原资源标识；资源更新后下一次运行重新加载，不改动用户黑白名单。

算法与取帧适配层已通过主机 native 测试；Android 游戏内命中率、输入响应和完整流程仍待真机验证。详见 [页面与执行对照](LIMBUS_UI_PARITY.md)。

## 接入一个新游戏

1. 在 `engine/` 下新建目录 `engine/<游戏>/`，在 `settings.gradle.kts` 加
   `include(":engine:<游戏>")`，依赖 `:engine:api`
2. 实现 `GameProfile`（包名候选、`DisplaySpec`、`Capability`、资源包）
3. 实现 `AutomationEngine`；跑在 App 进程就用 `DeviceHandle` 取帧与注入，
   跑在提权进程则额外实现 `RemoteEngineFactory`
4. 实现 `EngineUi` 提供任务面板
5. 在 `EngineIds` 加常量，在 `EngineSetup` 加一行 `register`

`core:*` 与既有引擎均不需要改动 —— 这一条由上面的边界契约保证，不是靠约定。
照 `engine/limbus/` 的目录形状抄即可。
