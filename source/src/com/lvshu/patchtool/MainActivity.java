package com.lvshu.patchtool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

/**
 * 酸败补丁助手 —— 用 Shizuku 代替 su，实现补丁的一键安装 / 打包 / 目录初始化。
 * v1.1：目录检测改走 Shizuku shell；界面改成固定浅色高对比配色。
 */
public class MainActivity extends Activity {

    private static final String PREF = "patchtools";
    private static final String DEF_ROOT = "/storage/emulated/0/LvShu/酸败补丁";
    private static final String DEF_GAME = "/storage/emulated/0/Android/data/com.jumpw.mobile300/files";
    private static final int REQ_SHIZUKU = 1001;
    private static final int REQ_STORAGE = 2002;

    // ---- 配色（固定值，不跟随系统深色模式） ----
    private static final int C_BG = 0xFFF2F4F7;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TITLE = 0xFF111827;
    private static final int C_TEXT = 0xFF1F2937;
    private static final int C_LABEL = 0xFF6B7280;
    private static final int C_HINT = 0xFF9CA3AF;
    private static final int C_LINE = 0xFFD1D5DB;
    private static final int C_PRIMARY = 0xFF2563EB;
    private static final int C_PRIMARY_TXT = 0xFFFFFFFF;
    private static final int C_OK = 0xFF15803D;
    private static final int C_BAD = 0xFFB91C1C;
    private static final int C_LOG_BG = 0xFF111827;
    private static final int C_LOG_TXT = 0xFFE5E7EB;

