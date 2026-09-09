package com.aliothmoon.maadroid;

import com.aliothmoon.maadroid.MaaCoreCallback;
import android.os.ParcelFileDescriptor;


interface MaaCoreService {


    oneway void destroy();


    boolean SetUserDir(String path);

    boolean LoadResource(String path);

    boolean SetStaticOption(int key, String value);


    boolean CreateInstance(MaaCoreCallback callback);

    void DestroyInstance();

    boolean hasInstance();

    boolean SetInstanceOption(int key, String value);

    int AsyncConnect(String path, String address, String config, boolean block);

    void SetConnectionExtras(String name, String extras);

    boolean Connected();

    int AppendTask(String type, String params);

    boolean SetTaskParams(int taskId, String params);

    int[] GetTasksList();

    boolean Start();

    boolean Stop();

    boolean Running();

    boolean BackToHome();

    int AsyncClick(int x, int y, boolean block);

    int AsyncScreencap(boolean block);

    ParcelFileDescriptor GetImage();

    ParcelFileDescriptor GetImageBgr();

    String GetVersion();

    String GetUUID();
}
