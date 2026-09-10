# 边狱安卓适配 · 交接文档

交接日期：2026-09-10。当前分支 `main @ 9f8adc3`，工作树干净，`:engine:limbus` 408 用例全绿。
可实测产物：`out/apk/MAA-Droid-765-arm64-v8a.apk`（Thread 链路已真机验证通过）。

---

## 一、下一步要做的：App 内开发模式 · 自动采集素材底片

### 为什么

适配安卓的**唯一真实阻塞**是缺「这台设备的真帧」。上游 LALC 的素材来自 Steam 客户端，
在安卓上大面积失配；而判断某张素材该怎么改、改成什么，必须看识别器**实际参与匹配的那一帧**。

不要用聊天/截图渠道传来的图片当依据——那条链路（`1280x720 渲染 → 手机屏 → 截屏 → 压缩 → 还原`）
会磨掉小素材依赖的细节，系统性压低所有分数。**本次交接前已因此连续判断错三次**（见第四节）。

### 设计（不需要碰 Compose UI）

| 项 | 做法 |
|---|---|
| 开关 | `BuildConfig.DEBUG`，或读标记文件 `Maa/debug/limbus/.capture`（建/删文件即开关，便于真机现场切换） |
| 采集点 | `LimbusRecognizer` 里每次 `frames.grab()` 之后。该处本就在取帧，零额外开销 |
| 去重 | 缩到 64×36 灰度求帧间差，只存与上一张明显不同的。否则走一遍主页会得到几千张重复 |
| 命名 | `Maa/debug/limbus/frames/<seq>_<当时识别的节点或素材名>.png`。**名字里必须带节点名**，否则导出后分不清哪张是队伍页、哪张是关卡页 |
| 上限 | 60 张左右，满即停，避免写满存储 |
| 落盘位置 | 必须在 `.../files/Maa/debug/` 这棵树下——日志导出器已在收集它（导出的 zip 里能看到
`infrast/enter_facility/*_raw.png`）。**同时把绝对路径写进 `onDiagnostic` 日志**，导出器万一没收就能手动取 |

`LimbusEngine` 里推导目录的辅助函数（沿资源目录向上找名为 `Maa` 的祖先，找不到退到同级 `debug`）
本次已写过，可从 git 历史里的失败尝试中取，或直接重写，只有十几行。

### 必须按这个顺序改，因为它同时能定位一个未查明的问题

**本次交接前尝试过两次，都失败，且根因未查明。** 症状：给 `LimbusRecognizer` 加东西之后，
编译报一批 `Unresolved reference 'toMat' / 'warned' / 'templateOf'`——全是该类**后面**声明的成员
在**前面**的方法里解析不到。第一次用 Python 脚本盲替换，怀疑花括号被破坏；第二次改用 Edit
工具逐处精确插入（构造参数、函数、调用点、companion 常量），**错误依然完全相同**，
说明「脚本破坏结构」的推测是错的，真正原因另有其他。

未验证的怀疑方向：`tooling/ksp-processor` 对该类的处理、或给主构造器添加带默认值的
`File?` 参数触发了什么。**这一步值得查明而不是绕开**，因为它会挡住此后所有对该类的改动。

所以：

```bash
rm -rf engine/limbus/build          # gradlew clean 清不掉 intermediates/built_in_kotlinc
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sh gradlew :engine:limbus:testDebugUnitTest --no-build-cache    # 基线，应 408 绿
```

1. **只**加构造参数 `private val captureDir: File? = null,` → 立刻
   `sh gradlew :engine:limbus:compileDebugKotlin`
   - **若此步就报那批 unresolved → 根因就是「给这个类加构造参数」，这才是要查的真问题**
   - 若干净，继续
2. 加采集函数（含去重、上限）→ 再编译
3. 加调用点 → 再编译
4. `LimbusEngine` 里传 `captureDir` → 编译 + 跑测试
5. 打包、真机走一遍主页→镜牢→关卡页→战斗，导出日志

### 做完之后回路就闭合了

```
真机走一遍 → 导出日志(含帧) → scripts/audit_templates.py 离线跑
→ 拿到失配清单 → 从真帧裁替代素材 → audit 再验(几秒) → 打包实测
```

**只有最后一步需要真机。** 这是从「一轮几十分钟」到「一轮几秒」的关键。

---

## 二、已确立的结论（都有数据支撑，不要重新推导）

### 1. OCR 类识别在安卓上没问题，模板类识别全部失配

