package com.lvshu.patchtool;

/**
 * 执行通道选择：有 root 且用户勾了「优先 root」就走 su，否则走 Shizuku。
 * 两条通道并存，谁可用用谁，不删任何一条。
 */
public final class Shell {

    private static volatile boolean preferRoot = false;

    private Shell() {
    }

    public static void setPreferRoot(boolean v) {
        preferRoot = v;
    }

    public static boolean preferRoot() {
        return preferRoot;
    }

    /** 当前实际会不会走 root。 */
    public static boolean usingRoot() {
        return preferRoot && RootShell.available();
    }

    /** 有没有任何一条通道可用。 */
    public static boolean ready() {
        if (usingRoot()) return true;
        return ShizukuShell.available() && ShizukuShell.permissionGranted();
    }

    /** 当前执行身份（root 通道恒为 0）。 */
    public static int uid() {
        if (usingRoot()) return 0;
        return ShizukuShell.shellUid();
    }

    public static String desc() {
        if (usingRoot()) return "root（su，uid=0）";
        if (ShizukuShell.available() && ShizukuShell.permissionGranted()) {
            int u = ShizukuShell.shellUid();
            return "Shizuku（uid=" + u + (u == 0 ? "，root 激活" : "，shell 激活") + "）";
        }
        return "未就绪";
    }

    public static int runScript(String scriptPath) throws Exception {
        if (usingRoot()) return RootShell.runScript(scriptPath);
        return ShizukuShell.runScript(scriptPath);
    }
}
