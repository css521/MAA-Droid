# 架构说明

本项目是**多游戏自动化宿主**：同一个 App 内可切换游戏，每个游戏是一个可插拔的引擎模块。

## 模块

```
app                宿主：导航、游戏切换、资源中心、定时、通知、引擎装配
├─ engine-arknights   明日方舟（MaaCore）        ┐
├─ engine-limbus      边狱公司（移植自 LALC）    ┘ 各游戏一个模块
├─ engine-api      插件契约
├─ core-ui         Compose 组件、主题、悬浮窗
├─ core-common     偏好、日志、通知、定时、更新框架
├─ core-remote     提权进程实现、虚拟显示器、设备侧抽象实现
├─ core-bridge     native 截图桥、AIDL 契约、输入注入
└─ hidden-api      framework 隐藏 API 桩
```

依赖方向**严格单向**：`app / engine-* → engine-api → core-* → hidden-api`。

`core-*` 不得引用任何 `engine-*` 或应用层 —— 整个可扩展性建立在这一条上，而倒挂只需一次「顺手 import」就会发生，故由 `ModuleBoundaryContractTest` 扫描源码钉住。

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
| 我们怎么做 | 沿用既有下载链路，只做声明 | CI 从其 tag 重打包成约 25 MB 带 sha256 清单的包 |
| 内置于 APK | 是（`assets/MaaSync/MaaResource`） | 否，首次使用时下载 |
| 兼容门闸 | 由 MaaCore 版本 stamp 保证 | 清单协议版本 + `min_engine_version` + `required_actions` |

边狱的门闸解决的是这个问题：**上游改流程 / 模板 / 阈值应当无感跟随，但上游若新增了本 App 未实现的动作，必须在装载前拒绝并提示升级**，而不是挂机到一半崩在某个节点上。

相关文件：`scripts/pack_engine_resource.py`、`.github/workflows/limbus-resource.yml`、`engine-limbus/actions.txt`。

## 边狱引擎：为什么照抄上游的数据结构

`engine-limbus` 的流水线模型（`PipelineNode` / `PipelineRegistry`）字段名与语义严格照抄上游 `workflow/task_node.py` 与 `task_registry.py`，包括几处容易被「优化」掉的细节：

- `interrupt` 缺省值是 `["error_handler"]`，但来自 `error.json` 的节点必须清空 —— 否则异常处理自我递归。装配顺序不可调整。
- 模板按**基名**注册，`img/zh` 与 `img/en` 下同名文件是语言变体，靠切换语言目录覆盖。资源包不可按基名去重。
- 断引用与重名一律让装配失败，绝不产出半可用流水线。

照抄的目的是让上游改动能经资源包热更直接生效、不需要转换层。

## 接入一个新游戏

1. 新建 `engine-<游戏>` 模块，依赖 `engine-api`
2. 实现 `GameProfile`（包名候选、`DisplaySpec`、`Capability`、资源包）
3. 实现 `AutomationEngine`；跑在 App 进程就用 `DeviceHandle` 取帧与注入，跑在提权进程则额外实现 `RemoteEngineFactory`
4. 实现 `EngineUi` 提供任务面板
5. 在 `EngineIds` 加常量，在 `EngineSetup` 加一行 `register`

`core-*` 与既有引擎均不需要改动。
