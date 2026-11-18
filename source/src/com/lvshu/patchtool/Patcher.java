package com.lvshu.patchtool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 补丁包的解压 / 打包 / 部署脚本生成（不依赖 root，全部本地完成）。 */
final class Patcher {

    private Patcher() {
    }

    /** 目录里所有 .zip 补丁，按文件名排序。 */
    static List<File> listPatches(File dir) {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                out.add(f);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    /**
     * 把补丁 zip 解压到 outDir，内部路径剥掉开头的 files/，
     * 这样 outDir 就等于游戏 files 目录的镜像。
     *
     * @return 解出的文件数
     */
    static int extract(File zip, File outDir, Progress progress) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + outDir);
        }
        outDir.setReadable(true, false);
        outDir.setExecutable(true, false);
        String base = outDir.getCanonicalPath();
        int count = 0;
        ZipFile zf = openZip(zip);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String rel = stripPrefix(name);
                if (rel.length() == 0) continue;
                File out = new File(outDir, rel);
                String canon = out.getCanonicalPath();
                if (!canon.startsWith(base + File.separator)) continue; // 防目录穿越
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录: " + parent);
                }
                InputStream in = zf.getInputStream(e);
                try {
                    copy(in, out);
                } finally {
                    closeQuietly(in);
                }
                out.setReadable(true, false);
                count++;
                if (progress != null && (count % 20 == 0)) {
                    progress.onProgress("解压中… " + count + " 个文件");
                }
            }
        } finally {
            try {
                zf.close();
            } catch (IOException ignored) {
            }
        }
        return count;
    }

    /**
     * 打开 zip，自动处理 GBK 文件名。
     *
     * java.util.zip 默认按 UTF-8 解析文件名，但国内 Windows 打的包几乎都是 GBK，
     * 中文名会变成乱码（含 \uFFFD），路径匹配和文件名定位全部失效。
     * 先按 UTF-8 开，扫一遍条目名，发现乱码就用 GBK 重开。
     */
    private static ZipFile openZip(File zip) throws IOException {
        ZipFile zf = new ZipFile(zip);
        if (looksGarbled(zf)) {
            closeQuietly(zf);
            try {
                zf = new ZipFile(zip, java.nio.charset.Charset.forName("GBK"));
            } catch (Throwable t) {
                // GBK 也开不了就退回 UTF-8 结果，至少不崩
                zf = new ZipFile(zip);
            }
        }
        return zf;
    }

    private static boolean looksGarbled(ZipFile zf) {
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (n != null && n.indexOf('\uFFFD') >= 0) return true;
        }
        return false;
    }

    private static String stripPrefix(String name) {
        if (name.startsWith("files/")) return name.substring(6);
        int i = name.indexOf("/files/");
        if (i >= 0) return name.substring(i + 7);
        return name;
    }

    // ---------------- 定位（补丁包不是 files/ 结构时用） ----------------

    /**
     * 名称归一化：只留字母和数字，并转小写。
     *
     * 注意必须保留中文（Character.isLetterOrDigit 对 CJK 返回 true）。
     * 早先用的 replaceAll("[^a-z0-9]", "") 会把中文全删，
     * 导致「李白.assetbundle」和「韩信.assetbundle」都归一成 "assetbundle"，
     * 两个不相干的文件互相错位覆盖。
     */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 把 gameRels（相对游戏 files 目录的路径）建成 同名 / 模糊 两张索引。 */
    static java.util.Map<String, java.util.List<String>>[] buildIndex(java.util.List<String> gameRels) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.List<String>>[] m = new java.util.Map[2];
        m[0] = new java.util.HashMap<String, java.util.List<String>>();
        m[1] = new java.util.HashMap<String, java.util.List<String>>();
        for (String rel : gameRels) {
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            put(m[0], name, rel);
            put(m[1], norm(name), rel);
        }
        return m;
    }

    private static void put(java.util.Map<String, java.util.List<String>> mm, String k, String v) {
        java.util.List<String> l = mm.get(k);
        if (l == null) {
            l = new java.util.ArrayList<String>();
            mm.put(k, l);
        }
        l.add(v);
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * 读出 ab 的内部名（UnityFS 里的 CAB-xxx）。读不到返回 null。
     *
     * 实测（300大作战 3.60.1）：同一个资源的位置，APK 原版 114,629 B 和补丁版 280,183 B
     * 内容完全不同，CAB 却一模一样 → CAB 是「按资源路径/名字」派生的，不随内容变。
     * 所以它能当资源身份键用：拿补丁 ab 的 CAB 去游戏 CAB 表里查，就能定位到原位置。
     */
    /**
     * 建 CAB 索引的 shell 脚本（v3.8 快速版）。
     *
     * 旧版每个文件跑 4 次 fork：
     *   c=$(head -c 8192 "$f" | sed ... | grep -a -o 'CAB-...' | head -1)
     * 26184 个文件 ≈ 13 万次进程创建，实测 26.5 条/秒、16 分 29 秒。
     *
     * 新版：find 出清单，xargs 每 64 个文件起一个 sh，fork 降到几百次。
     * 判定规则：只看前 8192 字节，取第一处 CAB- + 精确 32 位十六进制，
     * 输出 "CAB-xxx<TAB>绝对路径"（v3.12.1 起锁定 32 位，避免贪婪多吃一位）。
     *
     * 不依赖 uid=0：只用 sh 内建与 toybox 常备命令，
     * Shizuku(uid=2000) 和 root 都能跑。
     *
     * @param outFile   输出 tsv（App 可读的公共目录）
     * @param selfCheck 是否额外用旧算法抽 50 条，写到 <outFile>.chk 供 App 比对
     */
    static String buildCabIndexScript(String gameStr, File outFile, boolean selfCheck) {
        // v3.13：游戏资源就在 files 下的 data 与 flies 两个目录，建表时一起扫。
        String rootData = gameStr + "/data";
        String rootFlies = gameStr + "/flies";
        String out = esc(outFile.getAbsolutePath());
        String chk = esc(outFile.getAbsolutePath() + ".chk");

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 酸败补丁助手 —— 建 CAB 索引（快速版）\n");
        sb.append("G1='").append(esc(rootData)).append("'\n");
        sb.append("G2='").append(esc(rootFlies)).append("'\n");
        sb.append("O='").append(out).append("'\n");
        sb.append("CHK='").append(chk).append("'\n");
        sb.append("if [ ! -d \"$G1\" ] && [ ! -d \"$G2\" ]; then echo \"[$(date '+%H:%M:%S')] [索引] ✗ 目录都不存在: $G1 / $G2\"; exit 3; fi\n");
        // 【双通道兼容】root 与 Shizuku(uid=2000) 都要能跑。
        //   • 索引目录若由对方身份创建，本身份可能写不进去 → 先测目录可写，再强制删旧文件。
        //   • 旧版只用 ": > $O"，在文件属主是 root 时 Shizuku 清空会静默失败，
        //     脚本继续 >> 追加，导致行数虚高、数据错乱（2026-11-11 实测 6172 个文件产出 26354 行）。
        sb.append("D=$(dirname \"$O\")\n");
        sb.append("mkdir -p \"$D\" 2>/dev/null\n");
        sb.append("if ! touch \"$D/.w_test\" 2>/dev/null; then echo \"[$(date '+%H:%M:%S')] [索引] ✗ 索引目录不可写（属主可能是另一个身份），请手动删除: $D\"; exit 5; fi\n");
        sb.append("rm -f \"$D/.w_test\" 2>/dev/null\n");
        sb.append("rm -f \"$O\" \"$CHK\" 2>/dev/null\n");
        sb.append("if ! : > \"$O\" 2>/dev/null; then echo \"[$(date '+%H:%M:%S')] [索引] ✗ 无法创建: $O\"; exit 2; fi\n");
        sb.append(": > \"$CHK\" 2>/dev/null\n");
        sb.append("L=\"$O.list\"\n");
        sb.append("rm -f \"$L\" 2>/dev/null\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] [索引] 身份 uid=$(id -u)  扫描根 $G1 $G2\"\n");
        // 必须 find -print0 与后续 tr '\000' '\n' 配对；两个目录各自扫，追加到同一份清单。
        // 若 find 用换行而后续按 \0 切，整份清单会被当成一个超长文件名，瞬间“扫完”且产出 0 行。
        sb.append("for g in \"$G1\" \"$G2\"; do [ -d \"$g\" ] && find \"$g\" -type f -name '*.assetbundle' -print0 2>/dev/null; done > \"$L\"\n");
        sb.append("TOTAL=$(tr -cd '\\000' < \"$L\" | wc -c | tr -d ' ')\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] [索引] 待扫描 $TOTAL 个 .assetbundle\"\n");
        sb.append("if [ \"$TOTAL\" = \"0\" ]; then echo \"[$(date '+%H:%M:%S')] [索引] ✗ 一个文件都没找到\"; rm -f \"$L\"; exit 4; fi\n");
        // 诊断实测（诊断_cab扫描/probe.log，2026-11-07 01:08）：
        //   • 瓶颈是 FUSE 读取，head -c 8192 约 15ms/文件，全量下限约 6~10 分钟；
        //   • toybox awk (20240311) 不保证支持 ENDFILE（gawk 扩展），且把二进制 ab
        //     当文本读会读完整个文件（几十 MB），比 head -c 8192 慢得多 → 不能用；
        //   • xargs -P 可用；
        //   • 「head -c 8192 + grep + awk 输出」在 xargs 子 shell 里产出真 TAB，
        //     full_test.tsv 的 hex 已验证（09 分隔、0A 结尾）。
        // 【禁用 -P 并行】实测（诊断_cab扫描/verify.log 2026-11-07 07:10）：
        //   xargs -P 4 -0 -n 64 在 toybox 上退出码 0、stderr 为空，
        //   但 26218 个文件只处理了 360 个就返回，剩余静默丢弃。
        //   这正是“3 秒扫完、产出 0 行”的真正原因。
        // 不加 -P 时 xargs 行为正常（probe.log: 1288 个 → 31s，完整处理）。
        // 错误流向导：以后扫描失败能看到真实原因（以前 stderr 全丢 /dev/null）。
        sb.append("ERR=\"$O.err\"\n");
        sb.append(": > \"$ERR\" 2>/dev/null\n");
        // 【v3.9】彻底不用 xargs。toybox 的 xargs 在本机实测会静默截断
        // （26218 个只处理 360 个，退出码 0、stderr 为空），无论是否带 -P。
        // 改成 find -print0 | tr '\0' '\n' | while read 逐条处理。
        //
        // 【v3.12 提速】每文件 fork 从 4 次降到 2 次：
        //   旧: head(1) + printf(1) + grep(1) + head -1(1) = 4 次
        //   新: head(1) + grep -m1(1)                      = 2 次
        // grep -m1 找到第一处就停，不会读完大文件；去掉 case 预筛（多一层判断反而慢）。
        // 实测（诊断_cab扫描/bench.log 2026-11-08）：448s→75s / 3000 个，
        // 外推全量约 11 分钟，且产出条数与旧版完全一致（不丢数据）。
        sb.append("tr '\\000' '\\n' < \"$L\" | while IFS= read -r f; do\n");
        sb.append("  n=$((n+1))\n");
        sb.append("  [ $((n % 500)) -eq 0 ] && echo \"[$(date '+%H:%M:%S')] [索引] 进度 $n/$TOTAL\" >&2\n");
        // 【v3.12.1 修正】必须精确 32 位十六进制。
        //   旧写法 'CAB-[0-9a-fA-F]*' 是贪婪匹配，会把 CAB 后面紧跟的十六进制字节
        //   一起吃掉（实测 Shizuku 下产出 '...be9f' → '...be9f2'，多一位），
        //   导致索引里的 CAB 与补丁文件读出的 CAB 不相等，同名消歧全部失效。
        //   Unity 的 CAB 标识固定为 32 位，所以直接锁长度。
        sb.append("  c=$(head -c 8192 \"$f\" 2>/dev/null | grep -a -m1 -o 'CAB-[0-9a-fA-F]\\{32\\}')\n");
        sb.append("  [ -n \"$c\" ] && awk -v a=\"$c\" -v b=\"$f\" 'BEGIN{print a \"\\t\" b}'\n");
        sb.append("done >> \"$O\" 2>>\"$ERR\"\n");
        sb.append("XRC=$?\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] [索引] 循环退出码 $XRC\"\n");
        // xargs 自身的错误也记下
        sb.append("if [ -s \"$ERR\" ]; then\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] [索引] ⚠ 出现错误输出（前 20 行）:\"\n");
        sb.append("  head -n 20 \"$ERR\" | sed 's/^/  [err] /'\n");
        sb.append("fi\n");
        if (selfCheck) {
            // v3.10：自检改用与主扫描相同的抽取算法（head -c 8192 | grep -ao）。
            // 旧版用 sed 替换不可打印字符会截断二进制里的 CAB 串，与主扫描不等价，
            // 导致「一致 15、不一致 35」的假告警。现在两边同算法，自检才有意义。
            // 与主扫描严格同算法（v3.12 同步提速），否则自检会报假不一致。
            sb.append("# 抽样自检：同算法跑 50 条，供 App 比对等价性\n");
            sb.append("tr '\\000' '\\n' < \"$L\" | head -n 50 | while IFS= read -r f; do\n");
            // 与主扫描严格同算法（含 32 位锁定），否则自检会报假不一致。
            sb.append("  c=$(head -c 8192 \"$f\" 2>/dev/null | grep -a -m1 -o 'CAB-[0-9a-fA-F]\\{32\\}')\n");
            sb.append("  [ -n \"$c\" ] && awk -v a=\"$c\" -v b=\"$f\" 'BEGIN{print a \"\\t\" b}'\n");
            sb.append("done >> \"$CHK\" 2>/dev/null\n");
        }
        sb.append("GOT=$(wc -l < \"$O\" | tr -d ' ')\n");
        sb.append("rm -f \"$L\"\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] [索引] 扫描结束：共 $TOTAL 个，命中 $GOT 条\"\n");
        // 完整性校验：每个文件最多 1 条，结果远少于文件数说明扫描被截断。
        // （2026-11-07 的 -P 并行就是这种情况：退出码 0、无报错，26218 个只出 360 条）
        sb.append("if [ \"$TOTAL\" -gt 1000 ] && [ \"$GOT\" -lt $((TOTAL / 2)) ]; then\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] [索引] ✗ 结果异常偏少（$GOT/$TOTAL），扫描可能被截断\"\n");
        sb.append("fi\n");
        // 【关键】结果转存到 App 私有目录。
        //
        // 实测（2026-11-07 多次）：shell 侧确实写出了 26,218 条结果，
        // 但 App 进程 readText() 读同样是空，countLines() 也是 0。
        // 文件在 /storage/emulated/0 下（非 Android/data），但 App 走 FUSE 读仍拿不到内容。
        // 因此由 root 身份的脚本把成品拷进 App 私有目录，App 再读那份副本——
        // 私有目录不经过 FUSE，不存在可见性问题。
        // 【双通道兼容】结果转存：
        //   root(uid=0) 能写 App 私有目录 /data/data/<pkg>/files → 转那里最稳。
        //   Shizuku(uid=2000) 跨 uid 写不进私有目录 → 退回索引目录下的副本。
        //   App 侧按“私有副本优先、索引目录副本兜底”读取。
        sb.append("DSTP='/data/data/com.lvshu.patchtool/files/index_result.tsv'\n");
        sb.append("DSTC='/data/data/com.lvshu.patchtool/files/index_result.chk'\n");
        sb.append("if cp -f \"$O\" \"$DSTP\" 2>/dev/null && chmod 644 \"$DSTP\" 2>/dev/null; then\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] [索引] 结果已转存私有目录 $DSTP\"\n");
        sb.append("  cp -f \"$CHK\" \"$DSTC\" 2>/dev/null; chmod 644 \"$DSTC\" 2>/dev/null\n");
        sb.append("else\n");
        sb.append("  LD=\"$O.local.tsv\"\n");
        sb.append("  LC=\"$O.local.chk\"\n");
        sb.append("  if cp -f \"$O\" \"$LD\" 2>/dev/null && chmod 644 \"$LD\" 2>/dev/null; then\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] [索引] 私有目录不可写，结果已转存索引目录 $LD\"\n");
        sb.append("    cp -f \"$CHK\" \"$LC\" 2>/dev/null; chmod 644 \"$LC\" 2>/dev/null\n");
        sb.append("  else\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] [索引] ✗ 转存失败：$DSTP 与 $LD 都不可写\"\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("rm -f \"$ERR\"\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    static String cabOfFile(File f) {
        if (f == null || !f.isFile()) return null;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
            }
            // ISO-8859-1 把每个字节 1:1 映成 char，不会因编码把 ASCII 弄丢
            String s = new String(buf, 0, n, java.nio.charset.Charset.forName("ISO-8859-1"));
            int i = s.indexOf("CAB-");
            while (i >= 0) {
                int e = i + 4;
                while (e < s.length() && isHexChar(s.charAt(e))) e++;
                // 【v3.12.1】必须与索引一致的 32 位长度。
                // 旧版返回全部连续十六进制字符，若 CAB 后面紧跟十六进制字节会多几位，
                // 与索引里 32 位的 key 对不上，同名消歧直接失效。
                if (e - i - 4 >= 32) return s.substring(i, i + 4 + 32);
                if (e - i - 4 >= 20) return s.substring(i, e);
                i = s.indexOf("CAB-", i + 4);
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(in);
        }
        return null;
    }

    /**
     * ① 精确路径（含逐级剥皮）。命中返回游戏侧真实 rel，否则 null。
     *
     * 剥皮是必要的：补丁包常见多包一层，比如
     *   flies/data/android/assetbundle/sound/x.assetbundle   （目录名打错）
     *   李白/role/x.assetbundle                              （作者自建包装目录）
     * 去掉前缀后仍是「精确路径」，不是模糊匹配。
     */
    static String exactRel(String rel, java.util.Set<String> all) {
        if (rel == null || all == null) return null;
        if (all.contains(rel)) return rel;
        int cut = rel.lastIndexOf('/');
        String name = rel.substring(cut + 1);
        String d = cut < 0 ? "" : rel.substring(0, cut);
        while (true) {
            int p = d.indexOf('/');
            d = (p < 0) ? "" : d.substring(p + 1);
            String probe = (d.length() == 0) ? name : (d + "/" + name);
            if (all.contains(probe)) return probe;
            if (d.length() == 0) break;
        }
        return null;
    }




    /**
     * 说明 / 预览类文件：不是游戏资源，定位不到时就别往游戏目录里塞。
     * 反和谐开关 localmode.txt 是游戏真需要的，留个例外。
     */
    private static boolean isSideFile(String rel) {
        String n = rel.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp")
                || n.endsWith(".md") || n.endsWith(".htm") || n.endsWith(".html")
                || n.endsWith(".url") || n.endsWith(".log") || n.endsWith(".pdf")
                || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".mp4")) {
            return true;
        }
        if (n.endsWith(".txt")) return !n.endsWith("localmode.txt");
        return false;
    }



    /** 定位结果的一个条目：一个源文件可以对应多个目标（游戏里复用同一资源）。 */
    static final class Target {
        final String srcRel;
        /** true = 游戏里没有这个资源，按精确路径新建（只有精确路径允许新建）。 */
        boolean isNew;
        final java.util.List<String> dstRels = new java.util.ArrayList<String>();
        Target(String srcRel) {
            this.srcRel = srcRel;
        }
    }

    /** 待新建文件的汇总确认：返回 true 才写盘。 */
    interface Confirm {
        boolean confirmNew(java.util.List<String> paths);
    }

    /**
     * 精确路径新建的准入校验（v3.13）。
     *
     * 规则（按用户要求）：
     *   • 只认游戏 files 下的相对路径：绝对路径、含 . / .. 的路径一律拒绝；
     *   • 不允许写进 assets/（那是游戏 APK 内置资源，热更目录里放没用）；
     *   • 首级目录必须是游戏 files 下真实存在的目录，避免把「作者包装目录」
     *     （如 李白/role/x.assetbundle）当新路径，在游戏目录里建一堆垃圾目录。
     *
     * @param gameRoots 游戏 files 下一级目录名集合；null / 空表示不做首级校验
     */
    static boolean canCreateNew(String rel, java.util.Set<String> gameRoots) {
        if (rel == null || rel.length() == 0) return false;
        if (rel.charAt(0) == '/') return false;
        if (rel.indexOf('\\') >= 0) return false;
        String[] seg = rel.split("/");
        if (seg.length < 2) return false;               // 光一个文件名、没有目录结构，不当资源
        for (int k = 0; k < seg.length; k++) {
            String s = seg[k];
            if (s.length() == 0 || s.equals(".") || s.equals("..")) return false;
        }
        if (seg[0].equalsIgnoreCase("assets")) return false;
        if (gameRoots != null && !gameRoots.isEmpty() && !gameRoots.contains(seg[0])) return false;
        return true;
    }

    /**
     * 把补丁包里的路径归一成「游戏 files 下的真实相对路径」（v3.13）。
     *
     * 关键：不能无脑把 flies 剥光——游戏里 files/flies 是真实目录，
     * 包里多写一层时只剥「多余的那一层」，保留最内层真实目录：
     *   flies/flies/data/...  → 剥掉重复的第一层 → flies/data/...（落到第二层 flies）
     *   李白/data/...          → 剥掉作者的包装目录   → data/...
     *   data/... / flies/...   → 本来就是真实一级目录，原样保留
     *
     * 这条路不依赖游戏清单与 CAB 索引，所以新增文件也能算出最终落点。
     *
     * @return 归一后的路径；本身不合法（绝对路径、含 . 或 ..、空段、assets/）时返回 null
     */
    static String normalizedRel(String rel, java.util.Set<String> gameRoots) {
        if (rel == null) return null;
        rel = rel.replace('\\', '/');
        if (rel.length() == 0 || rel.charAt(0) == '/') return null;
        String[] seg = rel.split("/");
        if (seg.length < 2) return null;
        for (int k = 0; k < seg.length; k++) {
            String s = seg[k];
            if (s.length() == 0 || s.equals(".") || s.equals("..")) return null;
        }
        if (seg[0].equalsIgnoreCase("assets")) return null;
        int st = 0;
        while (st < seg.length - 1) {
            String cur = seg[st];
            String nxt = seg[st + 1];
            boolean dup = cur.equals(nxt);                        // flies/flies
            boolean wrapper = gameRoots != null && !gameRoots.isEmpty()
                    && !gameRoots.contains(cur) && gameRoots.contains(nxt);
            if (!dup && !wrapper) break;
            st++;
        }
        if (gameRoots != null && !gameRoots.isEmpty() && !gameRoots.contains(seg[st])) return null;
        StringBuilder sb = new StringBuilder(rel.length());
        for (int k = st; k < seg.length; k++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(seg[k]);
        }
        return sb.toString();
    }

    /**
     * 定位：① 精确路径 → ② 同名唯一 → ③ 同名多个+CAB 双条件校验（可一源多目标）。
     *
     * 规则（按用户要求）：
     *   • 同名 + CAB 也相同 → 确认是同一个资源，装。若有多个则全部装（游戏复用）。
     *   • 同名 + CAB 不同  → 不是目标，不装。
     *   • 不同名          → 不装。
     *   • 绝不猜。
     *
     * @param cabMap 游戏 CAB → 相对路径列表；null 表示本次未建表
     * @param srcCab 补丁源文件的 CAB，可为 null
     */
    static Target locateTarget(String rel, java.util.List<String> gameRels,
                               java.util.Set<String> all,
                               java.util.Map<String, java.util.List<String>> byName,
                               java.util.Map<String, java.util.List<String>> cabMap,
                               String srcCab) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);

        // ① 精确路径（含逐级剥皮）：只装路径指定的那一个
        String exact = exactRel(rel, all);
        if (exact != null) {
            Target t = new Target(rel);
            t.dstRels.add(exact);
            return t;
        }

        // 「需安装至安装包」类：目标是游戏 APK 内的 assets/，不是热更目录
        if (rel.toLowerCase(Locale.ROOT).indexOf("assets/android/assetbundle/") >= 0) {
            return null;
        }

        java.util.List<String> same = byName.get(name);
        if (same == null || same.isEmpty()) return null;

        // ② 同名唯一：不用 CAB 也能定
        if (same.size() == 1) {
            Target t = new Target(rel);
            t.dstRels.add(same.get(0));
            return t;
        }

        // ③ 同名多个：用 CAB 双条件校验
        if (cabMap != null && srcCab != null) {
            java.util.List<String> verified = new java.util.ArrayList<String>();
            for (String cand : same) {
                java.util.List<String> paths = cabMap.get(srcCab);
                if (paths != null && paths.contains(cand)) verified.add(cand);
            }
            if (verified.isEmpty()) return null;   // CAB 全不同 → 不是目标
            Target t = new Target(rel);
            t.dstRels.addAll(verified);             // 可能多个 → 全装
            return t;
        }

        return null;   // 无 CAB 表 → 不猜
    }

    /**
     * 定位并重排到 stage2。
     *
     * 两段式（v3.13）：
     *   ① 先只定位、不写盘，把「游戏里没有对应资源的精确路径」汇总出来；
     *   ② 交给调用方弹一次汇总窗确认：点是全部新建，点否只跳过这些新增，
     *      已有资源照常覆盖安装。
     *
     * 只有精确路径能新建；同名 / 剥皮 / CAB 这些模式仍然只覆盖、不新建。
     *
     * 定位顺序：① 精确路径（含逐级剥皮） → ② 同名唯一 → ③ 同名多个 + CAB 双条件。
     * 已删除的模糊匹配：105skin6（面板贴图）会被当成 105_skin6（英雄预制体），覆盖错文件。
     *
     * @param gameRoots 游戏 files 下一级目录名，用于校验新建路径（可为 null）
     * @param confirm   汇总确认回调（可为 null，表示一律不新建）
     * @return {命中数, 未定位数}
     */
    static int[] relocate(File stage, File stage2, java.util.List<String> gameRels,
                          java.util.Map<String, java.util.List<String>> cabMap,
                          java.util.List<String> skipped,
                          Progress progress, java.util.List<String> report,
                          java.util.Set<String> gameRoots, Confirm confirm) {
        java.util.Set<String> all = new java.util.HashSet<String>(gameRels);
        java.util.Map<String, java.util.List<String>>[] idx = buildIndex(gameRels);
        java.util.List<String> rels = new java.util.ArrayList<String>();
        walk(stage, "", rels);
        int miss = 0;
        int i = 0;
        java.util.List<Target> plan = new java.util.ArrayList<Target>();
        java.util.List<Target> fresh = new java.util.ArrayList<Target>();

        // —— 第一遍：只定位，不写盘 ——
        for (String rel : rels) {
            i++;
            String name = rel.substring(rel.lastIndexOf('/') + 1);

            // 说明/预览类文件：不是游戏资源，直接不动
            if (isSideFile(rel)) {
                miss++;
                if (report != null) report.add("✗ 跳过（说明/预览文件）：" + name);
                if (skipped != null) skipped.add(name + "（说明文件）");
                continue;
            }

            // 只有 ab 才去读 CAB（其他类型文件读也读不到，白花时间）
            String srcCab = null;
            if (rel.toLowerCase(Locale.ROOT).endsWith(".assetbundle")) {
                srcCab = cabOfFile(new File(stage, rel));
            }

            Target t = locateTarget(rel, gameRels, all, idx[0], cabMap, srcCab);

            // 精确路径在游戏里找不到 → 按归一化路径新建（不依赖清单与 CAB），
            // 等汇总弹窗确认后再写盘。重复前缀只剥多余那层，真实 flies 会保留。
            if (t == null) {
                String newRel = normalizedRel(rel, gameRoots);
                if (newRel != null && canCreateNew(newRel, gameRoots)) {
                    Target nt = new Target(rel);
                    nt.isNew = true;
                    nt.dstRels.add(newRel);
                    fresh.add(nt);
                    if (progress != null && (i % 30) == 0) {
                        progress.onProgress("定位中… " + i + "/" + rels.size());
                    }
                    continue;
                }
            }

            if (t == null) {
                miss++;
                if (report != null) {
                    report.add("✗ 跳过：" + rel);
                    java.util.List<String> same = idx[0].get(name);
                    if (same != null && !same.isEmpty()) {
                        report.add("    游戏里同名 " + same.size() + " 处：");
                        int lim = Math.min(same.size(), 6);
                        for (int k = 0; k < lim; k++) report.add("      " + same.get(k));
                        if (same.size() > lim) report.add("      ……还有 " + (same.size() - lim) + " 处");
                        if (cabMap == null) {
                            report.add("    （本次没有 CAB 索引，无法确认哪个是同一资源）");
                        } else if (srcCab == null) {
                            report.add("    （这个文件里没读到 CAB，无法确认）");
                        } else {
                            report.add("    （CAB 对比：都不匹配 " + srcCab + "）");
                        }
                    } else {
                        report.add("    游戏里找不到同名文件");
                    }
                }
                if (skipped != null) skipped.add(name);
                continue;
            }

            plan.add(t);
            if (progress != null && (i % 30) == 0) {
                progress.onProgress("定位中… " + i + "/" + rels.size());
            }
        }

        // —— 汇总确认：一次列全，点否只跳过这些新增 ——
        boolean allowNew = false;
        if (!fresh.isEmpty()) {
            java.util.List<String> paths = new java.util.ArrayList<String>();
            for (Target t : fresh) paths.add(t.dstRels.get(0));
            if (report != null) {
                report.add("＋ 有 " + paths.size() + " 个文件在游戏里没有对应资源（精确路径）：");
                int lim = Math.min(paths.size(), 20);
                for (int k = 0; k < lim; k++) report.add("    " + paths.get(k));
                if (paths.size() > lim) report.add("    ……还有 " + (paths.size() - lim) + " 个");
            }
            allowNew = confirm != null && confirm.confirmNew(paths);
            if (report != null) {
                report.add(allowNew
                        ? "＋ 已确认：按精确路径新建 " + paths.size() + " 个文件"
                        : "✗ 已取消：跳过 " + paths.size() + " 个新建文件，已有资源照常安装");
            }
            if (!allowNew) {
                miss += fresh.size();
                if (skipped != null) {
                    for (Target t : fresh) {
                        skipped.add(t.srcRel.substring(t.srcRel.lastIndexOf('/') + 1) + "（未新建）");
                    }
                }
                fresh.clear();
            }
        }

        // —— 第二遍：写盘 ——
        int ok = 0;
        for (Target t : plan) {
            if (deployTarget(stage, stage2, t, report)) ok++;
            else miss++;
        }
        for (Target t : fresh) {
            if (deployTarget(stage, stage2, t, report)) ok++;
            else miss++;
        }
        return new int[]{ok, miss};
    }

    /**
     * 把一个定位结果写进 stage2。
     *
     * v3.10 修：旧版第一个目标用 renameTo 把源文件搬走，第二个目标再读源文件就 ENOENT
     * （实测 墨姐改歌姬修复/263.assetbundle 只替换了 1 处）。现在一律 copy，全部写完后统一删源。
     */
    private static boolean deployTarget(File stage, File stage2, Target t, java.util.List<String> report) {
        File src = new File(stage, t.srcRel);
        boolean anyOk = false;
        for (String dstRel : t.dstRels) {
            File dst = new File(stage2, dstRel);
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try {
                copy(new FileInputStream(src), dst);
                anyOk = true;
            } catch (IOException e) {
                if (report != null) report.add("搬移失败：" + dstRel + " / " + e.getMessage());
            }
        }
        if (!anyOk) {
            if (report != null) report.add("✗ 全部目标写入失败：" + t.srcRel);
            return false;
        }
        src.delete();
        if (report != null) {
            if (t.isNew) {
                report.add("＋ 该路径没有对应资源，按精确路径新建：" + t.srcRel);
                if (!t.srcRel.equals(t.dstRels.get(0))) {
                    report.add("    包内多层前缀已归一，真实目录保留：" + t.dstRels.get(0));
                }
                report.add("    已将 " + t.srcRel + " 移动到 " + t.dstRels.get(0));
            } else {
                String extra = t.dstRels.size() > 1 ? ("（共 " + t.dstRels.size() + " 处复用，全部替换）") : "";
                report.add("✓ " + t.srcRel + " → " + t.dstRels.get(0) + extra);
                for (int k = 1; k < t.dstRels.size(); k++) {
                    report.add("    另：" + t.dstRels.get(k));
                }
            }
        }
        return true;
    }

    private static void walk(File dir, String base, java.util.List<String> acc) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) walk(f, base + f.getName() + "/", acc);
            else acc.add(base + f.getName());
        }
    }

    /**
     * /storage/emulated/0/... → /data/media/0/...（root 直连真实数据区，绕开 FUSE）。
     * 非 root 身份没有权限走这条，返回原路径。
     */
    static String toDirectPath(String path) {
        String[] prefixes = new String[]{
                "/storage/emulated/0/", "/storage/self/primary/", "/sdcard/"
        };
        for (String p : prefixes) {
            if (path.startsWith(p)) return "/data/media/0/" + path.substring(p.length());
        }
        if (path.equals("/storage/emulated/0") || path.equals("/sdcard")) return "/data/media/0";
        return path;
    }

    /**
     * 生成 shell 侧部署脚本。
     *
     * @param directDataPaths shell 是 root 时传 true：全部走 /data/media/0，
     *                        避免 FUSE 掉线导致 "Transport endpoint is not connected"。
     */
    /** 去掉 /storage/emulated/0、/data/media/0 这类卷前缀，得到相对路径（用于挂载点替换）。 */
    private static String stripVolumePrefix(String abs) {
        String[] pres = {"/storage/emulated/0/", "/data/media/0/",
                "/mnt/pass_through/0/emulated/0/", "/mnt/user/0/emulated/0/",
                "/storage/self/primary/", "/sdcard/"};
        for (String pre : pres) {
            if (abs.startsWith(pre)) return abs.substring(pre.length());
        }
        return null;
    }

    static String buildInstallScript(String zipName, File stageDir, File gameFiles, File backupBase,
                                     File logFile, boolean directDataPaths) {
        String stagePublic = stageDir.getAbsolutePath();
        String dstPublic = gameFiles.getAbsolutePath();
        String bkPublic = backupBase == null ? "" : backupBase.getAbsolutePath();
        String logPublic = logFile.getAbsolutePath();

        String stage = esc(directDataPaths ? toDirectPath(stagePublic) : stagePublic);
        String dst = esc(directDataPaths ? toDirectPath(dstPublic) : dstPublic);
        String bk = directDataPaths ? esc(toDirectPath(bkPublic)) : esc(bkPublic);
        String log = esc(directDataPaths ? toDirectPath(logPublic) : logPublic);
        String pub = directDataPaths ? esc(dstPublic) : "";
        String zip = shQuote(zipName);

        // 挂载点候选：无线调试激活的 Shizuku 是 uid=2000，走 FUSE 时写 Android/data
        // 可能中途 ENOTCONN；pass_through / mnt 视图往往还能用，所以逐个探测。
        String dstRel = stripVolumePrefix(dstPublic);
        String bkRel = bkPublic.length() == 0 ? "" : stripVolumePrefix(bkPublic);
        String roots = directDataPaths
                ? "/data/media/0 /storage/emulated/0"
                : "/mnt/pass_through/0/emulated/0 /mnt/user/0/emulated/0 /storage/emulated/0";

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 由 酸败补丁助手 生成（Shizuku shell 执行）\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 部署补丁 ").append(zip).append(" ===\"\n");
        sb.append("mkd() { mkdir -p \"$1\" && return 0; sleep 0.4; mkdir -p \"$1\" && return 0; sleep 1; mkdir -p \"$1\"; }\n");
        sb.append("put() { cp -f \"$1\" \"$2\" && return 0; sleep 0.4; cp -f \"$1\" \"$2\" && return 0; sleep 1; cp -f \"$1\" \"$2\"; }\n");
        if (dstRel == null) {
            // 未知布局：退回固定路径
            sb.append("DST='").append(dst).append("'\n");
            sb.append("BK='").append(bk).append("'\n");
            sb.append("ROOTS=''\n");
            sb.append("switch_mount() { return 1; }\n");
        } else {
            sb.append("ROOTS='").append(esc(roots)).append("'\n");
            sb.append("GRELP='").append(esc(dstRel)).append("'\n");
            sb.append("BKRELP='").append(esc(bkRel)).append("'\n");
            sb.append("setpaths() { DST=\"$ROOT/$GRELP\"; if [ -n \"$BKRELP\" ]; then BK=\"$ROOT/$BKRELP\"; else BK=''; fi; BKM=\"$BK/.manifest.tsv\"; }\n");
            sb.append("pick() { for c in $ROOTS; do [ -d \"$c/$GRELP\" ] || continue; if touch \"$c/$GRELP/.w_test\" 2>/dev/null; then rm -f \"$c/$GRELP/.w_test\" 2>/dev/null; ROOT=\"$c\"; return 0; fi; done; return 1; }\n");
            sb.append("switch_mount() { OLD=\"$ROOT\"; for c in $ROOTS; do [ \"$c\" = \"$OLD\" ] && continue; [ -d \"$c/$GRELP\" ] || continue; if touch \"$c/$GRELP/.w_test\" 2>/dev/null; then rm -f \"$c/$GRELP/.w_test\" 2>/dev/null; ROOT=\"$c\"; setpaths; echo \"[$(date '+%H:%M:%S')] ↻ 挂载点已切换: $c\"; return 0; fi; done; return 1; }\n");
            sb.append("ROOT=''; pick || { echo \"[$(date '+%H:%M:%S')] ✗ 没有可写的挂载点，试过: $ROOTS\"; echo \"  处理：重启手机 / 重启 Shizuku 后重试；用 root 激活 Shizuku 可走 /data/media/0 彻底绕开\"; exit 6; }\n");
            sb.append("setpaths\n");
        }
        sb.append("PUB='").append(pub).append("'\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 写入目标: $DST (uid=$(id -u))\"\n");
        sb.append("if ! touch \"$DST/.w_preflight\" 2>/dev/null; then echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录当前不可写（挂载点失效），已中止，一个文件都没动: $DST\"; echo \"  处理：重启手机 / 重开 Shizuku 后重试；root 激活可走 /data/media/0\"; exit 7; fi; rm -f \"$DST/.w_preflight\" 2>/dev/null\n");
        // v3.10: touch before mkdir. $BK = 备份文件/<pkg>/原版 which was never created;
        // touch on a missing path fails for root too -> misreported as not writable.
        // 结尾必须是 \n（换行），曾误写成 \\n（字面反斜杠+n），
        // 导致这一行与后面所有语句拼成一行，sh 报 unmatched 'if'。
        sb.append("if [ -n \"$BK\" ]; then mkd \"$BK\"; if touch \"$BK/.w_test\" 2>/dev/null; then rm -f \"$BK/.w_test\" 2>/dev/null; else echo \"[$(date '+%H:%M:%S')] ⚠ 备份目录建不出来或不可写，本次跳过备份: $BK\"; BK=''; BKM=''; fi; fi\n");
        // 目标目录的属主，拷完把文件 chown 回去，免得游戏读不到
        sb.append("OWNER=$(stat -c '%u:%g' \"$DST\" 2>/dev/null)\n");
        // 带重试的拷/建目录：FUSE 偶尔会 ENOTCONN，重试几次通常就过去了
        sb.append("BKM=\"${BK:+$BK/.manifest.tsv}\"\n");
        sb.append("cks() { cksum \"$1\" 2>/dev/null | awk '{print $1\" \"$2}'; }\n");
        sb.append("look() { awk -F'\t' -v k=\"$1\" '$1==k{print $2\" \"$3; exit}' \"$BKM\" 2>/dev/null; }\n");
        sb.append("putmani() { awk -F'\t' -v k=\"$1\" '$1!=k' \"$BKM\" 2>/dev/null > \"$BKM.t\"; printf '%s\t%s\t%s\\n' \"$1\" \"$2\" \"$3\" >> \"$BKM.t\"; mv \"$BKM.t\" \"$BKM\"; }\n");
        sb.append("cd '").append(stage).append("' || { echo \"[$(date '+%Y-%m-%d %H:%M:%S')] ✗ 无法进入解压目录\"; exit 2; }\n");
        sb.append("find . -type f > ./.plist 2>/dev/null\n");
        sb.append("n=0; ok=0; bad=0; wn=0; bk_ok=0; bk_fail=0\n");
        sb.append("while IFS= read -r rel; do\n");
        sb.append("  case \"$rel\" in ./.plist) continue;; esac\n");
        sb.append("  rel=${rel#./}\n");
        sb.append("  [ -z \"$rel\" ] && continue\n");
        sb.append("  n=$((n+1))\n");
        sb.append("  d=\"$DST/$rel\"\n");
        sb.append("  dd=\"${d%/*}\"\n");
        sb.append("  case \"$rel\" in *.txt|*.TXT|*.md|*.MD) skipbak=1;; *) skipbak=0;; esac\n");
        // v3.8：备份判定改成「看备份记录里有没有这条目标路径」，不再看内容是否等于补丁。
        // 旧逻辑「内容相同就不备份」会让重装过的补丁永远没有原版可还原。
        sb.append("  if [ \"$skipbak\" = 0 ] && [ -f \"$d\" ] && [ -n \"$BK\" ] && [ ! -f \"$BK/$rel\" ]; then\n");
        sb.append("    case \"$rel\" in\n");
        sb.append("      */*) bd=\"$BK/${rel%/*}\";;\n");
        sb.append("      *) bd=\"$BK\";;\n");
        sb.append("    esac\n");
        sb.append("    mkd \"$bd\" 2>/dev/null\n");
        sb.append("    if put \"$d\" \"$BK/$rel\"; then\n");
        sb.append("      putmani \"$rel\" $(cks \"$BK/$rel\")\n");
        sb.append("      bk_ok=$((bk_ok+1))\n");
        sb.append("      echo \"[$(date '+%H:%M:%S')] 已备份原版 $rel\"\n");
        sb.append("    else\n");
        sb.append("      bk_fail=$((bk_fail+1))\n");
        sb.append("      echo \"[$(date '+%H:%M:%S')] ⚠ 备份失败（继续安装，但这个文件回滚时会缺原版）: $rel\"\n");
        sb.append("    fi\n");
        sb.append("  elif [ \"$skipbak\" = 0 ] && [ -n \"$BK\" ] && [ -f \"$BK/$rel\" ]; then\n");
        sb.append("    have=$(look \"$rel\"); cur=$(cks \"$BK/$rel\")\n");
        sb.append("    if [ -n \"$have\" ] && [ \"$have\" != \"$cur\" ]; then\n");
        sb.append("      wn=$((wn+1))\n");
        sb.append("      echo \"[$(date '+%H:%M:%S')] ⚠ 备份校验不符（保留原索引，回滚会跳过）: $rel\"\n");
        sb.append("    fi\n");
        sb.append("  fi\n");
        sb.append("  if mkd \"$dd\" && put \"$rel\" \"$d\"; then\n");
        sb.append("    [ -n \"$OWNER\" ] && chown \"$OWNER\" \"$d\" 2>/dev/null\n");
        sb.append("    chmod 0664 \"$d\" 2>/dev/null\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ $rel\"\n");
        sb.append("    ok=$((ok+1))\n");
        sb.append("  elif switch_mount && { d=\"$DST/$rel\"; dd=\"${d%/*}\"; mkd \"$dd\" && put \"$rel\" \"$d\"; }; then\n");
        sb.append("    [ -n \"$OWNER\" ] && chown \"$OWNER\" \"$d\" 2>/dev/null\n");
        sb.append("    chmod 0664 \"$d\" 2>/dev/null\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ $rel（切换挂载点后成功）\"\n");
        sb.append("    ok=$((ok+1))\n");
        sb.append("  else\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✗ $rel（写入失败）\"\n");
        sb.append("    bad=$((bad+1))\n");
        sb.append("  fi\n");
        sb.append("done < ./.plist\n");
        sb.append("if [ \"$wn\" -gt 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 有 $wn 个文件的备份校验不符，已保留原索引，回滚会跳过它们\"; fi\n");
        sb.append("if [ \"$n\" -eq 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 这个补丁一个文件都没部署：zip 里可能没有 files/ 目录结构\"; fi\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] --- 备份：本次新增 $bk_ok 个，失败 $bk_fail 个（目标: ${BK:-无}）\"\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] --- 合计 $n 个文件：成功 $ok，失败 $bad\"\n");
        sb.append("if [ \"$bad\" -gt 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 有 $bad 个文件没写进去（常见原因：挂载点中途失效）\"; exit 8; fi\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /**
     * 一键清理备份：删除「备份文件」下的旧时间戳目录；keepOriginal=false 时连「原版」一起删。
     * 只删时间戳格式（yyyyMMdd_HHmmss）的目录，别的东西一律不碰。
     */
    static String buildCleanScript(File backupRoot, File logFile, boolean directDataPaths, boolean keepOriginal) {
        String bk = esc(directDataPaths ? toDirectPath(backupRoot.getAbsolutePath()) : backupRoot.getAbsolutePath());
        String log = esc(directDataPaths ? toDirectPath(logFile.getAbsolutePath()) : logFile.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 酸败补丁助手 —— 一键清理备份\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("BK='").append(bk).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 清理备份（").append(keepOriginal ? "保留原版" : "全部删除").append("） ===\"\n");
        // 安全护栏：路径必须正好是 …/备份文件
        sb.append("case \"$BK\" in */备份文件) ;; *) echo \"[$(date '+%H:%M:%S')] ✗ 备份路径异常，已中止: $BK\"; exit 5;; esac\n");
        sb.append("if [ ! -d \"$BK\" ]; then echo \"[$(date '+%H:%M:%S')] 备份目录不存在，无需清理\"; exit 0; fi\n");
        sb.append("before=$(du -sk \"$BK\" 2>/dev/null | awk '{print $1}')\n");
        sb.append("n=0\n");
        sb.append("for d in \"$BK\"/*; do\n");
        sb.append("  [ -d \"$d\" ] || continue\n");
        sb.append("  b=$(basename \"$d\")\n");
        sb.append("  case \"$b\" in\n");
        sb.append("    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]_[0-9][0-9][0-9][0-9][0-9][0-9])\n");
        sb.append("      sz=$(du -sk \"$d\" 2>/dev/null | awk '{print $1}')\n");
        sb.append("      if rm -rf \"$d\"; then echo \"[$(date '+%H:%M:%S')] ✓ 已删除 $b（${sz} KB）\"; n=$((n+1)); fi\n");
        sb.append("      ;;\n");
        sb.append("    *)\n");
        // 备份实际在 备份文件/<包名>/原版/，包名目录不能被当成“不是备份目录”留下
        sb.append("      if [ -d \"$d/原版\" ]; then\n");
        if (keepOriginal) {
            sb.append("        echo \"[$(date '+%H:%M:%S')] - 保留 $b/原版\"\n");
        } else {
            sb.append("        sz=$(du -sk \"$d/原版\" 2>/dev/null | awk '{print $1}')\n");
            sb.append("        if rm -rf \"$d/原版\"; then echo \"[$(date '+%H:%M:%S')] ✓ 已删除 $b/原版（${sz} KB）\"; n=$((n+1)); fi\n");
            sb.append("        rmdir \"$d\" 2>/dev/null\n");
        }
        sb.append("      elif [ \"$b\" = \"原版\" ]; then\n");
        if (keepOriginal) {
            sb.append("        echo \"[$(date '+%H:%M:%S')] - 保留 $b\"\n");
        } else {
            sb.append("        sz=$(du -sk \"$d\" 2>/dev/null | awk '{print $1}')\n");
            sb.append("        if rm -rf \"$d\"; then echo \"[$(date '+%H:%M:%S')] ✓ 已删除 $b（${sz} KB）\"; n=$((n+1)); fi\n");
        }
        sb.append("      else\n");
        sb.append("        echo \"[$(date '+%H:%M:%S')] - 保留 $b（不是备份目录）\"\n");
        sb.append("      fi\n");
        sb.append("      ;;\n");
        sb.append("  esac\n");
        sb.append("done\n");
        sb.append("after=$(du -sk \"$BK\" 2>/dev/null | awk '{print $1}')\n");
        sb.append("freed=$((before-after))\n");
        sb.append("[ \"$freed\" -lt 0 ] && freed=0\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] --- 清理 $n 项，释放约 ${freed} KB，剩余 $(du -sk \"$BK\" 2>/dev/null | awk '{print $1}') KB\"\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /**
     * 一键回滚原版：把「备份文件/原版」里记录的原始文件按原路径拷回游戏目录。
     * 有校验索引就逐个核对 cksum，对不上就跳过并报出来（防止同名文件乱入）。
     */
    static String buildRestoreScript(File gameFiles, File backupDir, File logFile, boolean directDataPaths) {
        String dstPublic = gameFiles.getAbsolutePath();
        String dst = esc(directDataPaths ? toDirectPath(dstPublic) : dstPublic);
        String pub = directDataPaths ? esc(dstPublic) : "";
        String bk = esc(directDataPaths ? toDirectPath(backupDir.getAbsolutePath()) : backupDir.getAbsolutePath());
        String log = esc(directDataPaths ? toDirectPath(logFile.getAbsolutePath()) : logFile.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 酸败补丁助手 —— 一键回滚原版\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("DST='").append(dst).append("'\n");
        sb.append("PUB='").append(pub).append("'\n");
        sb.append("BK='").append(bk).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 回滚原版 ===\"\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 还原到: $DST (uid=$(id -u))\"\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 备份来源: $BK\"\n");
        sb.append("if [ ! -d \"$DST\" ] && [ -n \"$PUB\" ] && [ -d \"$PUB\" ]; then DST=\"$PUB\"; fi\n");
        sb.append("OWNER=$(stat -c '%u:%g' \"$DST\" 2>/dev/null)\n");
        sb.append("mkd() { mkdir -p \"$1\" && return 0; sleep 0.4; mkdir -p \"$1\" && return 0; sleep 1; mkdir -p \"$1\"; }\n");
        sb.append("put() { cp -f \"$1\" \"$2\" && return 0; sleep 0.4; cp -f \"$1\" \"$2\" && return 0; sleep 1; cp -f \"$1\" \"$2\"; }\n");
        sb.append("cks() { cksum \"$1\" 2>/dev/null | awk '{print $1\" \"$2}'; }\n");
        sb.append("if [ ! -d \"$DST\" ]; then echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录不存在: $DST\"; exit 3; fi\n");
        sb.append("if [ ! -d \"$BK\" ]; then echo \"[$(date '+%H:%M:%S')] ✗ 备份目录不存在: $BK\"; exit 4; fi\n");
        sb.append("if ! touch \"$DST/.w_preflight\" 2>/dev/null; then echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录当前不可写（挂载点失效），已中止，什么都没改: $DST\"; echo \"  处理：重启手机 / 重开 Shizuku 后重试\"; exit 7; fi; rm -f \"$DST/.w_preflight\" 2>/dev/null\n");
        sb.append("BKM=\"$BK/.manifest.tsv\"\n");
        sb.append("n=0; ok=0; bad=0; sk=0\n");
        sb.append("do_one() {\n");
        sb.append("  rel=\"$1\"\n");
        sb.append("  f=\"$BK/$rel\"; d=\"$DST/$rel\"\n");
        sb.append("  if [ ! -f \"$f\" ]; then echo \"[$(date '+%H:%M:%S')] ✗ 备份缺失: $rel\"; bad=$((bad+1)); return; fi\n");
        sb.append("  case \"$rel\" in *.txt|*.TXT|*.md|*.MD) echo \"[$(date '+%H:%M:%S')] ℹ 说明文件不还原: $rel\"; sk=$((sk+1)); return;; esac\n");
        sb.append("  n=$((n+1))\n");
        sb.append("  cur=$(cks \"$f\")\n");
        sb.append("  if [ -n \"$2\" ] && [ \"$cur\" != \"$2\" ]; then\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ⚠ 校验不符，已跳过（防止覆盖成错的文件）: $rel\"\n");
        sb.append("    bad=$((bad+1)); return\n");
        sb.append("  fi\n");
        sb.append("  # 不再用\"内容一致就跳过\"：设备上出现过读缓存导致的假一致，宁可白拷一次\n");
        sb.append("  mkd \"${d%/*}\" 2>/dev/null\n");
        sb.append("  if put \"$f\" \"$d\"; then\n");
        sb.append("    [ -n \"$OWNER\" ] && chown \"$OWNER\" \"$d\" 2>/dev/null\n");
        sb.append("    chmod 0664 \"$d\" 2>/dev/null\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ 已还原 $rel\"\n");
        sb.append("    ok=$((ok+1))\n");
        sb.append("  else\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✗ 还原失败: $rel\"\n");
        sb.append("    bad=$((bad+1))\n");
        sb.append("  fi\n");
        sb.append("}\n");
        sb.append("TM=\"$BK/.restore_$$\"\n");
        sb.append(": > \"$TM.mdone\"\n");
        sb.append("if [ -f \"$BKM\" ]; then\n");
        sb.append("  T=$(printf '\\t')\n");
        sb.append("  while IFS=\"$T\" read -r rel mc ms; do\n");
        sb.append("    case \"$rel\" in ''|'#'*) continue;; esac\n");
        sb.append("    printf '%s\\n' \"$rel\" >> \"$TM.mdone\"\n");
        sb.append("    [ -z \"$mc\" ] && do_one \"$rel\" \"\" || do_one \"$rel\" \"$mc $ms\"\n");
        sb.append("  done < \"$BKM\"\n");
        sb.append("fi\n");
        // 索引缺失时，备份目录里没进索引的文件也要还原，否则回滚只还原一半
        sb.append("find \"$BK\" -type f ! -name '.manifest.tsv' ! -name '.restore_*' > \"$TM.rlist\" 2>/dev/null\n");
        sb.append("while IFS= read -r f; do\n");
        sb.append("  rel=${f#$BK/}\n");
        sb.append("  [ -z \"$rel\" ] && continue\n");
        sb.append("  grep -qxF \"$rel\" \"$TM.mdone\" 2>/dev/null && continue\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] ℹ 未索引备份，按文件还原: $rel\"\n");
        sb.append("  do_one \"$rel\" \"\"\n");
        sb.append("done < \"$TM.rlist\"\n");
        sb.append("rm -f \"$TM.mdone\" \"$TM.rlist\" 2>/dev/null\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] --- 回滚结束：还原 $ok 个，跳过 $sk 个，失败 $bad 个\"\n");
        sb.append("if [ \"$bad\" -gt 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 有 $bad 个文件没还原成功\"; exit 8; fi\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /**
     * 官方反和谐：只需要在游戏 files 下存在一个空的 localmode.txt。
     * 这个脚本只做这一件事（幂等：已存在就只报告）。
     */
    static String buildAntiScript(File gameFiles, File logFile, boolean directDataPaths) {
        String dstPublic = gameFiles.getAbsolutePath();
        String dst = esc(directDataPaths ? toDirectPath(dstPublic) : dstPublic);
        String pub = directDataPaths ? esc(dstPublic) : "";
        String log = esc(directDataPaths ? toDirectPath(logFile.getAbsolutePath()) : logFile.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 酸败补丁助手 —— 反和谐安装（只建 localmode.txt）\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("DST='").append(dst).append("'\n");
        sb.append("PUB='").append(pub).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 反和谐安装 ===\"\n");
        sb.append("if [ ! -d \"$DST\" ] && [ -n \"$PUB\" ] && [ -d \"$PUB\" ]; then DST=\"$PUB\"; fi\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 游戏目录: $DST (uid=$(id -u))\"\n");
        sb.append("if [ ! -d \"$DST\" ]; then\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录不存在\"\n");
        sb.append("  exit 3\n");
        sb.append("fi\n");
        sb.append("F=\"$DST/localmode.txt\"\n");
        sb.append("if [ -f \"$F\" ]; then\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] 反和谐开关本来就在：$F（$(wc -c < \"$F\" 2>/dev/null) 字节）\"\n");
        sb.append("else\n");
        sb.append("  if : > \"$F\"; then\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ 已创建空文件：$F\"\n");
        sb.append("  else\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✗ 创建失败：$F\"\n");
        sb.append("    exit 4\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("OWNER=$(stat -c '%u:%g' \"$DST\" 2>/dev/null)\n");
        sb.append("if [ -n \"$OWNER\" ]; then chown \"$OWNER\" \"$F\" 2>/dev/null; fi\n");
        sb.append("chmod 0664 \"$F\" 2>/dev/null\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 结果: $(ls -l \"$F\" 2>/dev/null)\"\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /** 把 srcDir 里的东西打进 zip，内部路径固定为 files/&lt;relPath&gt;/… */
    static int pack(File srcDir, String relPath, File outZip) throws IOException {
        String rel = sanitizeRel(relPath);
        if (rel.length() == 0) throw new IOException("路径无效，请填写例如 data/android/assetbundle/spines");
        OutputStream fos = new FileOutputStream(outZip);
        ZipOutputStream zos = new ZipOutputStream(fos);
        zos.setMethod(ZipOutputStream.DEFLATED);
        int count = 0;
        try {
            count = addDir(zos, srcDir, "files/" + rel + "/", 0);
            zos.finish();
        } finally {
            try {
                zos.close();
            } catch (IOException ignored) {
            }
        }
        return count;
    }

    private static int addDir(ZipOutputStream zos, File dir, String prefix, int count) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return count;
        for (File f : files) {
            if (f.isDirectory()) {
                count = addDir(zos, f, prefix + f.getName() + "/", count);
            } else {
                ZipEntry e = new ZipEntry(prefix + f.getName());
                e.setTime(f.lastModified());
                zos.putNextEntry(e);
                InputStream in = new FileInputStream(f);
                try {
                    copy(in, zos);
                } finally {
                    closeQuietly(in);
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    /** 路径消毒，与打包脚本里的 sed 规则一致。 */
    static String sanitizeRel(String raw) {
        if (raw == null) return "";
        String p = raw.trim().replace('\\', '/');
        p = p.replaceAll("^/storage/emulated/0/Android/data/[^/]+/files/", "");
        p = p.replaceAll("^/data/data/[^/]+/files/", "");
        p = p.replaceAll("^.*/files/", "");
        p = p.replaceAll("^/+", "");
        p = p.replaceAll("/+", "/");
        p = p.replaceAll("/+$", "");
        return p;
    }

    /**
     * 打包文件名消毒（v3.13.1）：按用户输入的名字来，返回 null 之外的合法名字。
     *
     * 规则：去首尾空白；末尾 .zip 不重复追加；路径分隔符与 Android/Windows 非法字符
     * （/ \ : * ? " < > | 与控制符）换成下划线；去掉结尾的点；超长截断到 80 字。
     *
     * @return 形如 `名字.zip`；输入无法救回（空名）时返回 ""
     */
    static String sanitizeZipName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        if (s.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            s = s.substring(0, s.length() - 4).trim();
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            boolean bad = c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|';
            sb.append(bad ? '_' : c);
        }
        s = sb.toString().trim();
        while (s.length() > 0 && s.charAt(s.length() - 1) == '.') {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.length() == 0) return "";
        if (s.length() > 80) s = s.substring(0, 80).trim();
        if (s.length() == 0) return "";
        return s + ".zip";
    }

    /** 同名包已存在就加 _2、_3…，不覆盖上一次打的包。 */
    static File uniqueZipFile(File dir, String zipName) {
        String name = sanitizeZipName(zipName);
        if (name.length() == 0) name = "补丁.zip";
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name.substring(0, name.length() - 4);
        for (int k = 2; k < 1000; k++) {
            f = new File(dir, base + "_" + k + ".zip");
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ".zip");
    }

    private static void copy(InputStream in, File out) throws IOException {
        OutputStream os = new FileOutputStream(out);
        try {
            copy(in, os);
        } finally {
            closeQuietly(os);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static String esc(String path) {
        return path.replace("'", "'\\''");
    }

    static String shQuote(String s) {
        // 生成的是双引号内嵌 shell 文本：反斜杠、双引号、$、反引号都要转义
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
    }

    interface Progress {
        void onProgress(String message);
    }
}