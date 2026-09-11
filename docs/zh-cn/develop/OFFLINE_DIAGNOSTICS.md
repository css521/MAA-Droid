# 离线崩溃诊断与日志导出

适用于“点击 Limbus 开始后 App 退出、无法接 USB”的排障场景。重新打开应用后，使用现有日志导出的分享或保存到文件入口即可获取 ZIP。诊断记录不依赖 Maa 资源准备、外部资源目录、Shizuku/Root 服务或网络。本改动提供证据采集能力，不代表已经定位或修复具体 native 崩溃。

## 接入 API

```kotlin
import com.maadroid.app.diagnostics.AppDiagnostics

AppDiagnostics.initialize(context)
AppDiagnostics.record("limbus", "native.init.before")
try {
    initializeNativeEngine()
    AppDiagnostics.record("limbus", "native.init.after")
} catch (error: Throwable) {
    AppDiagnostics.failure("limbus", "native.init.failed", error)
    throw error
}
```

固定 API 为 `initialize(Context)`、`record(component, phase, detail = "")`、`failure(component, phase, Throwable)`。`initialize` 幂等；未初始化时记录调用安全地忽略。`MaaApplication.onCreate` 在引擎装配、Koin 与远程服务初始化之前初始化诊断并安装崩溃 handler。原有 Koin `CrashHandler(MaaPathConfig)` 构造与 `init` 调用保留，后续重复初始化不会再包一层 handler。

`record` 在调用线程同步追加一条小记录并调用文件描述符 `sync`，没有等待刷新队列。适合点击开始、资源准备、创建显示器、首帧、模型加载前后等低频边界；不要每帧调用。记录包含 UTC 时间、毫秒时间戳、App 版本、ABI、Android API、PID、进程名、启动 session、component、phase 与短 detail。`failure` 先写异常类型边界，再保存异常栈。

只传组件名、阶段名、尺寸、包名等少量事实。不要传完整任务配置、对象序列化、口令、token、Authorization 或 Cookie。新增文本记录会去除常见配置转储并对常见凭据键、认证头和 URL 用户信息脱敏；这不能代替调用方选择非敏感内容。

## 设备内路径和保留范围

| 路径（相对于应用内部目录） | 内容与上限 |
| --- | --- |
| `files/diagnostics/events.log` | 当前阶段日志；单文件 256 KiB，单条不超过 4 KiB |
| `files/diagnostics/events.1.log` 至 `events.3.log` | 三代旧日志；事件日志总计不超过 1 MiB，`.1` 比 `.3` 新 |
| `files/diagnostics/java_crashes/crash_*.txt` | 最近 10 份 Java 异常栈，每份最多 1 MiB；含 cause、suppressed 与线程信息 |
| `cache/export/maa_logs_日期时间_随机后缀.zip` | 分享与 SAF 共用的 ZIP 生成入口；保留最近四份已生成包 |

阶段日志和 Java 栈跨 writer/进程重启保留，导出不会清空源文件。正常异常栈完整保存；极大栈有明确截断标记，超过 8192 字符的单行会被省略。文件锁保护并发 writer 与轮转，快照在同一锁下复制，避免把轮转中途的文件集合打包。目录不可用、空间不足或单份文件复制失败不会从诊断 API 抛出异常；单份快照失败仍尝试复制其余文件。

`CrashHandler` 保存 Java 未捕获异常后，将原线程、原 Throwable 交给安装前的 Android handler，保留系统记录退出原因的机会。前任 handler 缺失、返回或抛错时仍执行终止兜底。Java handler 无法拦截 SIGSEGV/SIGABRT、系统直接杀进程或断电；native 崩溃前已完成的同步边界记录是主要线索。

## ZIP 内容和失败隔离

1. 先复制内部诊断，生成并关闭一份独立可读的 ZIP，包含 `diagnostics/events*.log`、`diagnostics/java_crashes/`、`diagnostics/environment.txt`、`diagnostics/collection_status.txt` 和说明文件。
2. 再用独立候选 ZIP 附加 Android 历史退出信息。仅候选包完整结束并同步成功后才原子替换第一份 ZIP。
3. 用另一个候选 ZIP 尝试附加旧日志、设备详情与远程日志；读取异常、候选包写入失败、远程 Binder 异常或超时均保留上一份已完成的诊断 ZIP。

系统历史采集与旧日志采集各最多等待 5 秒、各只有一个后台 worker。Binder 若忽略中断，已超时的 worker 也不能在稍后覆盖返回的 ZIP；该来源仍忙时，后续导出跳过它，不持续创建线程。旧日志 worker 不占用历史记录 worker。成就上报另设 250 毫秒等待上限，其异常不会隐藏已完成的 ZIP。中途退出留下的暂存目录/候选文件在超过一天后的导出中清理。

