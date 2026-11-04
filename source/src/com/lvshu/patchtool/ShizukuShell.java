package com.lvshu.patchtool;

import android.content.pm.PackageManager;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 以 shell / root 身份执行命令。
 *
 * API 13 的 rikka.shizuku.Shizuku 里已经没有 newProcess 了，
 * 但服务端 AIDL（moe.shizuku.server.IShizukuService / IRemoteProcess）
 * 一直都在，直接用它的 Stub 即可，和官方旧版 API 走的是同一条路。
 */
public final class ShizukuShell {

    private static final String[] ENV = new String[]{
            "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin"
    };

    private ShizukuShell() {
    }

    public static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean permissionGranted() {
        try {
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int shellUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void requestPermission(int requestCode) {
        Shizuku.requestPermission(requestCode);
    }

    /** 以 shell 身份执行 `sh &lt;scriptPath&gt;`，返回退出码。 */
    public static int runScript(String scriptPath) throws Exception {
        IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        if (service == null) throw new IllegalStateException("Shizuku 服务不可用");
        IRemoteProcess proc = service.newProcess(new String[]{"sh", scriptPath}, ENV, "/");
        if (proc == null) throw new IllegalStateException("Shizuku 没有返回进程（权限未授予？）");
        try {
            return proc.waitFor();
        } finally {
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}