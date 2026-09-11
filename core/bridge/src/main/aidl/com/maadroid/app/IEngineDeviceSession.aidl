package com.maadroid.app;

import android.os.SharedMemory;
import android.view.Surface;

// One exclusive capture/display lease. Closed binders can never act on a later lease.
interface IEngineDeviceSession {
    int getDisplayId() = 1;
    SharedMemory openFrameChannel() = 2;
    long[] grabFrame() = 3;
    void closeFrameChannel() = 4;
    void touchDown(int x, int y, int contact) = 5;
    void touchMove(int x, int y, int contact) = 6;
    void touchUp(int x, int y, int contact) = 7;
    void touchCancel() = 8;
    void keyDown(int keyCode) = 9;
    void keyUp(int keyCode) = 10;
    boolean startApp(String packageName) = 11;
    void stopApp(String packageName) = 12;
    boolean matchesDisplaySpec(int width, int height, int dpi) = 13;
    // Synchronous and idempotent; also invoked when the owner's binder dies.
    void close() = 14;
    // Attaches/detaches the UI preview only; capture and automation keep running.
    void setPreviewSurface(in Surface surface) = 15;
    // FPS of the game owned by this lease; a closed lease cannot read a later game's FPS.
    float getGameFps() = 16;
}