旧日志继续使用 `gui/`、`error_logs/`、`crash_logs/`、`asst.log`、`logcat/` 等原始相对路径，远程目录仍由 `MaaFiles.EXPORT_REMOTE_DIR` 指定。`diagnostics/`、元数据文件名、`export/` 和远程目录被保留，旧文件不能覆盖；拒绝绝对路径、`..`、反斜杠与重复条目。旧日志按原有近七天规则筛选，最多尝试 256 份本地文件与 128 个远程路径，单文件最多 32 MiB，总附加数据最多 128 MiB。按文件长度截取的日志可能在写入中继续增长，包中的状态会注明边界；远程大小未知时按单文件上限预留总预算。

`properties.txt` 使用少量 Android Build 字段，不再执行 `getprop` 子进程。`device_info.txt` 的资源、屏幕和后端详情只在可失败的附加阶段采集。若 `attachments_status.txt` 缺失，表示附加阶段失败、来源忙或超时，内部 `diagnostics/` 仍可用。读取到部分数据的附件会在状态文件中标出，不视为完整日志。

FileProvider 增加明确的内部 `cache/export/` 路径；分享使用 `application/zip` 与 URI 读授权，SAF 从同一 ZIP 生成方法复制内容。分享 Intent 创建失败、SAF 目标不可写时，内部已完成的 ZIP 不会被删除。

## Android 历史退出信息

API 30+ 调用 `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 8)`，最多取系统保留的最近八条：

- `reason` 数值与名称、`status`、`description`、`timestamp` 与时间、进程名、PID、importance；
- `pss_kb`、`rss_kb`，单位 KB，为系统记录的内存采样，可辅助判断模型加载时内存压力；不保证是死亡瞬间的峰值，零值不代表没有内存占用；
- 可用的 `traceInputStream` 以 `diagnostics/exit_history/trace_*.bin` 原始字节入包，每份最多 2 MiB，截断/缺失/读取失败在 `trace_status.txt` 说明。

native trace 可能需要 Android 12 / API 31，厂商也可能不提供。内容可能是 tombstone protobuf 二进制，不能一律按文本解析。API 28–29 在包中明确写明历史接口不可用。无记录、trace 为空或采集超时都不能证明未发生崩溃；提权进程使用其他 UID 时可能不在本应用历史中。

已有日志和系统原始 trace 保持原始内容，并不经过新增文本脱敏逻辑，分享前应检查。清除应用数据、卸载、轮转会丢失对应内部记录；系统可以清理缓存 ZIP。内部存储完全不可写时无法承诺生成 ZIP。`sync` 针对进程突然退出提供尽力持久化，不是断电或损坏存储的保证。应用必须能重新启动并进入现有导出入口；启动即再次崩溃的情况需要其他恢复方式。

## 本地验证

可在仓库根目录运行专项 JVM 测试，不调用 Gradle，也不下载依赖：

```sh
python3 app/src/test/java/com/maadroid/app/diagnostics/run_jvm_tests.py
```

脚本使用缓存的 Kotlin 2.4.10、JUnit 4.13.2 和 JDK 17，编译/运行产物仅写入临时目录，结束后删除。`DIAGNOSTIC_JAVA` 可指定 Java 可执行文件，`DIAGNOSTIC_JAR_CACHE` 可指定依赖缓存目录。

覆盖跨 writer 重建保留、按字节轮转、UTF-8、并发 writer、目录故障及恢复、部分快照失败隔离、完整 cause/suppressed、异常栈数量/大小限制、凭据与配置省略、无 Maa 目录打包、附件失败后原包逐字节不变、历史记录不受后续失败影响、不可中断来源超时及迟到结果隔离、损坏/丢失附件、有界二进制 trace、ZIP 路径防碰撞与旧日志路径兼容。

专项测试不执行 Android API、真实 native crash、系统历史采样、FileProvider 或 SAF；没有进行设备验证。Android 全包构建/测试由主线程执行，其结果不计入此处的 JVM 验证。

2026-09-09 实际执行上述脚本：Kotlin 编译成功，JUnit `OK (28 tests)`，运行时间 0.803 秒，进程退出码 0；未运行 Gradle。主线程另反馈 app 整合测试 784 项全部通过（包含诊断测试），此为主线程报告，本说明未独立复跑全包测试。源码与测试已冻结，收到最终整合测试通知后仅补充本段验证记录。