| 类型 | 状态 | 证据 |
|---|---|---|
| OCR（关卡编号、`12/12`、饰品名、Lv 标签） | **可用** | `60`→`G0` 字形还原修完后 Thread 链路真机通过 |
| 模板匹配（`details`、`skip_battle`、`skill_*`…） | **全灭** | 10 张流水线判据实测 0.555~0.820，无一过 0.85 阈值 |

`details` 与真实控件 **负相关 −0.143**，从帧里裁出的区域自匹配 0.951 —— 说明匹配流水线本身
正常（CLAHE + 模糊 + `TM_CCOEFF_NORMED` 无问题），**是素材和控件根本不是一回事**。

流水线实际引用的模板**只有 50 个**（562 张里大部分是饰品/卡包/罪人头像，供动作代码按名查）。
范围比想象小得多。

### 2. 画面几何没有问题，不存在「一个缩放系数修全部」的捷径

真机 `frame.geometry` 实测：`内容区=0,0 1280x720`，满幅无黑边。那些 `1264x720`/`1276x720`
是画面本身边缘偏暗被 `threshold=8` 判成黑，不是 letterbox（letterbox 会是稳定、对称、成块的）。

**因此缩放假设作废，`screenshotScale` 一刀修 562 张的方案不存在。**（该阈值偏敏感，
若保留这个诊断建议提到 16~24。）

### 3. AALC 的素材不能直接换，它的识别风格不同

AALC（`/Users/css521/project/java/AhabAssistantLimbusCompany`）确实支持模拟器
（`module/automation/input_handlers/simulator/mumu_control.py`），素材来自安卓客户端，基准 2560×1440，
自带归一化（`1440/set_win_size`，坐标同步换算）。**但：**

- **没有 `details`、没有 `skip_battle`** —— 它的流程不靠这些判据
- 489 张素材里 **256 张是 2560×1440 整帧**（`*_assets.png`），因为它是「裁区域 + 与整帧参考比」，
  不是「小模板 + 全图搜索」
- 实测一张小素材 `thread_continuous_combat_show_box` 在降质帧上得 0.967（同条件下 LALC 素材全灭），
  证明其素材与安卓客户端几何相容——**它的价值是当底片，不是当替换文件**

**结论：无论用谁的逻辑，都必须从安卓画面裁出正确模板。没有自动手段**——哪一块是 `details`
是语义判断。而底片应优先用自己设备的真帧（AALC 的是别人 MuMu 上某个版本的画面）。

### 4. 不要全改成 AALC 逻辑

`app/` 13065 行（PySide GUI，全弃）、`tasks/` **8088 行 27 个模块**（核心流程，须重写 Kotlin）、
`module/automation` 5626 行，实际要搬约 **15000 行**；而 `engine/limbus` 现有 16324 行已跑通
EXP 全链、Thread 已验证。

**更关键**：AALC 流程是 Python 代码，MAA-Meow 选 LALC 正是因为它的流程在 JSON 里可热更
（见 `LimbusEngine` 类注释）。改造成 AALC 逻辑会让「上游每次更新都要改代码发 APK」
从担忧变成必然，而且**仍然要做同样的裁素材工作**。

值得借的三样（都可增量加，不用推倒）：基准分辨率归一化、区域裁剪+整帧比对（作为小模板的
补充判据——17×17 的 `skill_blunt` 在无技能界面上得分反而更高，这种小图注定不可靠）、256 张整帧当底片。

### 5. MaaFramework：方向对，但现在不是时候

平台化（多游戏共用后端、资源即数据、`roi` 相对定位、节点 `timeout` 天然消掉自环）它都对，
而且模块骨架已就位（`settings.gradle.kts` 的注释写明「加一个游戏 = 加一个 engine 目录」）。

**闸门是实的**，`scripts/setup_maa_core.py --maafw-probe v5.13.0` 跑出的确定清单：

```
同名 6 个，全部大小不同（不能两份共存，必须选一份）：
  libopencv_world4.so               60.8MB vs 23.7MB   ← 差 2.6 倍，最硬的一处
  libonnxruntime.so                 26.6MB vs 25.1MB
  libfastdeploy_ppocr.so            21.9MB vs 21.6MB
  libMaaUtils.so                    14.2MB vs 14.2MB   ← 同尺寸，很可能同源，非障碍
  libMaaAndroidNativeControlUnit.so  2.8MB vs  2.9MB
  libc++_shared.so                   1.7MB vs  8.9MB   ← MAA 那份未 strip
仅 MaaFramework 有 9 个共 98MB，其中 libMaaFramework.so 49.7MB
```

