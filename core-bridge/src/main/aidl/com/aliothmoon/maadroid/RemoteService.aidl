package com.aliothmoon.maadroid;

import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import com.aliothmoon.maadroid.ITouchEventCallback;
import com.aliothmoon.maadroid.remote.PermissionGrantRequest;
import com.aliothmoon.maadroid.remote.PermissionStateInfo;

interface RemoteService {

    oneway void destroy() = 16777114; // Destroy method defined by Shizuku server

    void exit() = 1; // Exit method defined by user

    String version() = 2;

    void test(in Map<String,String> map) = 3;

    void screencap(int width, int height) = 4;

    boolean setForcedDisplaySize(int width, int height) = 6;

    boolean clearForcedDisplaySize() = 7;

    // 按 engineId 取引擎服务。core-bridge 不认识任何具体引擎：
    // 引擎在提权进程侧通过 RemoteEngineRegistry 注册，此处只按 id 转交 binder。
    // 这是「一个 App 控制多个游戏」的进程层接口。
    IBinder getEngineService(String engineId) = 9;

    int setup(String userDir,boolean isDebug) = 10;

    PermissionStateInfo grantPermissions(in PermissionGrantRequest request) = 11;

    void setMonitorSurface(in Surface surface) = 12;

    boolean setVirtualDisplayMode(int mode) = 13;

    int startVirtualDisplay() = 14;

    void stopVirtualDisplay() = 15;

    // contact: 手指 id 0..15，与 MotionEvent pointer id 一致
    oneway void touchDown(int x, int y, int contact) = 17;

    oneway void touchMove(int x, int y, int contact) = 18;

    oneway void touchUp(int x, int y, int contact) = 19;

    // 整体取消当前手势，预览退出时仍按着的手指由远端释放
    oneway void touchCancel() = 42;

    // 后台模式游戏实时帧率，未监控返回 -1
    float getGameFps() = 43;

    oneway void setDisplayPower(boolean on) = 20;

    boolean setPlayAudioOpAllowed(String packageName, boolean isAllowed) = 21;

    int pid() = 22;

    int isAppAlive(String packageName) = 23;

    oneway void heartbeat(int pid) = 24;

    void setVirtualDisplayResolution(int width, int height, int dpi) = 25;

    oneway void setTouchCallback(ITouchEventCallback callback) = 26;

    boolean startActivity(in Intent intent) = 27;

    boolean isPackageInstalled(String packageName) = 28;

    boolean isAppOnVirtualDisplay(String packageName) = 29;

    oneway void setForceFullscreenOnVirtualDisplay(boolean enabled) = 30;

    // 调试用：抓取当前帧缓冲，编码为 PNG 写入 dirPath 目录（由远端 shell 进程直接落盘，
    // 避免跨进程读取 ashmem 被 SELinux 拒绝）。返回保存的绝对路径，失败返回 null。仅调试模式 UI 调用。
    String captureFramePng(String dirPath) = 31;

    // 把漂移到其它 display 的应用任务拉回虚拟显示器，成功返回 true
    boolean moveAppToVirtualDisplay(String packageName) = 32;

    int unlock(String credential) = 33;

    int lockAndSleep() = 34;

    int testUnlock(String credential) = 35;

    oneway void startGestureRecord(int timeoutMs) = 36;

    String pollGestureRecord() = 37;

    oneway void cancelGestureRecord() = 38;

    int unlockWithGesture(String gestureJson) = 39;

    int testUnlockGesture(String gestureJson) = 40;

    // HyperOS 发岛时短断 com.xiaomi.xmsf 网络
    boolean setPackageNetworkingEnabled(String packageName, boolean enabled) = 41;

    // ---- MaaCore 独立数据目录（/data/local/tmp，见 CoreDataDir）----
    // 内置资源与 stamp 不符时从 apkPath 重解
    boolean ensureCoreResources(String apkPath, String stamp) = 44;

    boolean applyCoreHotUpdate(in ParcelFileDescriptor zip) = 45;

    String getCoreResourceVersion() = 46;

    boolean putCoreFile(String relPath, in ParcelFileDescriptor src) = 47;

    // 独立目录 debug/ 下的文件相对路径，导出日志时拉取
    List<String> listCoreDebugFiles() = 48;

    ParcelFileDescriptor openCoreDebugFile(String relPath) = 49;

    boolean clearCoreData() = 50;

    // ---- 帧通道与按键：供跑在 App 进程的 Kotlin 引擎（边狱）使用 ----
    // MaaCore 在提权进程内直接访问帧缓冲，不需要这些；而 App 进程的引擎只能跨进程取帧。
    // 走常驻共享内存：映射一次，之后每帧一次 memcpy（1280x720 BGR 约 2.6 MB，亚毫秒级），
    // 识别频率只有 1–5 fps，开销可忽略；如此 OpenCV 与 onnxruntime 可留在普通 App 进程。

    // 建立/取回常驻共享内存。容量按 width*height*3（BGR）分配。失败返回 null
    SharedMemory openFrameChannel(int width, int height) = 51;

    // 触发一次拷贝，返回 [width, height, stride, seq]；无可用帧或容量不足返回 null
    // （不做截断：截断帧会让模板匹配得到错误结果）
    long[] grabFrame() = 52;

    oneway void closeFrameChannel() = 53;

    // 边狱的 Android 客户端认硬件按键，流水线里的 key 节点直接映射 keycode
    // （enter→66、esc→111、p→44，取自 AALC 已验证的映射表）
    oneway void keyDown(int keyCode) = 54;

    oneway void keyUp(int keyCode) = 55;
}
