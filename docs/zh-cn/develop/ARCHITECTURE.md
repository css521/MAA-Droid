# 架构说明

本项目是**多游戏自动化宿主**：同一个 App 内可切换游戏，每个游戏是一个可插拔的引擎模块。

## 模块

目录结构即依赖方向，分层一眼可见：

```
app/                    宿主：导航、游戏切换、资源中心、定时、通知、引擎装配
engine/
  api/                  插件契约：GameProfile / AutomationEngine / EngineUi / ResourcePackSpec
  arknights/            明日方舟（MaaCore）        ┐ 待抽出，现仍在 :app
  limbus/               边狱公司（移植自 LALC）    ┘ 每个游戏一个模块
core/
  bridge/               native 截图桥、AIDL 契约、输入注入
  remote/               提权进程实现、虚拟显示器、设备侧抽象实现
  common/               偏好、日志、通知、定时、更新框架   ┐ 待拆，现仍在 :app
  ui/                   Compose 组件、主题、悬浮窗        ┘
hidden-api/             framework 隐藏 API 桩，仅编译期
tooling/
  annotation-api/       偏好 KSP 的注解
  ksp-processor/        偏好 KSP 的处理器
build-logic/            构建约定插件
```

Gradle 路径与目录一致：`:engine:limbus`、`:core:bridge`、`:tooling:ksp-processor`。

依赖方向**严格单向**：`app → engine:<游戏> → engine:api → core:* → hidden-api`。

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

第三条规则同时**编码了抽取 `engine/arknights` 的前置条件**：方舟代码现在用着仍在
`:app` 里的偏好、通知与 Compose 组件，直接搬过去会立刻触犯它 —— 也就是必须先把这些
通用能力下沉到 `core:common` / `core:ui`，顺序不能颠倒。

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

未实现的一律返回空表（等价于「识别不中」）而不抛异常，且每种只提示一次：

- ~~**OCR**~~：已接入（`ocr/` 下 `PpOcrEngine` / `DbDetector` / `CtcDecoder` / `TextMerge` / `OcrGeometry`）。
  - 模型随资源包下发（PP-OCRv5 det 4.6 MB + rec 15.9 MB）。**字符表嵌在 rec 模型的 `metadata_props["character"]` 里**（18383 个字符），不必另向 PaddleOCR 取字典，也就没有「字典与模型版本对不上」这类风险。
  - 装载时核对字符表组装后的类别数（18383 + 空格 + blank = 18385）与模型输出层维度；不一致即**停用 OCR** 并报出两个数字。宁可不做 OCR 也不输出乱码 —— 乱码经模糊匹配去买饰品会买错东西。
  - 归一化用 mean=std=0.5，**与三个分类器的 ImageNet 那组不同**，混用会让检测整体失准。
  - 仍需真机验证实际命中率。
- ~~**三个 ONNX 分类器**~~：已接入。`mirror_legend`（130×110/8 类/单标签）与 `skill_icon`（80×80/7 类/单标签）走 argmax；`mirror_path`（224×224/9 连接/**多标签**）走 sigmoid 逐位判定，阈值取 `training_config.json` 的 `best_thresholds`，上游 v5.0.0 未提供故回退 0.5。镜牢寻路已是带权择优；分类器不可用时仍退回「上到下依次尝试」，能推进但不择优。
- **四个冷门匹配器**：暂以灰度模板匹配近似（上游各只有一处调用）。
- **`battle_winrate` 的 EGO 主动触发**：依赖罪人头像检测与带坐标的技能分类。当前只按 `p` 推进战斗；该功能受 `ego_enable` 控制且默认关闭，故默认路径与上游无差异。

不做「简化版猜坐标」：点错位置会打断流程，比不做更糟。

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
