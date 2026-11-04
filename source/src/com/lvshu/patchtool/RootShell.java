package com.lvshu.patchtool;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 直接走 su 执行（设备已 root）。不需要 Shizuku 参与。
 *
 * 和 Shizuku 通道的区别：
 *  - 身份是真正的 uid=0，脚本会自动走 /data/media/0，绕开 FUSE 那些毛病
 *  - 能把文件 chown 给游戏、能写 root 拥有的目录
 *  - 第一次用会弹 root 授权（Magisk/KernelSU 之类）
 */
public final class RootShell {

    /** -2 还没探测过，-1 不可用，0 root 可用 */
    private static volatile int state = -2;
    /** 探到能用的 su 路径，缓存起来 */
    private static volatile String suBin = null;

    private static final String[] SU_CANDIDATES = {
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su"
    };

    private RootShell() {
    }

    public static int probe() {
        if (suBin == null) suBin = findSu();
        if (suBin == null) {
            state = -1;
            return state;
        }
        String out = exec("id -u", 8000);
        String last = lastLine(out);
        if ("0".equals(last) || "uid=0(root)".equals(last)) {
            state = 0;
        } else {
            state = -1;
        }
        return state;
    }

    public static boolean available() {
        return state == 0;
    }

    public static int state() {
        return state;
    }

    /** 跑 `su -c "sh <path>"`，返回退出码。 */
    public static int runScript(String scriptPath) throws Exception {
        if (suBin == null) suBin = findSu();
        if (suBin == null) throw new IllegalStateException("找不到可用的 su");
        Process p = new ProcessBuilder(suBin, "-c", "sh " + quote(scriptPath))
                .redirectErrorStream(true).start();
        try {
            drain(p);
            return p.waitFor();
        } finally {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 挨个试候选 su 路径，能跑通 `id -u` 的那个就用它。 */
    private static String findSu() {
        for (String bin : SU_CANDIDATES) {
            if (testExec(bin)) return bin;
        }
        return null;
    }

    private static boolean testExec(String bin) {
        Process p = null;
        try {
            p = new ProcessBuilder(bin, "-c", "id -u").redirectErrorStream(true).start();
            String out = drain(p);
            int rc = p.waitFor();
            String last = lastLine(out);
            return rc == 0 && ("0".equals(last) || "uid=0".equals(last) || "uid=0(root)".equals(last));
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String exec(String cmd, long timeoutMs) {
        Process p = null;
        try {
            String bin = suBin != null ? suBin : findSu();
            if (bin == null) return "";
            p = new ProcessBuilder(bin, "-c", cmd).redirectErrorStream(true).start();
            final Process fp = p;
            Thread watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(8000);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        fp.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }, "su-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            String out = drain(p);
            int rc = p.waitFor();
            if (rc != 0) return "";
            return out;
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String lastLine(String s) {
        if (s == null) return "";
        String[] parts = s.trim().split("\n");
        return parts.length == 0 ? "" : parts[parts.length - 1].trim();
    }

    private static String drain(Process p) {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 16384) sb.setLength(0);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