OpenCV 差 2.6 倍是因为 MaaFramework 的 world4 编进了 FeatureMatch 需要的 `xfeatures2d` 等模块。
**下一步是符号核对**（`llvm-readelf -d` 看 NEEDED、`llvm-nm -D --undefined | grep cv::` 看需要哪些符号，
与 MAA 那份 `--defined-only` 求差集），差集为空则可复用 23.7MB 那份、零体积增长。

**但它修不了当前的 bug**——素材还是错的。应等第一节做完、有了正确行为基线，
再拿它跑同一条链做对照。

补充：`libMaaAndroidNativeControlUnit.so` 和 `libMaaUtils.so` **本来就是 MaaFramework 的产物**，
随 MAA 发布包进来的；项目已有 `override_maafw_control_unit`（`scripts/setup_maa_core.py:286`）
从 MaaFramework release 取 .so。所以约束是**版本对齐**而非名字冲突——从同一个 release zip
多取几个 .so 即天然一致。这比「从源码重建改 SONAME」便宜一个数量级。

### 6. APK 285MB 的构成（减体积别从 OpenCV 下手）

```
250.1 MB / 9329 个   assets/MaaSync/MaaResource   ← 明日方舟全客户端资源，59%
 85.5 MB /   35 个   *.dex                        ← debug 不跑 R8
 76.8 MB /   13 个   lib/ native（含两份 OpenCV 47MB）
  3.1 MB            assets/shizuku.apk
```

顺序应是：① 方舟资源按客户端按需下载 ② release + R8 ③ 最后才是 OpenCV 统一。前两项占 80%，都不碰 native。

---

## 三、可用的工具

| 工具 | 用途 |
|---|---|
| `scripts/audit_templates.py` | **离线**素材审计：把上游素材逐张打进真机原帧，输出失配清单。复现了 Kotlin 侧 `TemplateMatcher` 的完整流水线，分数与真机可比（同素材同帧：本机 0.669 / 设备 0.652）。支持 `--scales` 多尺度、`--expect` 映射、`--csv` |
| `scripts/setup_maa_core.py --maafw-probe TAG` | MaaFramework 与 MaaCore 的 native 库共存清单，纯分析不改构建 |
| `.maa-cache/converter-env/bin/python3` | 项目自带的 venv，有 cv2 5.0.0 + numpy，离线实验都用它 |
| `scripts/verify_native_runtime.py` | 打包后核对 ELF 依赖与 ORT 版本化符号，每次出包都应跑 |

---

## 四、本次交接前犯过的错，不要重复

1. **拿聊天渠道的截图当原帧量像素**（两次）。先推出「上下黑边、UI 缩到 0.8 倍」，
   又据此断言 MaaFramework 的整帧 resize 能修当前 bug。**两个结论都作废。**
   → 只信识别器实际参与匹配的那个 Mat。
2. **只用一个数据点就宣称离线回路已验证**。`details` 在本机与设备都失败所以看起来一致，
   实际那条回路对**能命中**的素材完全不可信（`main_drive_no_text` 本机 0.555，设备当场识别成功——
   因为它走的是 `AndroidHomeNavigation` 那条自带缩放的旁路）。
3. **用 Python 脚本盲替换 Kotlin 类体**。改坏后回滚，且**未查明根因**就归因于花括号，
   后来用 Edit 精确插入仍复现同样错误，说明归因是错的。
   → 改 Kotlin 一处一编译；`rm -rf engine/limbus/build`，不要指望 `gradlew clean`。
4. **把「加可观测性」判断成浪费**。曾说「在快要删掉的代码上加诊断是浪费」——错的，
   当前引擎的诊断数据是验证任何迁移正确性的**对照基准**。

---

## 五、建议的总体路线

1. **开发模式采集底片**（第一节）← 阻塞项
2. 从真帧裁替代素材，按任务链推进（EXP✅ / Thread✅ / 镜牢 → ），每条链只处理它用到的那几张
3. 加**基准分辨率归一化层**：坐标与素材统一过换算，之后换分辨率、混用不同基准素材都不改代码
   （这是「不要写死代码」的正解；`screenshotScale` 参数已存在，AALC 的 `1440/set_win_size` 是参考实现）
4. 加区域裁剪+整帧比对，作为小模板的补充判据
5. 有了正确行为基线之后，才评估 MaaFramework 作为平台后端

**不要做**：全改 AALC 逻辑；现在动 MaaFramework；为省体积先动 OpenCV；无据修改坐标或素材。
