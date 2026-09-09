# MAA 与 LALC 共用 ONNX Runtime

当前共用 `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` 的完整 C 库与 Java/JNI 绑定。版本集中在 `gradle/libs.versions.toml`；方舟和边狱均声明严格版本约束。APK 每个 ABI 只含一份 `libonnxruntime.so`，MaaCore 通过 C API 调用它，边狱通过同一 AAR 的 `libonnxruntime4j_jni.so` 调用它。

## 为什么需要同时检查原生符号

735 诊断包的手机日志记录了两次边狱初始化失败：第一次在 `native.classifier.prepare` 阶段，链接器找不到 `OrtGetApiBase`；第二次因同一个类初始化已经失败而报 `NoClassDefFoundError`。两次都已创建虚拟显示、启动游戏并取到画面，OpenCV 也已加载。

当时 MaaCore v6.17.2 需要 `OrtGetApiBase@VERS_1.19.2`，Java/JNI 1.20.0 需要 `OrtGetApiBase@VERS_1.20.0`，打包的 `pickFirsts` 却只保留了 MaaCore 附带的 1.19.2。链接器在进入 `OrtGetApiBase()->GetApi(version)` 之前就检查符号版本，C API 的运行时版本协商无法解决这个冲突。

## 构建处理

`PrepareMaaNativeLibrariesTask` 对每个构建变体、每个选中的 ABI 执行：

1. 从方舟模块该变体实际解析出的 ONNX Android AAR 提取 C 库与 JNI 库，使用当前 NDK 的 `llvm-readelf` 检查 ELF 元数据。严格版本约束同时传递给消费它的 APK。
2. 核对 MaaCore、Java/JNI 及其他依赖 ONNX 的 Maa 原生库所需的全部 `Ort*` 符号、版本和架构。
3. 校验通过后，把 Maa 原生库复制到 `engine/arknights/build/generated/maa-native/<variant>/jniLibs`，省略重复的 `libonnxruntime.so`，由完整 Android AAR 提供唯一的 C/JNI 组合。

下载目录位于 `engine/arknights/src/main/jniLibs`；不会修改任何 ELF 字节。Android 打包只读取生成目录。`libonnxruntime.so` 不再允许通过 `pickFirsts` 忽略冲突。

MAA 上游更新到另一 ONNX ABI 时，构建会报告具体缺失符号并停止。维护者需选择与新 MaaCore 兼容的 Android AAR，同时验证 LALC 模型；两者匹配后仍共用一套库。游戏资源更新继续独立进行，原生运行时的变化需要新 APK。

## 验证

```sh
sh gradlew :build-logic:test :engine:arknights:prepareDebugMaaNativeLibraries -Pmaa.abi=all
python3 scripts/verify_native_runtime.py path/to/app.apk --readelf /path/to/ndk/llvm-readelf
```

APK 检查器从真实产物检查两套引擎的动态依赖、版本化符号及其提供者，能检出上述 735 包的错误。

本次统一前，已用 macOS arm64 的 ONNX Runtime 1.19.2 CPU 执行 LALC v5.0.0 的五个模型。三个分类模型使用资源中配置的输入尺寸，OCR 检测使用 `1×3×736×1280`，OCR 识别使用 `1×3×48×320`，输出形状和有限数值检查全部通过。这验证了模型对该版本的兼容性；Android 端完整游戏流程仍需真机验证。