    private static final String[] PRESETS = new String[]{
            "（自定义 / 选一个预设）",
            "data/android/assetbundle/texture/heropanel/",
            "data/android/assetbundle/spines/",
            "data/android/assetbundle/sound/",
            "data/android/assetbundle/ui/atlas/",
            "data/android/assetbundle/gamedependences/art/xmodel/comtexture/",
            "data/android/assetbundle/gamedependences/scenemode/scene_tex/rgb/",
            "data/android/texture/",
            "data/android/assetbundle/gamedependences/art/effect/texture/ui/"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    private TextView tvShizuku;
    private TextView tvLog;
    private EditText etRoot, etPatch, etGame, etRel;
    private CheckBox cbBackup;
    private Spinner spPreset;
    private Button btnShizuku, btnStorage, btnInstall, btnRestore, btnClean, btnPack, btnMkdir, btnClear, btnDetect, btnAnti, btnDelPatch;
    private CheckBox cbRoot;
    private TextView tvBkHint;
    private ScrollView logScroll;
    private volatile boolean busy = false;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshStatus();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(C_BG));
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(C_BG);
            getWindow().setNavigationBarColor(0xFFFFFFFF);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        buildUi();
        loadPrefs();
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        log("酸败补丁助手 v3.13 已启动。");
        log("两条通道任选其一：① 授权 Shizuku，或在配置里勾「优先用 root」→ ② 所有文件访问 → ③ 一键安装。");
        refreshStatus();
        refreshBackupHint();
        probeRoot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ---------------- 配色工具 ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int r, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), 0, dp(r), dp(bottom));
        return p;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(C_CARD, 0, 12));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        return c;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(C_LABEL);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(C_TITLE);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(primary ? C_PRIMARY_TXT : C_PRIMARY);
        b.setBackground(primary ? box(C_PRIMARY, 0, 10) : box(0xFFE8EFFD, C_PRIMARY, 10));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setMinHeight(dp(44));
        return b;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(C_HINT);
        e.setText(value);
        e.setTextColor(C_TEXT);
        e.setTextSize(13f);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        e.setBackground(box(0xFFF9FAFB, C_LINE, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(16));

        ScrollView outer = new ScrollView(this);
        outer.setBackgroundColor(C_BG);
        outer.addView(root);

        TextView title = new TextView(this);
        title.setText("酸败补丁助手");
        title.setTextSize(21);
        title.setTextColor(C_TITLE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, lp(-1, -2, 0, 2, 0, 2));

        TextView sub = new TextView(this);
        sub.setText("免 root · 通过 Shizuku 以 shell 身份写入游戏目录");
        sub.setTextSize(12);
        sub.setTextColor(C_LABEL);
        root.addView(sub, lp(-1, -2, 0, 2, 0, 10));

        // 状态卡
        LinearLayout statusCard = card();
        tvShizuku = new TextView(this);
        tvShizuku.setTextSize(13);
        tvShizuku.setTextColor(C_TEXT);
        statusCard.addView(tvShizuku, lp(-1, -2, 0, 0, 0, 8));
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        btnShizuku = button("① 授权 Shizuku", true);
        btnStorage = button("② 文件访问权限", false);
        row1.addView(btnShizuku, lp(0, -2, 1, 0, 5, 0));
        row1.addView(btnStorage, lp(0, -2, 1, 5, 0, 0));
        statusCard.addView(row1, lp(-1, -2, 0, 0, 0, 4));
        root.addView(statusCard, lp(-1, -2, 0, 0, 0, 10));

        // 配置卡
        LinearLayout cfg = card();
        cfg.addView(sectionTitle("路径配置"));
        cfg.addView(label("补丁根目录（日志/备份/临时都在这下面）"));
        etRoot = field("根目录", DEF_ROOT);
        cfg.addView(etRoot, lp(-1, -2, 0, 0, 0, 4));
        cfg.addView(label("补丁目录（把 .zip 放这里）"));
        etPatch = field("补丁目录", DEF_ROOT + "/补丁存放位置（zip）");
        cfg.addView(etPatch, lp(-1, -2, 0, 0, 0, 4));
        cfg.addView(label("游戏 files 目录（渠道服改这里）"));
        etGame = field("游戏 files 目录", DEF_GAME);
        cfg.addView(etGame, lp(-1, -2, 0, 0, 0, 4));
        tvBkHint = new TextView(this);
        tvBkHint.setTextSize(11.5f);
        tvBkHint.setTextColor(0xFF6B7280);
        cfg.addView(tvBkHint, lp(-1, -2, 0, 0, 0, 4));
        btnDetect = button("🔍 自动探测已安装的酸败目录", false);
        cfg.addView(btnDetect, lp(-1, -2, 0, 0, 0, 8));

        cbRoot = new CheckBox(this);
        cbRoot.setText("优先用 root 执行（设备已 root 时直接 su，不用先激活 Shizuku）");
        cbRoot.setTextSize(13);
        cbRoot.setTextColor(C_TEXT);
        cbRoot.setButtonTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY));
        cfg.addView(cbRoot, lp(-1, -2, 0, 0, 0, 4));

        cbBackup = new CheckBox(this);
        cbBackup.setText("覆盖前备份到「备份文件/<包名>/原版/」（只存一份，重复跑不会覆盖）");
        cbBackup.setTextSize(13);
        cbBackup.setTextColor(C_TEXT);
        cbBackup.setButtonTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY));
        cfg.addView(cbBackup, lp(-1, -2, 0, 0, 0, 4));
        root.addView(cfg, lp(-1, -2, 0, 0, 0, 10));

        // 操作卡
        LinearLayout ops = card();
        ops.addView(sectionTitle("操作"));
        btnInstall = button("▶ 一键安装补丁", true);
        ops.addView(btnInstall, lp(-1, -2, 0, 0, 0, 8));
        btnAnti = button("🩹 反和谐安装（建 localmode.txt）", false);
        ops.addView(btnAnti, lp(-1, -2, 0, 0, 0, 8));
        btnRestore = button("↩ 一键回滚原版", false);
        ops.addView(btnRestore, lp(-1, -2, 0, 0, 0, 8));
        btnClean = button("🧹 一键清理备份（省空间）", false);
        ops.addView(btnClean, lp(-1, -2, 0, 0, 0, 8));
        btnDelPatch = button("🗑 删除已安装的补丁 zip", false);
        ops.addView(btnDelPatch, lp(-1, -2, 0, 0, 0, 8));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        btnMkdir = button("初始化目录", false);
        btnClear = button("清空日志", false);
        row2.addView(btnMkdir, lp(0, -2, 1, 0, 5, 0));
        row2.addView(btnClear, lp(0, -2, 1, 5, 0, 0));
        ops.addView(row2, lp(-1, -2, 0, 0, 0, 10));

        ops.addView(sectionTitle("打包"));
        ops.addView(label("先选一个游戏内路径（对应 详情目录.txt），再打包"));
        spPreset = new Spinner(this);
        spPreset.setBackground(box(0xFFF9FAFB, C_LINE, 10));
        spPreset.setPadding(dp(8), dp(4), dp(8), dp(4));
        spPreset.setAdapter(new SpinnerAdapter(PRESETS));
        spPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) etRel.setText(PRESETS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        ops.addView(spPreset, lp(-1, -2, 0, 0, 0, 6));
        etRel = field("游戏内相对路径，例如 data/android/assetbundle/spines/", "");
        ops.addView(etRel, lp(-1, -2, 0, 0, 0, 8));
        btnPack = button("📦 打包「待打包资源」", false);
        ops.addView(btnPack, lp(-1, -2, 0, 0, 0, 4));
        root.addView(ops, lp(-1, -2, 0, 0, 0, 10));

        // 日志
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setBackground(box(C_LOG_BG, 0, 12));
        logCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(12);
        logTitle.setTextColor(0xFF9CA3AF);
        logCard.addView(logTitle, lp(-1, -2, 0, 0, 0, 4));
        tvLog = new TextView(this);
        tvLog.setTextSize(11.5f);
        tvLog.setTextColor(C_LOG_TXT);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setTextIsSelectable(true);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        logScroll = new ScrollView(this);
        logScroll.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logCard.addView(logScroll, lp(-1, dp(260), 0, 0, 0, 0));
        root.addView(logCard, lp(-1, -2, 0, 0, 0, 6));

        setContentView(outer);

        btnShizuku.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askShizuku();
            }
        });
        btnStorage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestStoragePermission();
            }
        });
        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doInstall();
            }
        });
        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClean();
            }
        });
        btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestore();
            }
        });
        btnDelPatch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteInstalled();
            }
        });
        btnAnti.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doAnti();
            }
        });
        btnMkdir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doMkdirs();
            }
        });
        btnPack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doPack();
            }
        });
        etGame.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                refreshBackupHint();
            }
        });

        cbRoot.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Shell.setPreferRoot(checked);
                if (checked && RootShell.state() == -2) probeRoot();
                savePrefs();
                refreshStatus();
                log(checked ? "\u2139 已启用 root 执行通道（有 su 时优先用它）" : "\u2139 已切回 Shizuku 执行通道");
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearLog();
            }
        });
        btnDetect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doDetect();
            }
        });
    }

    /** Spinner 用自定义 item，避免跟随系统主题出现白字。 */
    private class SpinnerAdapter extends ArrayAdapter<String> {
        SpinnerAdapter(String[] items) {
            super(MainActivity.this, android.R.layout.simple_spinner_item, items);
        }

        private TextView make(String text, boolean dropdown) {
            TextView t = new TextView(MainActivity.this);
            t.setText(text);
            t.setTextSize(12.5f);
            t.setTextColor(C_TEXT);
            t.setPadding(dp(10), dp(dropdown ? 10 : 12), dp(10), dp(dropdown ? 10 : 12));
            return t;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return make(getItem(position), false);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return make(getItem(position), true);
        }
    }

    // ---------------- 状态 / 权限 ----------------

    private void refreshStatus() {
        boolean storage = hasStorage();
        int rs = RootShell.state();
        String rootState = rs == 0 ? "可用" : (rs == -1 ? "不可用（无 su 或未授权）" : "探测中…");
        boolean ok = ShizukuShell.available();
        boolean perm = ok && ShizukuShell.permissionGranted();

        String line1;
        if (Shell.usingRoot()) {
            line1 = "执行通道：root（su）· uid=0 · 不依赖 Shizuku";
        } else if (!ok) {
            line1 = "执行通道：Shizuku 未连接（root " + rootState + "）";
        } else if (!perm) {
            line1 = "执行通道：Shizuku 已连接，未授权（点 ①）；root " + rootState;
        } else {
            int u = ShizukuShell.shellUid();
            line1 = "执行通道：Shizuku · uid=" + u + (u == 0 ? "（root 激活）" : "（shell 激活）");
        }
        String line2 = "root：" + rootState + "　·　所有文件访问权限：" + (storage ? "已授予" : "未授予");
        tvShizuku.setText(line1 + "\n" + line2);
        tvShizuku.setTextColor(Shell.ready() && storage ? C_OK : C_BAD);
    }

    /** 后台探测一次 su，探测结果回主线程刷状态。 */
    private void probeRoot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                RootShell.probe();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        log(RootShell.available() ? "\u2713 检测到 root：可以走 su 通道" : "\u2139 没有可用的 su（root 通道不可用，仍走 Shizuku）");
                    }
                });
            }
        }, "root-probe").start();
    }

    private void askShizuku() {
        if (!ShizukuShell.available()) {
            toast("Shizuku 没运行：先打开 Shizuku 启动服务");
            return;
        }
        if (ShizukuShell.permissionGranted()) {
            toast("Shizuku 权限已到手");
            refreshStatus();
            return;
        }
        try {
            ShizukuShell.requestPermission(REQ_SHIZUKU);
            log("已申请 Shizuku 权限，请在弹窗点「允许」。");
        } catch (Throwable t) {
            toast("申请失败：" + t);
        }
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    toast("打不开设置页，请手动到系统设置里给「所有文件访问」");
                }
            }
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    // ---------------- Shizuku shell 辅助 ----------------

    /**
     * 用 shell 身份跑一小段脚本，返回退出码。
     * 关键：Android/data 只有 shell 能看，应用进程永远看不到，
     * 所以「游戏目录是否存在」这类判断必须走这里。
     */
    private int shellExit(String body) throws Exception {
        File dir = scriptDir();
        File s = new File(dir, "probe_" + System.nanoTime() + ".sh");
        writeText(s, "#!/system/bin/sh\n" + body + "\n");
        s.setReadable(true, false);
        try {
            return Shell.runScript(s.getAbsolutePath());
        } finally {
            s.delete();
        }
    }

    private boolean shellDirExists(String path) {
        try {
            return shellExit("if [ -d '" + esc(path) + "' ]; then exit 0; else exit 3; fi") == 0;
        } catch (Throwable t) {
            log("! shell 检测失败: " + t);
            return false;
        }
    }

    private static String esc(String s) {
        return s.replace("'", "'\\''");
    }

    /** 探测 Android/data 下的 jumpw（酸败）目录，让用户点选。 */
    private void doDetect() {
        if (busy) {
            toast("等一下，还有任务在跑");
            return;
        }
        if (!Shell.ready()) {
            toast("先点 ① 授权 Shizuku，或在下面勾选「优先用 root」");
            return;
        }
        final String rootStr = text(etRoot, DEF_ROOT);
        startWorker(new Runnable() {
            @Override
            public void run() {
                File out = new File(rootStr, "临时解压/scan.txt");
                out.getParentFile().mkdirs();
                final File target = out;
                String body = "OUT='" + esc(out.getAbsolutePath()) + "'\n"
                        + ": > \"$OUT\"\n"
                        + "for d in /storage/emulated/0/Android/data/*; do\n"
                        + "  n=${d##*/}\n"
                        + "  case \"$n\" in *jumpw*|*300*|*mclub*) [ -d \"$d/files\" ] && echo \"$d/files\" >> \"$OUT\";; esac\n"
                        + "done\n"
                        + "exit 0";
                try {
                    shellExit(body);
                } catch (Throwable t) {
                    log("✗ 探测失败: " + t);
                    return;
                }
                List<String> found = new ArrayList<String>();
                try {
                    String all = readText(target);
                    for (String line : all.split("\n")) {
                        String s = line.trim();
                        if (s.length() > 0 && !found.contains(s)) found.add(s);
                    }
                } catch (IOException e) {
                    log("✗ 读取探测结果失败: " + e.getMessage());
                } finally {
                    target.delete();
                }
                if (found.isEmpty()) {
                    log("没探测到酸败目录，可能游戏没装或不是 jumpw 包名。");
                    log("可以把「游戏 files 目录」手动改成你的渠道服路径。");
                    return;
                }
                log("探测到 " + found.size() + " 个候选目录：");
                for (String s : found) log("  · " + s);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        final List<String> items = found;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择游戏 files 目录")
                                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        etGame.setText(items.get(which));
                                        savePrefs();
                                        toast("已填入，记得再点一键安装");
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    /**
     * 脚本放哪：优先补丁根目录下的「临时解压」，那儿不可写（没给存储权限）就退回
     * 应用私有目录 —— Shizuku 是 root 时私有目录也读得到。
     */
    private File scriptDir() {
        File dir = new File(text(etRoot, DEF_ROOT), "临时解压");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir failed");
            File probe = new File(dir, ".w_test");
            if (probe.exists()) probe.delete();
            if (!probe.createNewFile()) throw new IOException("not writable");
            probe.delete();
            return dir;
        } catch (Exception e) {
            return getFilesDir();
        }
    }

    private long dirSize(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        File[] cs = f.listFiles();
        if (cs == null) return 0;
        long t = 0;
        for (File c : cs) t += dirSize(c);
        return t;
    }

    private int countFiles(File f) {
        if (f == null) return 0;
        if (f.isFile()) return 1;
        File[] cs = f.listFiles();
        if (cs == null) return 0;
        int t = 0;
        for (File c : cs) t += countFiles(c);
        return t;
    }

    private String mb(long b) {
        return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
    }

    /** 一键清理备份：先亮出家底，再让用户选删哪些。 */
    private void confirmClean() {
        if (busy) {
            toast("正在跑，别急");
            return;
        }
        if (!Shell.ready()) {
            toast("还没就绪：授权 Shizuku 或勾选 root 通道");
            return;
        }
        final File bkRoot = new File(text(etRoot, DEF_ROOT), "备份文件");
        if (!bkRoot.isDirectory()) {
            toast("没有备份目录，不用清理：" + bkRoot.getAbsolutePath());
            return;
        }
        // 实际备份在 备份文件/<包名>/原版/，老版本还可能留在 备份文件/原版/
        final File legacyOrig = new File(bkRoot, "原版");
        long origSize = dirSize(legacyOrig);
        int origFiles = countFiles(legacyOrig);
        int pkgDirs = 0;
        int oldCount = 0;
        long oldSize = 0;
        File[] subs = bkRoot.listFiles();
        if (subs != null) {
            for (File f : subs) {
                if (!f.isDirectory()) continue;
                if (f.getName().matches("\\d{8}_\\d{6}")) {
                    oldCount++;
                    oldSize += dirSize(f);
                } else {
                    File o = new File(f, "原版");
                    if (o.isDirectory()) {
                        pkgDirs++;
                        origFiles += countFiles(o);
                        origSize += dirSize(o);
                    }
                }
            }
        }
        final String msg = "备份文件/ 里现在有：\n\n"
                + "· 原版（可回滚）：" + origFiles + " 个文件，" + mb(origSize)
                + (pkgDirs > 0 ? "（" + pkgDirs + " 个包名目录）" : "") + "\n"
                + "· 旧时间戳目录：" + oldCount + " 个，共 " + mb(oldSize) + "\n\n"
                + "「删旧目录」只清旧备份、保留各包名下的原版；\n"
                + "「全部删除」连原版一起清掉 —— 之后就没法一键回滚了。";
        new AlertDialog.Builder(this)
                .setTitle("一键清理备份")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setNeutralButton("全部删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doClean(false);
                    }
                })
                .setPositiveButton("删旧目录", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doClean(true);
                    }
                })
                .show();
    }

    private void doClean(final boolean keepOriginal) {
        savePrefs();
        final String rootStr = text(etRoot, DEF_ROOT);
        startWorker(new Runnable() {
            @Override
            public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                File logDir = new File(rootStr, "操作日志");
                if (!logDir.isDirectory() && !logDir.mkdirs()) logDir = getFilesDir();
                File logFile = new File(logDir, "clean_" + stamp + ".log");
                File script = new File(scriptDir(), "clean_" + stamp + ".sh");
                boolean direct = Shell.uid() == 0;
                log("清理备份（" + (keepOriginal ? "保留原版" : "全部删除") + "）");
                try {
                    writeText(script, Patcher.buildCleanScript(new File(rootStr, "备份文件"), logFile, direct, keepOriginal));
                    script.setReadable(true, false);
                } catch (IOException e) {
                    log("✗ 生成脚本失败: " + e.getMessage());
                    return;
                }
                int rc = runAndTail(script, logFile);
                script.delete();
                if (rc == 0) {
                    log("清理完成。");
                    doneDialog("清理完成", "已清掉多余备份，原版备份保留。\n\n详细日志：" + logFile.getName(), null);
                } else if (rc == 5) {
                    log("✗ 备份路径异常，已中止（没删任何东西）。");
                } else {
                    log("✗ 清理失败，退出码 " + rc);
                }
                log("日志: " + logFile.getAbsolutePath());
            }
        });
    }

    /** 一键回滚原版：先确认，再从「备份文件/<包名>/原版」按索引还原。 */
    private void confirmRestore() {
        if (busy) {
            toast("正在跑，别急");
            return;
        }
        if (!Shell.ready()) {
            toast("还没就绪：授权 Shizuku 或勾选 root 通道");
            return;
        }
        final String rootStr = text(etRoot, DEF_ROOT);
        File bk = backupOrig(rootStr, text(etGame, DEF_GAME));
        if (!bk.isDirectory()) {
            toast("还没有备份目录：" + bk.getAbsolutePath());
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("一键回滚原版？")
                .setMessage("会把「备份文件/<包名>/原版」里的原始文件按原路径覆盖回游戏目录。\n\n建议先退出游戏再回滚。")
                .setNegativeButton("取消", null)
                .setPositiveButton("回滚", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doRestore();
                    }
                })
                .show();
    }

    private void doRestore() {
        savePrefs();
        final String gameStr = text(etGame, DEF_GAME);
        final String rootStr = text(etRoot, DEF_ROOT);
        startWorker(new Runnable() {
            @Override
            public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                File bk = backupOrig(rootStr, gameStr);
                File logDir = new File(rootStr, "操作日志");
                if (!logDir.isDirectory() && !logDir.mkdirs()) logDir = getFilesDir();
                File logFile = new File(logDir, "restore_" + stamp + ".log");
                File script = new File(scriptDir(), "restore_" + stamp + ".sh");
                boolean direct = Shell.uid() == 0;
                log("回滚原版 ← " + bk.getAbsolutePath());
                fileLog(logFile, "回滚到：" + gameStr);
                fileLog(logFile, "备份来源：" + bk.getAbsolutePath());
                try {
                    writeText(script, Patcher.buildRestoreScript(new File(gameStr), bk, logFile, direct));
                    script.setReadable(true, false);
                } catch (IOException e) {
                    log("✗ 生成脚本失败: " + e.getMessage());
                    return;
                }
                int rc = runAndTail(script, logFile);
                script.delete();
                if (rc == 0) {
                    log("回滚流程结束（明细见日志）。");
                    int back = numFromLog(logFile, "还原 (\\d+) 个");
                    doneDialog("回滚完成",
                            (back < 0 ? "已按备份还原。" : ("已还原 " + back + " 个原版文件。")) + "\n\n"
                                    + "游戏目录：\n" + gameStr + "\n\n重开游戏后就是原版资源了。\n详细日志：" + logFile.getName(),
                            gameStr);
                } else if (rc == 3) {
                    log("✗ 游戏目录不存在，先点「🔍 自动探测」确认路径。");
                } else if (rc == 4) {
                    log("✗ 备份目录不存在，没东西可回滚。");
                } else {
                    log("✗ 回滚失败，退出码 " + rc);
                }
                log("日志: " + logFile.getAbsolutePath());
            }
        });
    }

    /** 反和谐安装：只在游戏 files 下建一个空的 localmode.txt。 */
    private void doAnti() {
        if (busy) {
            toast("正在跑，别急");
            return;
        }
        if (!Shell.ready()) {
            toast("还没就绪：授权 Shizuku 或勾选 root 通道");
            return;
        }
        savePrefs();
        final String gameStr = text(etGame, DEF_GAME);
        final String rootStr = text(etRoot, DEF_ROOT);
        startWorker(new Runnable() {
            @Override
            public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                File logDir = new File(rootStr, "操作日志");
                if (!logDir.isDirectory() && !logDir.mkdirs()) logDir = getFilesDir();
                File logFile = new File(logDir, "anti_" + stamp + ".log");
                File script = new File(scriptDir(), "anti_" + stamp + ".sh");
                boolean direct = Shell.uid() == 0;
                log("反和谐安装 → " + gameStr);
                try {
                    writeText(script, Patcher.buildAntiScript(new File(gameStr), logFile, direct));
                    script.setReadable(true, false);
                } catch (IOException e) {
                    log("✗ 生成脚本失败: " + e.getMessage());
                    return;
                }
                int rc = runAndTail(script, logFile);
                script.delete();
                if (rc == 0) {
                    log("✓ 反和谐已就绪（localmode.txt 在 " + gameStr + " 下）。");
                    doneDialog("反和谐已安装",
                            "localmode.txt 已就位，游戏会以非和谐资源启动。\n\n游戏目录：\n" + gameStr + "\n\n可以打开游戏了。\n详细日志：" + logFile.getName(),
                            gameStr);
                    log("日志: " + logFile.getAbsolutePath());
                } else if (rc == 3) {
                    log("✗ 游戏目录不存在，先点「🔍 自动探测」确认路径。");
                } else {
                    log("✗ 反和谐安装失败，退出码 " + rc + "，日志: " + logFile.getAbsolutePath());
                }
            }
        });
    }

    // ---------------- 业务 ----------------

    private void doMkdirs() {
        if (!checkStorageOnly()) return;
        final String rootStr = text(etRoot, DEF_ROOT);
        startWorker(new Runnable() {
            @Override
            public void run() {
                String[] dirs = new String[]{
                        "", "/补丁存放位置（zip）", "/临时解压", "/操作日志",
                        "/备份文件", "/补丁素材源文件",
                        "/打包补丁", "/打包补丁/待打包资源"
                };
                for (String d : dirs) {
                    File f = new File(rootStr + d);
                    if (f.exists() || f.mkdirs()) {
                        log("✓ " + f.getAbsolutePath());
                    } else {
                        log("✗ 建不出来 " + f.getAbsolutePath());
                    }
                }
                log("目录结构就绪。");
            }
        });
    }

    /**
     * 收拾「临时解压」里的历史残留：以前中断的运行会留下 stamp 目录和 run_*.sh，
     * 不清就一直堆着（一个 stage 能有十几 MB）。
     */
    private void pruneTmp(File tmpRoot) {
        File[] cs = tmpRoot.listFiles();
        if (cs == null) return;
        int n = 0;
        for (File c : cs) {
            String name = c.getName();
            if (name.matches("\\d{8}_\\d{6}(_\\d+)?")
                    || name.matches("run_\\d{8}_\\d{6}_\\d+\\.sh")
                    || name.matches("install_\\d{8}_\\d{6}_\\d+\\.sh")
                    || name.matches("restore_\\d{8}_\\d{6}\\.sh")
                    || name.matches("clean_\\d{8}_\\d{6}\\.sh")
                    || name.matches("anti_\\d{8}_\\d{6}\\.sh")
                    || name.equals("scan.txt")) {
                deleteRecursive(c);
                n++;
            }
        }
        if (n > 0) postLog("已清理临时残留 " + n + " 项");
    }






    /**
     * 用 shell 列出游戏资源目录里的文件（相对游戏 files 的路径）。
     * 应用进程看不到 Android/data，只能让 shell 帮我们列。
     */
    private List<String> gameRelList(String gameStr, File tmpRoot) {
        List<String> out = new ArrayList<String>();
        File idx = new File(tmpRoot, ".gidx");
        idx.delete();
        File sh = new File(tmpRoot, "idx_" + System.currentTimeMillis() + ".sh");
        // v3.13：游戏资源在 files 下的 data 与 flies 两个目录，一起列
        String body = "#!/system/bin/sh\nfor d in " + Patcher.shQuote(gameStr + "/data") + " "
                + Patcher.shQuote(gameStr + "/flies") + "; do [ -d \"$d\" ] && find \"$d\" -type f 2>/dev/null; done > "
                + Patcher.shQuote(idx.getAbsolutePath()) + "\n";
        try {
            writeText(sh, body);
            sh.setReadable(true, false);
            Shell.runScript(sh.getAbsolutePath());
        } catch (Throwable e) {
            log("! 拿游戏资源清单失败：" + e);
        }
        sh.delete();
        try {
            String text = readText(idx);
            String prefix = gameStr + "/";
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith(prefix)) line = line.substring(prefix.length());
                out.add(line);
            }
        } catch (Throwable ignored) {
        }
        idx.delete();
        return out;
    }

    /**
     * 把解压结果按「游戏里的相对路径」重排。
     * 补丁包本来就是 files/ 结构 → 基本原样；只有 .assetbundle 散文件 → 按文件名定位。
     */
    private File mapStage(File stage, File tmpRoot, String gameStr, String tag,
                          String rootStr, List<String> skipped) {
        List<String> rels = gameRelList(gameStr, tmpRoot);
        if (rels.isEmpty()) {
            log("! 没列到游戏资源（目录不存在或权限不足），这次按补丁包里的原路径装");
            return stage;
        }

        File stage2 = new File(tmpRoot, tag + "_map");
        deleteRecursive(stage2);

        // CAB 索引：有缓存就直接用；没有才建（首次会提示等 1~2 分钟）
        Map<String, List<String>> cabMap = loadCabIndex(gameStr, tmpRoot, rootStr);

        // 新建路径的首级校验：只允许写进游戏 files 下真实存在的一级目录
        Set<String> roots = gameTopDirs(gameStr, tmpRoot);
        blog("[定位] 游戏一级目录：" + (roots.isEmpty() ? "(没列到，新建路径不做首级校验)" : roots.toString()));

        List<String> report = new ArrayList<String>();
        int[] r = Patcher.relocate(stage, stage2, rels, cabMap, skipped, new Patcher.Progress() {
            @Override
            public void onProgress(String message) {
                postLog(message);
            }
        }, report, roots, new Patcher.Confirm() {
            @Override
            public boolean confirmNew(List<String> paths) {
                // 汇总弹窗：一次列全，点是全部新建，点否只跳过这些新增
                log("游戏里没有 " + paths.size() + " 个文件的对应资源，等待确认是否按精确路径新建…");
                boolean yes = confirmNewFiles(paths);
                log(yes ? "✓ 已确认：按精确路径新建 " + paths.size() + " 个文件"
                        : "✗ 已取消：只跳过这 " + paths.size() + " 个新增文件，已有资源照常安装");
                return yes;
            }
        });
        // P1：定位明细（含每个文件为什么跳过）必须落盘，否则弹窗说“看日志”却查不到
        blog("[定位] ===== 开始定位 =====");
        for (String line : report) blog("[定位] " + line);
        blog("[定位] 结果：成功 " + r[0] + " 个，跳过 " + r[1]
                + " 个（游戏资源共 " + rels.size() + " 个，CAB 索引 "
                + (cabMap == null ? "未建" : cabMap.size() + " 条") + "）");

        // 一个文件都没落进来时目录可能不存在，不能直接拿去 cd（会报 No such file or directory）
        if (!stage2.isDirectory() || !hasAnyFile(stage2)) {
            log("! 这个补丁没有任何文件能被定位成游戏路径，本次不安装");
            return null;
        }
        return stage2;
    }

    /**
     * 游戏 files 下的一级目录名单（校验「精确路径新建」不会在游戏目录里乱建顶层目录）。
     * 应用进程看不到 Android/data，只能让 shell 列；拿不到就返回空集合，
     * 此时不做首级校验，路径合法性仍由 Patcher.canCreateNew 兜住。
     */
    private Set<String> gameTopDirs(String gameStr, File tmpRoot) {
        Set<String> out = new HashSet<String>();
        File lst = new File(tmpRoot, ".groot");
        lst.delete();
        File sh = new File(tmpRoot, "groot_" + System.currentTimeMillis() + ".sh");
        String body = "#!/system/bin/sh\nls -1 " + Patcher.shQuote(gameStr)
                + " 2>/dev/null > " + Patcher.shQuote(lst.getAbsolutePath()) + "\n";
        try {
            writeText(sh, body);
            sh.setReadable(true, false);
            Shell.runScript(sh.getAbsolutePath());
        } catch (Throwable e) {
            log("! 列游戏一级目录失败：" + e);
        }
        sh.delete();
        try {
            for (String line : readText(lst).split("\n")) {
                line = line.trim();
                if (line.length() > 0) out.add(line);
            }
        } catch (Throwable ignored) {
        }
        lst.delete();
        return out;
    }

    /**
     * 汇总弹窗：一次列出全部「游戏里没有对应资源、但补丁包给了精确路径」的文件。
     *
     * 点「全部新建」→ 全部按精确路径写进去；
     * 点「只跳过新增」→ 这些文件不装，已经存在的资源照常安装。
     *
     * 安装在后台线程跑，这里必须等用户点完；页面被系统销毁时按「跳过新增」处理，不能卡死。
     */
    private boolean confirmNewFiles(final List<String> paths) {
        if (paths == null || paths.isEmpty()) return false;
        if (isFinishing()) return false;
        final boolean[] yes = new boolean[]{false};
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("游戏里没有下面这些文件的对应资源，可以按补丁包里的精确路径新建：\n");
                int lim = Math.min(paths.size(), 12);
                for (int k = 0; k < lim; k++) sb.append("\n  • ").append(paths.get(k));
                if (paths.size() > lim) sb.append("\n  ……还有 ").append(paths.size() - lim).append(" 个");
                sb.append("\n\n「全部新建」按上面的路径写入；「只跳过新增」这些文件不装，")
                        .append("其它已有资源照常安装。");
                try {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("发现 " + paths.size() + " 个新文件")
                            .setMessage(sb.toString())
                            .setCancelable(false)
                            .setPositiveButton("全部新建", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    yes[0] = true;
                                    latch.countDown();
                                }
                            })
                            .setNegativeButton("只跳过新增", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    yes[0] = false;
                                    latch.countDown();
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface d) {
                                    latch.countDown();
                                }
                            })
                            .show();
                } catch (Throwable t) {
                    // 弹不出来就别拦着装：按「跳过新增」走，其它文件照常安装
                    log("! 汇总弹窗打不开，本次按「只跳过新增」处理：" + t);
                    latch.countDown();
                }
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return yes[0];
    }

    /**
     * 加载游戏 CAB 索引（缓存优先）。
     *
     * v3.8：按包名分目录存放，因为同一个游戏不同渠道服包名不同
     * （com.jumpw.mobile300 / com.jumpw.mobile.aligames …）。
     * 旧版把所有服平铺成 _storage_emulated_0_… 的 .tsv，看不出归属，
     * 也分不清换服时该复用还是重建。
     *
     * 目录：<rootStr>/缓存/资源索引/<包名>/{index.tsv, meta.txt}
     * 失效条件：files 下 data 或 flies 目录 mtime 变了（两者相加比对）。
     */
    private Map<String, List<String>> loadCabIndex(String gameStr, File tmpRoot, String rootStr) {
        File dir = indexDir(rootStr, gameStr);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            blog("[索引] ✗ 建不了缓存目录，跳过 CAB 校验：" + dir);
            return null;
        }
        String pkg = pkgOf(gameStr);
        File tsv = new File(dir, "index.tsv");
        File meta = new File(dir, "meta.txt");

        blog("[索引] 包名 " + (pkg == null ? "(未知)" : pkg) + "  目录 " + dir.getAbsolutePath());

        // v3.13：data 与 flies 两个目录的 mtime 相加，任一变动都会让缓存失效重建
        long gameMtime = dirMtime(gameStr + "/data", tmpRoot) + dirMtime(gameStr + "/flies", tmpRoot);
        if (tsv.isFile() && meta.isFile()) {
            String m;
            try {
                m = readText(meta).trim();
            } catch (IOException e) {
                m = "";
            }
            if (m.contains("mtime=" + gameMtime) && gameMtime > 0) {
                Map<String, List<String>> cached = readCabTsv(tsv);
                if (cached != null && !cached.isEmpty()) {
                    blog("[索引] 命中缓存 " + cached.size() + " 条，免扫描");
                    return cached;
                }
                blog("[索引] 缓存存在但内容为空，重建");
            } else {
                blog("[索引] 缓存失效（data+flies mtime=" + gameMtime + " 与记录不符），重建");
            }
        } else {
            blog("[索引] 该渠道服还没有索引，开始建表");
        }

        // 同一个包本次运行内建表失败过，就不再重试。
        // 否则 3 个补丁会把 26,218 个文件扫 3 遍（实测 3 × 约 24 分钟）。
        if (failedIndexPkgs.contains(pkg)) {
            blog("[索引] 包 " + pkg + " 本次已建表失败过，跳过重扫（避免重复耗时）");
            return null;
        }

        long t0 = System.currentTimeMillis();
        Map<String, List<String>> map = buildCabIndex(gameStr, tmpRoot, dir);
        long cost = (System.currentTimeMillis() - t0) / 1000L;
        if (map == null || map.isEmpty()) {
            failedIndexPkgs.add(pkg);
        }
        if (map != null && !map.isEmpty()) {
            try {
                writeText(meta, "pkg=" + pkg
                        + "\ngame=" + gameStr
                        + "\nmtime=" + gameMtime
                        + "\ncount=" + map.size()
                        + "\ncost=" + cost + "s"
                        + "\ntime=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                        + "\n");
            } catch (Throwable ignored) {
            }
            blog("[索引] ✓ 建表完成 " + map.size() + " 条，耗时 " + cost + "s → " + tsv.getName());
        } else {
            blog("[索引] ✗ 建表失败或为空，本次按同名唯一 / 精确路径定位");
        }
        return map;
    }

    /**
     * 当前这次安装的日志文件；null 表示没有文件可写。
     *
     * 旧版只有 log() 进界面，fileLog() 才落盘，建索引那十几分钟文件日志是空白，
     * 事后完全查不到卡在哪。关键阶段一律走 blog()。
     */
    private File currentLog;

    /** 本次运行内建表失败过的包名，避免对每个补丁都重扫一遍。 */
    private final java.util.Set<String> failedIndexPkgs = new java.util.HashSet<String>();

    /** 同时写界面日志和日志文件。 */
    private void blog(String line) {
        log(line);
        File lf = currentLog;
        if (lf != null) {
            try {
                fileLog(lf, line);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从游戏目录取包名：…/Android/data/<pkg>/files → <pkg>。 */
    static String pkgOf(String gameStr) {
        if (gameStr != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/Android/data/([^/]+)").matcher(gameStr);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** 索引目录：按包名隔离，不同渠道服互不覆盖。 */
    private File indexDir(String rootStr, String gameStr) {
        String pkg = pkgOf(gameStr);
        if (pkg == null || pkg.length() == 0) {
            pkg = "_unknown_" + new File(gameStr).getName();
        }
        return new File(new File(new File(rootStr, "缓存"), "资源索引"), pkg);
    }

    /** 目录的修改时间（毫秒）；失败返回 0。 */
    private long dirMtime(String path, File tmpRoot) {
        File sh = new File(tmpRoot, "mt_" + System.currentTimeMillis() + ".sh");
        File out = new File(tmpRoot, ".mt");
        out.delete();
        String body = "#!/system/bin/sh\nstat -c %Y " + Patcher.shQuote(path)
                + " 2>/dev/null > " + Patcher.shQuote(out.getAbsolutePath()) + "\n";
        try {
            writeText(sh, body);
            sh.setReadable(true, false);
            Shell.runScript(sh.getAbsolutePath());
            String s = readText(out).trim();
            if (s.length() == 0) return 0;
            return Long.parseLong(s) * 1000L;
        } catch (Throwable t) {
            return 0;
        } finally {
            sh.delete();
            out.delete();
        }
    }

    private Map<String, List<String>> readCabTsv(File tsv) {
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        try {
            for (String line : readText(tsv).split("\n")) {
                if (line.length() == 0) continue;
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String cab = line.substring(0, t);
                String rel = line.substring(t + 1);
                List<String> l = m.get(cab);
                if (l == null) {
                    l = new ArrayList<String>();
                    m.put(cab, l);
                }
                l.add(rel);
            }
        } catch (Throwable e) {
            return null;
        }
        return m;
    }

    /**
     * 扫游戏目录，建「CAB → 相对路径列表」表。
     *
     * v3.8 提速：旧版每个文件跑 head|sed|grep|head 四次 fork，26184 个文件
     * 约 13 万次进程创建，实测 26.5 条/秒（16 分 29 秒）。
     * 新脚本改成 find 出清单 + xargs 每 64 个一批，fork 降到几百次；
     * 判定规则完全不变（前 8 KB、第一处 CAB- + 至少 20 位十六进制）。
     *
     * 扫盘在 shell 侧跑，Shizuku(uid=2000) 和 root 都能用，不依赖 uid=0。
     */
    private Map<String, List<String>> buildCabIndex(String gameStr, File tmpRoot, File dir) {
        File raw = new File(dir, "index.tsv.tmp");
        File chk = new File(dir, "index.chk.tmp");
        // 清掉上次残留，包括两种通道的转存副本。
        // Shizuku 身份可能删不掉 root 建的文件，这里删不掉也不致命——
        // 脚本开头会再 rm + 校验目录可写，失败会明确报错退出。
        raw.delete();
        chk.delete();
        new File(dir, "index.tsv.tmp.local.tsv").delete();
        new File(dir, "index.tsv.tmp.local.chk").delete();
        new File(getFilesDir(), "index_result.tsv").delete();
        new File(getFilesDir(), "index_result.chk").delete();
        File sh = new File(tmpRoot, "cab_" + System.currentTimeMillis() + ".sh");
        String body = Patcher.buildCabIndexScript(gameStr, raw, true);
        try {
            writeText(sh, body);
            sh.setReadable(true, false);
            blog("[索引] 开始扫描（" + Shell.desc() + "）…");
        } catch (IOException e) {
            blog("[索引] ✗ 生成扫描脚本失败：" + e.getMessage());
            return null;
        }

        // 后台跑脚本，主线程轮询进度，避免界面看起来卡死
        final File outFile = raw;
        final String shPath = sh.getAbsolutePath();
        final long t0 = System.currentTimeMillis();
        Thread runner = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int rc = Shell.runScript(shPath);
                    if (rc != 0) blog("[索引] ⚠ 扫描脚本退出码 " + rc);
                } catch (Throwable t) {
                    blog("[索引] ⚠ 扫描异常：" + t);
                }
            }
        }, "cab-scan");
        runner.start();

        long lastLines = -1;
        long lastTick = 0;
        while (runner.isAlive()) {
            sleep(1000);
            long el = (System.currentTimeMillis() - t0) / 1000L;
            long lines = countLines(outFile);
            // 每 5 秒或条数有变化就报一次，界面不会看起来卡死
            if (lines != lastLines && (el - lastTick >= 5 || lines % 1000 == 0)) {
                lastLines = lines;
                lastTick = el;
                blog("[索引] 进行中 " + lines + " 条，已用 " + el + "s"
                        + (el > 0 ? "（约 " + (lines / el) + " 条/秒）" : ""));
            }
            if (el > 1800) {
                blog("[索引] ⚠ 超过 30 分钟未完成，可能卡住（已扫 " + lines + " 条）");
                break;
            }
        }
        // 等线程真正结束
        try {
            runner.join(2000);
        } catch (InterruptedException ignored) {
        }

        long cost = (System.currentTimeMillis() - t0) / 1000L;

        // 【关键】改从 App 私有目录读结果。
        // 实测：shell 把结果写在 /storage/emulated/0/... 下，文件确实存在、
        // 大小也对，但 App 进程 readText() 读出来是空（FUSE 可见性问题）。
        // 脚本末尾会把成品拷到私有目录，这里优先读那份副本。
        // 【双通道兼容】按顺序尝试三个来源：
        //   ① App 私有目录（root 通道会转存到这里）
        //   ② 索引目录下的 .local.tsv（Shizuku 通道会转存到这里）
        //   ③ 原始位置 outFile（最后兜底）
        File privateCopy = new File(getFilesDir(), "index_result.tsv");
        File privateChk = new File(getFilesDir(), "index_result.chk");
        File localCopy = new File(dir, outFile.getName() + ".local.tsv");
        File localChk = new File(dir, outFile.getName() + ".local.chk");
        File srcOut = privateCopy;
        File srcChk = privateChk;

        long produced = countLines(srcOut);
        if (produced == 0) {
            long privSize = privateCopy.isFile() ? privateCopy.length() : -1;
            long localLines = countLines(localCopy);
            blog("[索引] 私有副本不可用（size=" + privSize + "），索引目录副本 " + localLines + " 行");
            if (localLines > 0) {
                srcOut = localCopy;
                srcChk = localChk;
                produced = localLines;
            }
        }
        if (produced == 0) {
            long rawLines = countLines(outFile);
            if (rawLines > 0) {
                blog("[索引] 两个副本都不可用，回退读原始位置 " + rawLines + " 行");
                srcOut = outFile;
                srcChk = chk;
                produced = rawLines;
            }
        }
        blog("[索引] 扫描结束，耗时 " + cost + "s，产出 " + produced + " 行");

        // 硬校验：一次真实扫描不可能几百毫秒就结束，也不可能产出 0 行。
        // 出现这种情况多半是脚本本身有问题（比如 find 与 xargs 分隔符不匹配），
        // 必须明确报错，不能静静退化成“按同名唯一定位”。
        if (produced == 0) {
            blog("[索引] ✗ 产出为空：脚本可能执行失败（耗时 " + cost + "s）。"
                    + "请检查 临时解压/cab_*.sh 与 index.tsv.tmp.chk 是否正常");
        } else if (cost <= 2 && produced < 200) {
            blog("[索引] ⚠ 仅 " + cost + "s 就结束且只有 " + produced
                    + " 条，明显异常（可能参数分隔符不匹配）");
        }

        // 抽样自检：新旧算法结果对不上就不信任新索引
        try {
            if (srcChk.isFile() && srcChk.length() > 0) {
                int same = 0;
                int diff = 0;
                java.util.Set<String> newSet = readLines(srcOut);
                for (String l : readLines(srcChk)) {
                    if (newSet.contains(l)) same++;
                    else diff++;
                }
                if (diff == 0) {
                    blog("[索引] 自检通过（抽样 " + (same + diff) + " 条全部一致）");
                } else {
                    blog("[索引] ⚠ 自检不一致：一致 " + same + "、不一致 " + diff + "，请把日志发我");
                }
            } else if (produced > 0) {
                blog("[索引] 自检文件为空，未校验等价性");
            }
        } catch (Throwable ignored) {
        }

        Map<String, List<String>> m = new HashMap<String, List<String>>();
        String prefix = gameStr + "/";
        StringBuilder out = new StringBuilder(256 * 1024);
        try {
            for (String line : readText(srcOut).split("\n")) {
                if (line.length() == 0) continue;
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String cab = line.substring(0, t);
                String p = line.substring(t + 1);
                if (!p.startsWith(prefix)) continue;
                String rel = p.substring(prefix.length());
                List<String> l = m.get(cab);
                if (l == null) {
                    l = new ArrayList<String>();
                    m.put(cab, l);
                }
                l.add(rel);
                out.append(cab).append('\t').append(rel).append('\n');
            }
            if (out.length() > 0) writeText(new File(dir, "index.tsv"), out.toString());
        } catch (Throwable e) {
            blog("[索引] ✗ 解析结果失败：" + e);
        }
        // 只在真正拿到结果时才清临时文件；失败则保留现场，方便排查。
        if (!m.isEmpty()) {
            raw.delete();
            chk.delete();
        } else {
            blog("[索引] 临时文件已保留（便于排查）: " + raw.getAbsolutePath());
        }
        sh.delete();
        return m;
    }

    private long countLines(File f) {
        if (f == null || !f.isFile()) return 0;
        java.io.BufferedReader r = null;
        long n = 0;
        try {
            r = new java.io.BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            while (r.readLine() != null) n++;
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private java.util.Set<String> readLines(File f) {
        java.util.Set<String> s = new java.util.HashSet<String>();
        if (f == null || !f.isFile()) return s;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 0) s.add(line);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return s;
    }



    /** 上一次安装里被跳过的文件（给完成弹窗提示用）。 */
    private List<String> lastSkip = new ArrayList<String>();

    /**
     * 跳过的文件只做一行提示，不另弹窗。
     * 具体原因已由 mapStage() 写进操作日志的 [定位] 段，这里只说去哪看。
     */
    private String skipTip(List<String> skipped) {
        if (skipped == null || skipped.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n⚠ 有 ").append(skipped.size()).append(" 个文件未安装：");
        int lim = Math.min(skipped.size(), 5);
        for (int i = 0; i < lim; i++) {
            sb.append("\n  • ").append(skipped.get(i));
        }
        if (skipped.size() > lim) {
            sb.append("\n  ……还有 ").append(skipped.size() - lim).append(" 个");
        }
        sb.append("\n\n具体原因见「操作日志」里的 [定位] 记录。");
        return sb.toString();
    }

    /** 目录下有没有至少一个文件（递归）。 */
    private static boolean hasAnyFile(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            if (f.isFile()) return true;
            if (f.isDirectory() && hasAnyFile(f)) return true;
        }
        return false;
    }






    private void doInstall() {
        if (busy) {
            toast("正在跑，别急");
            return;
        }
        if (!checkReady()) return;
        savePrefs();

        final String rootStr = text(etRoot, DEF_ROOT);
        final String patchStr = text(etPatch, rootStr + "/补丁存放位置（zip）");
        final String gameStr = text(etGame, DEF_GAME);
        final boolean backup = cbBackup.isChecked();

        startWorker(new Runnable() {
            @Override
            public void run() {
                File root = new File(rootStr);
                File patchDir = new File(patchStr);
                if (!root.exists() && !root.mkdirs()) {
                    log("✗ 根目录建不出来: " + rootStr);
                    log("  确认「所有文件访问」已授予。");
                    return;
                }
                if (!patchDir.isDirectory()) {
                    log("✗ 补丁目录读不到: " + patchStr);
                    log("  1) 确认路径拼写；2) 确认「所有文件访问」已授予（点 ②）。");
                    return;
                }
                List<File> zips = Patcher.listPatches(patchDir);
                if (zips.isEmpty()) {
                    log("! 补丁目录里没有 .zip：" + patchStr);
                    return;
                }
                log("补丁目录读到了 " + zips.size() + " 个 zip。");

                // 游戏目录只能由 shell 判断（应用进程看不到 Android/data）
                log("正在用 Shizuku 检查游戏目录…");
                if (!shellDirExists(gameStr)) {
                    log("✗ 游戏目录不存在（shell 视角）: " + gameStr);
                    log("  官服应是 com.jumpw.mobile300；渠道服点「🔍 自动探测」。");
                    File dataDir = new File("/storage/emulated/0/Android/data");
                    log("  提示：应用进程本身看不到 Android/data，这是正常的，检测走的是 Shizuku。");
                    return;
                }
                log("✓ 游戏目录存在。");

                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                File logDir = new File(root, "操作日志");
                logDir.mkdirs();
                File logFile = new File(logDir, "install_" + stamp + ".log");
                File tmpRoot = new File(root, "临时解压");
                tmpRoot.mkdirs();
                pruneTmp(tmpRoot);
                File backupBase = backup ? backupOrig(rootStr, gameStr) : null;

                currentLog = logFile;   // 让 blog() 从这一刻开始就双重写入
                failedIndexPkgs.clear();  // 新一轮安装重新允许建表
                blog("补丁目录：" + patchStr);
                blog("游戏目录：" + gameStr);
                blog("执行方式：" + Shell.desc());
                blog("共 " + zips.size() + " 个补丁待处理");

                final List<String> skippedAll = new ArrayList<String>();
                int index = 0;
                for (File zip : zips) {
                    index++;
                    File stage = new File(tmpRoot, stamp + "_" + index);
                    deleteRecursive(stage);
                    log("");
                    log("=== [" + index + "/" + zips.size() + "] " + zip.getName() + " ===");
                    int n;
                    try {
                        n = Patcher.extract(zip, stage, new Patcher.Progress() {
                            @Override
                            public void onProgress(String message) {
                                postLog(message);
                            }
                        });
                    } catch (IOException e) {
                        log("✗ 解压失败，跳过: " + e.getMessage());
                        fileLog(logFile, "✗ 解压失败: " + e.getMessage());
                        deleteRecursive(stage);
                        continue;
                    }
                    log("解压完成：" + n + " 个文件");
                    fileLog(logFile, "解压完成：" + n + " 个文件");

                    File useStage = mapStage(stage, tmpRoot, gameStr, stamp + "_" + index, rootStr, skippedAll);
                    if (useStage == null) {
                        blog("✗ 这个补丁里没有能装的文件，跳过：" + zip.getName());
                        deleteRecursive(stage);
                        continue;
                    }

                    File script = new File(tmpRoot, "run_" + stamp + "_" + index + ".sh");
                    boolean direct = Shell.uid() == 0;
                    String body = Patcher.buildInstallScript(zip.getName(), useStage, new File(gameStr),
                            backupBase, logFile, direct);
                    try {
                        writeText(script, body);
                        script.setReadable(true, false);
                    } catch (IOException e) {
                        log("✗ 生成脚本失败: " + e.getMessage());
                        continue;
                    }
                    log("开始部署（Shizuku → " + gameStr + "）…");
                    int rc = runAndTail(script, logFile);
                    blog("退出码: " + rc);
                    if (rc == 0) {
                        // 从本次日志里数出成功写入的文件数，一并记进清单
                        int wrote = numFromLog(logFile, "合计 (\\d+) 个文件");
                        markInstalled(patchDir, zip, gameStr, wrote);
                    }
                    deleteRecursive(stage);
                    if (useStage != null && !useStage.equals(stage)) deleteRecursive(useStage);
                    script.delete();
                }
                log("");
                log("=== 全部补丁处理完毕 ===");
                log("日志: " + logFile.getAbsolutePath());
                fileLog(logFile, "=== 全部补丁处理完毕 ===");
                int okFiles = sumFromLog(logFile, "成功 (\\d+)，失败");
                int badFiles = sumFromLog(logFile, "成功 \\d+，失败 (\\d+)");
                String tail = (okFiles < 0 ? "" : ("写入文件：成功 " + okFiles + " 个" + (badFiles > 0 ? "，失败 " + badFiles + " 个" : "") + "\n"));
                doneDialog("补丁安装完成",
                        "补丁数量：" + zips.size() + " 个 zip\n" + tail
                                + "游戏目录：\n" + gameStr
                                + skipTip(skippedAll)
                                + "\n\n可以打开游戏验证了（首次进游戏可能需要重登）。\n详细日志：" + logFile.getName(),
                        gameStr);
            }
        });
    }

    private int runAndTail(File script, File logFile) {
        final AtomicInteger rc = new AtomicInteger(Integer.MIN_VALUE);
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    rc.set(Shell.runScript(script.getAbsolutePath()));
                } catch (Throwable t) {
                    err.set(t);
                    rc.set(-1);
                }
            }
        }, "patch-runner");
        worker.start();

        long readPos = 0;
        long start = System.currentTimeMillis();
        while (rc.get() == Integer.MIN_VALUE) {
            readPos = tail(logFile, readPos);
            if (System.currentTimeMillis() - start > 15 * 60 * 1000L) {
                log("! 等太久了，脚本可能还在跑，请看日志文件。");
                break;
            }
            sleep(350);
        }
        try {
            worker.join(3000);
        } catch (InterruptedException ignored) {
        }
        tail(logFile, readPos);
        if (err.get() != null) {
            log("✗ 执行失败: " + err.get());
            log("  检查 Shizuku 是否还在运行、权限是否被撤销。");
        }
        return rc.get() == Integer.MIN_VALUE ? -1 : rc.get();
    }

    private long tail(File f, long pos) {
        if (!f.exists()) return pos;
        FileInputStream in = null;
        try {
            long len = f.length();
            if (len <= pos) return pos;
            in = new FileInputStream(f);
            long skip = pos;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) break;
                skip -= s;
            }
            byte[] buf = new byte[8192];
            int n;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), "UTF-8");
            for (String line : text.split("\n")) {
                if (line.trim().length() > 0) postLog(line);
            }
            return len;
        } catch (IOException e) {
            return pos;
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void doPack() {
        if (busy) {
            toast("正在跑，别急");
            return;
        }
        if (!checkStorageOnly()) return;
        savePrefs();
        final String rootStr = text(etRoot, DEF_ROOT);
        final String rel = etRel.getText().toString();
        if (Patcher.sanitizeRel(rel).length() == 0) {
            toast("先填游戏内相对路径，或从上面的预设里选一个");
            return;
        }
        askPackName(rootStr, rel);
    }

    /**
     * 打包前问名字：输入什么就用什么，默认填「补丁_<时间>」。
     * 以前这里直接拿时间戳命名，用户改不了名。
     */
    private void askPackName(final String rootStr, final String rel) {
        final String def = "补丁_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(def);
        et.setSelection(def.length());
        new AlertDialog.Builder(this)
                .setTitle("打包成什么名字")
                .setMessage("存到：" + rootStr + "\n文件名会自动补 .zip，重名自动加 _2")
                .setView(et)
                .setPositiveButton("开始打包", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = Patcher.sanitizeZipName(et.getText().toString());
                        if (name.length() == 0) {
                            toast("名字不合法，改用 " + def + ".zip");
                            name = def + ".zip";
                        }
                        startPack(rootStr, rel, name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按确认的名字打包；重名不覆盖，自动加序号。 */
    private void startPack(final String rootStr, final String rel, final String zipName) {
        startWorker(new Runnable() {
            @Override
            public void run() {
                File root = new File(rootStr);
                File src = new File(root, "打包补丁/待打包资源");
                if (!src.isDirectory()) {
                    log("✗ 没有待打包资源目录: " + src);
                    return;
                }
                File[] items = src.listFiles();
                if (items == null || items.length == 0) {
                    log("✗ 「待打包资源」是空的。");
                    return;
                }
                File out = Patcher.uniqueZipFile(root, zipName);
                log("正在打包 → " + out.getAbsolutePath());
                try {
                    int n = Patcher.pack(src, rel, out);
                    log("✓ 打包成功：" + n + " 个文件");
                    log("  压缩包：" + out.getName() + "（" + out.getAbsolutePath() + "）");
                    log("  内部路径：files/" + Patcher.sanitizeRel(rel) + "/");
                } catch (IOException e) {
                    log("✗ 打包失败: " + e.getMessage());
                }
            }
        });
    }

    private boolean checkReady() {
        if (!Shell.ready()) {
            toast("还没就绪：授权 Shizuku 或勾选 root 通道");
            return false;
        }
        return checkStorageOnly();
    }

    private boolean checkStorageOnly() {
        if (!hasStorage()) {
            toast("先点 ② 给本应用「所有文件访问」");
            return false;
        }
        return true;
    }

    // ---------------- 工具 ----------------

    /** 取输入框里的路径：去空白、去结尾斜杠（用户可能直接粘 …/files/ 这种带斜杠的）。 */
    private String text(EditText e, String def) {
        String s = e.getText().toString().trim();
        if (s.length() == 0) s = def;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void startWorker(final Runnable body) {
        busy = true;
        setButtonsEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    body.run();
                } catch (Throwable t) {
                    log("✗ 异常: " + t);
                } finally {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            setButtonsEnabled(true);
                        }
                    });
                }
            }
        }, "patch-worker").start();
    }

    private void setButtonsEnabled(boolean enabled) {
        btnInstall.setEnabled(enabled);
        btnPack.setEnabled(enabled);
        btnMkdir.setEnabled(enabled);
        btnDetect.setEnabled(enabled);
        btnAnti.setEnabled(enabled);
        btnRestore.setEnabled(enabled);
        btnClean.setEnabled(enabled);
    }

    private void postLog(final String line) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                log(line);
            }
        });
    }

    /** 线程安全：任何线程都能调，UI 更新一律甩回主线程。 */
    private void log(final String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    appendLog(line);
                }
            });
            return;
        }
        appendLog(line);
    }

    private void appendLog(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        logBuf.append("[").append(ts).append("] ").append(line).append("\n");
        if (logBuf.length() > 40000) logBuf.delete(0, logBuf.length() - 30000);
        tvLog.setText(logBuf.toString());
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void fileLog(File logFile, String line) {
        try {
            File parent = logFile.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream os = new FileOutputStream(logFile, true);
            try {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
                os.write(("[" + ts + "] " + line + "\n").getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void toast(final String msg) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void writeText(File f, String text) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(text.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    private static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        etRoot.setText(p.getString("root", DEF_ROOT));
        etPatch.setText(p.getString("patch", DEF_ROOT + "/补丁存放位置（zip）"));
        etGame.setText(p.getString("game", DEF_GAME));
        etRel.setText(p.getString("rel", ""));
        cbBackup.setChecked(p.getBoolean("backup", true));
        cbRoot.setChecked(p.getBoolean("rootmode", true));
        Shell.setPreferRoot(cbRoot.isChecked());
    }

    private void savePrefs() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("root", text(etRoot, DEF_ROOT))
                .putString("patch", text(etPatch, DEF_ROOT + "/补丁存放位置（zip）"))
                .putString("game", text(etGame, DEF_GAME))
                .putString("rel", etRel.getText().toString())
                .putBoolean("backup", cbBackup.isChecked())
                .putBoolean("rootmode", cbRoot.isChecked())
                .apply();
    }
    /** 清空日志：单纯清屏幕，还是连 操作日志 里的 .log 文件一起删。 */
    private void confirmClearLog() {
        final File dir = new File(text(etRoot, DEF_ROOT), "操作日志");
        File[] all = dir.listFiles();
        final List<File> logs = new ArrayList<File>();
        long bytes = 0;
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && f.getName().endsWith(".log")) {
                    logs.add(f);
                    bytes += f.length();
                }
            }
        }
        final int n = logs.size();
        final long kb = (bytes + 1023) / 1024;
        if (n == 0) {
            logBuf.setLength(0);
            tvLog.setText("");
            log("\u2139 屏幕已清空；操作日志目录里没有 .log 文件。");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("屏幕上的日志会立刻清空。\n\n"
                        + "磁盘上（" + dir.getAbsolutePath() + "）还有 " + n + " 个日志文件，约 " + kb + " KB。\n"
                        + "要一起删掉吗？删掉后就没法回看之前几次的运行记录了。")
                .setPositiveButton("一起删（" + n + " 个）", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int ok = 0, fail = 0;
                        for (File f : logs) {
                            if (f.delete()) ok++; else fail++;
                        }
                        logBuf.setLength(0);
                        tvLog.setText("");
                        if (fail == 0) log("\u2713 屏幕已清空，已删除 " + ok + " 个日志文件（约 " + kb + " KB）");
                        else log("\u26a0 屏幕已清空；日志文件删除 " + ok + " 个，失败 " + fail + " 个");
                    }
                })
                .setNeutralButton("只清屏幕", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        logBuf.setLength(0);
                        tvLog.setText("");
                        log("\u2139 屏幕已清空；磁盘上 " + n + " 个日志文件保留");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 界面提示：备份会按当前填的游戏目录归类到哪个包名下。 */
    private void refreshBackupHint() {
        if (tvBkHint == null) return;
        String gameStr = text(etGame, DEF_GAME);
        String pkg = pkgOfGame(gameStr);
        if (pkg.length() == 0) {
            tvBkHint.setText("备份归类：路径里没认出包名，会存到 备份文件/原版/");
        } else {
            tvBkHint.setText("备份归类：备份文件/" + pkg + "/原版/");
        }
    }

    /** 从游戏 files 路径里取出包名。 */
    private String pkgOfGame(String gameStr) {
        int i = gameStr.indexOf("/Android/data/");
        if (i < 0) return "";
        String rest = gameStr.substring(i + "/Android/data/".length());
        int j = rest.indexOf('/');
        return j < 0 ? rest : rest.substring(0, j);
    }

    /**
     * 原版备份目录：按游戏包名分开，避免官服/渠道服互相覆盖。
     * 老的共享目录 备份文件/原版 会在第一次用到时归档到对应包名下。
     */
    private File backupOrig(String rootStr, String gameStr) {
        File root = new File(rootStr);
        File legacy = new File(root, "备份文件/原版");
        String pkg = pkgOfGame(gameStr);
        if (pkg.length() == 0) return legacy;
        File dir = new File(new File(new File(root, "备份文件"), pkg), "原版");
        if (!dir.exists() && legacy.isDirectory()) {
            File parent = dir.getParentFile();
            if (parent != null) parent.mkdirs();
            if (legacy.renameTo(dir)) {
                log("\u2139 旧备份已归档到按包名分开的位置: 备份文件/" + pkg + "/原版");
                return dir;
            }
        }
        return dir;
    }

    /**
     * 安装成功后，把这个 zip 记进「已安装」清单。
     *
     * v3.8：按游戏包名分文件，否则官服和渠道服会混在同一张表里：
     *   <补丁目录>/.已安装/<包名>.tsv
     *     zip名 <TAB> 安装时间 <TAB> 游戏目录 <TAB> 写入文件数
     *
     * 旧的单文件 <补丁目录>/.已安装.tsv 不再写入，但读旧记录的代码保留兼容。
     */
    private void markInstalled(File patchDir, File zip) {
        markInstalled(patchDir, zip, text(etGame, DEF_GAME), -1);
    }

    private void markInstalled(File patchDir, File zip, String gameStr, int written) {
        String pkg = pkgOf(gameStr);
        if (pkg == null || pkg.length() == 0) pkg = "_unknown";
        File dir = new File(patchDir, ".已安装");
        if (!dir.isDirectory()) dir.mkdirs();
        File idx = new File(dir, pkg + ".tsv");

        List<String> keep = new ArrayList<String>();
        BufferedReader r = null;
        try {
            if (idx.isFile()) {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(idx), "UTF-8"));
                String line;
                String prefix = zip.getName() + "\t";
                while ((line = r.readLine()) != null) {
                    if (line.length() > 0 && !line.startsWith(prefix)) keep.add(line);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        keep.add(zip.getName() + "\t"
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                + "\t" + gameStr
                + "\t" + (written < 0 ? "?" : String.valueOf(written)));
        StringBuilder sb = new StringBuilder();
        for (String l : keep) sb.append(l).append('\n');
        try {
            writeText(idx, sb.toString());
            blog("[安装清单] 已记录 · 包名 " + pkg + " · " + zip.getName()
                    + " → " + idx.getAbsolutePath());
        } catch (IOException e) {
            blog("\u26a0 写入安装清单失败: " + e.getMessage());
        }
    }

    /** 找出当前包名下已安装的 zip 名集合（兼容旧的单文件清单）。 */
    private java.util.Set<String> installedNames(File patchDir, String gameStr) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        String pkg = pkgOf(gameStr);
        File f = new File(new File(patchDir, ".已安装"), (pkg == null ? "_unknown" : pkg) + ".tsv");
        File legacy = new File(patchDir, ".已安装.tsv");
        for (File src : new File[]{f, legacy}) {
            if (!src.isFile()) continue;
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(src), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t > 0) out.add(line.substring(0, t));
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (r != null) r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** 删除已经装过的补丁 zip（zip 留在补丁目录里，手动清掉）。 */
    private void confirmDeleteInstalled() {
        final String rootStr = text(etRoot, DEF_ROOT);
        final String patchStr = text(etPatch, rootStr + "/补丁存放位置（zip）");
        final String gameStr = text(etGame, DEF_GAME);
        final File patchDir = new File(patchStr);
        // v3.8 起记录写在 .已安装/<包名>.tsv，旧的单文件仍兼容
        java.util.Set<String> installed = installedNames(patchDir, gameStr);
        if (installed.isEmpty()) {
            toast("还没有安装记录：先装一次补丁");
            return;
        }
        final List<String> names = new ArrayList<String>();
        final List<File> files = new ArrayList<File>();
        long total = 0;
        for (String name : installed) {
            File f = new File(patchDir, name);
            if (f.isFile()) {
                names.add(name);
                files.add(f);
                total += f.length();
            }
        }
        if (files.isEmpty()) {
            toast("清单里的 zip 已经不在了，没有可删的");
            return;
        }
        StringBuilder msg = new StringBuilder();
        for (String n : names) msg.append("· ").append(n).append("\n");
        new AlertDialog.Builder(this)
                .setTitle("删除已安装的补丁 zip？")
                .setMessage("这几个补丁已经装进游戏了，删除只是清掉补丁目录里的 zip 文件，不会卸载游戏里的补丁、也不动备份。\n\n"
                        + msg + "\n共 " + (total / 1024) + " KB")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int ok = 0, fail = 0;
                        for (File f : files) {
                            if (f.delete()) ok++; else fail++;
                        }
                        removeInstalled(patchDir, gameStr, names);
                        if (fail == 0) log("\u2713 已删除 " + ok + " 个补丁 zip（游戏里的补丁和备份都没动）");
                        else log("\u26a0 删除 " + ok + " 个，失败 " + fail + " 个");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 删除记录：同时清理 .已安装/<包名>.tsv 与旧的 .已安装.tsv。 */
    private void removeInstalled(File patchDir, String gameStr, java.util.Collection<String> names) {
        String pkg = pkgOf(gameStr);
        File cur = new File(new File(patchDir, ".已安装"), (pkg == null ? "_unknown" : pkg) + ".tsv");
        File legacy = new File(patchDir, ".已安装.tsv");
        for (File src : new File[]{cur, legacy}) {
            if (!src.isFile()) continue;
            List<String> rest = new ArrayList<String>();
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(src), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    String name = t > 0 ? line.substring(0, t) : line;
                    if (!names.contains(name)) rest.add(line);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (r != null) r.close();
                } catch (Throwable ignored) {
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String l : rest) sb.append(l).append('\n');
            try {
                writeText(src, sb.toString());
            } catch (IOException ignored) {
            }
        }
    }

    /** 从本次的日志文件里抓一个数字出来（抓不到返回 -1）。 */
    private int numFromLog(File logFile, String regex) {
        if (logFile == null || !logFile.isFile()) return -1;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), "UTF-8"));
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
            String line;
            while ((line = r.readLine()) != null) {
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find()) {
                    try {
                        return Integer.parseInt(m.group(1));
                    } catch (Exception ignored) {
                        return -1;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 把日志里所有匹配到的数字加起来（多个补丁时用）。 */
    private int sumFromLog(File logFile, String regex) {
        if (logFile == null || !logFile.isFile()) return -1;
        BufferedReader r = null;
        int sum = 0;
        boolean hit = false;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), "UTF-8"));
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
            String line;
            while ((line = r.readLine()) != null) {
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find()) {
                    try {
                        sum += Integer.parseInt(m.group(1));
                        hit = true;
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return hit ? sum : -1;
    }

    /** 操作完成后的结果弹窗（可以顺手打开游戏）。 */
    private void doneDialog(final String title, final String message, final String gameStr) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(title)
                        .setMessage(message)
                        .setNegativeButton("关闭", null);
                if (gameStr != null && pkgOfGame(gameStr).length() > 0) {
                    b.setPositiveButton("打开游戏", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            launchGame(gameStr);
                        }
                    });
                }
                b.show();
            }
        });
    }

    private void launchGame(String gameStr) {
        String pkg = pkgOfGame(gameStr);
        if (pkg.length() == 0) {
            toast("从路径里认不出包名");
            return;
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                toast("系统里找不到 " + pkg + " 的启动入口");
                return;
            }
            startActivity(i);
        } catch (Throwable t) {
            toast("打开失败: " + t);
        }
    }

}