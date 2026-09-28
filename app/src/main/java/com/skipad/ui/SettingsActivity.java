package com.skipad.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.accessibility.AccessibilityManager;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.skipad.R;
import com.skipad.rule.RuleEngine;
import com.skipad.subscriber.Subscriber;
import com.skipad.util.DebugLog;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 极简设置页：开启无障碍、更新订阅、导入本地规则、电池白名单引导。
 * 另含"调试浮窗"开关：开启后可在任意界面顶部实时看到 SkipAd 的运行日志。
 * 不引入 AndroidX/Compose，保持小体积与低资源占用。
 */
public class SettingsActivity extends Activity {
    private static final int REQ_PICK = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_EXPORT = 1003;
    private static final String SP_UI = "skipad_ui";
    private static final String KEY_OVERLAY = "debug_overlay";

    private TextView tvStatus;
    private TextView tvLog;
    private Switch swDebug;
    private RuleEngine engine;
    private Subscriber subscriber;
    private DebugOverlay overlay;

    private final DebugLog.Listener logListener = line -> runOnUiThread(() -> appendLog(line));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        swDebug = findViewById(R.id.swDebug);
        engine = RuleEngine.getInstance(this);
        engine.load();
        subscriber = new Subscriber(this, engine);
        overlay = new DebugOverlay(this);
        // 点浮窗右上角 × 隐藏时，同步取消开关勾选并清掉持久化标记（注意：不要再调 hide，否则递归）
        overlay.setOnHideListener(() -> runOnUiThread(() -> {
            swDebug.setChecked(false);
            getSharedPreferences(SP_UI, MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY, false).apply();
        }));

        // 设置页内也实时显示日志（即使不开浮窗也能看）
        DebugLog.addListener(logListener);
        refreshLog();

        boolean overlayOn = getSharedPreferences(SP_UI, MODE_PRIVATE).getBoolean(KEY_OVERLAY, false);
        swDebug.setChecked(overlayOn && Settings.canDrawOverlays(this));

        swDebug.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    startOverlay();
                } else {
                    // 未授权悬浮窗权限：引导去设置，授权返回后再开
                    swDebug.setChecked(false);
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_OVERLAY);
                }
            } else {
                stopOverlay();
            }
        });

        findViewById(R.id.btnOpenService).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnUpdate).setOnClickListener(v -> {
            Toast.makeText(this, R.string.toast_updating, Toast.LENGTH_SHORT).show();
            subscriber.updateAsync(new Subscriber.Callback() {
                @Override public void onDone(int count, String warn) {
                    runOnUiThread(() -> tvStatus.setText(warn == null
                            ? getString(R.string.toast_update_done) + " (" + count + ")"
                            : "部分成功(" + count + "): " + warn));
                }
                @Override public void onError(Exception e) {
                    runOnUiThread(() -> tvStatus.setText("更新失败: " + e.getMessage()));
                }
            });
        });
        findViewById(R.id.btnImport).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK);
        });
        // 导出日志：SAF 让用户选保存位置（如 Downloads），无需存储权限
        findViewById(R.id.btnExport).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            String ts = new SimpleDateFormat("MMdd_HHmmss", Locale.getDefault()).format(new Date());
            i.putExtra(Intent.EXTRA_TITLE, "skipad_log_" + ts + ".txt");
            startActivityForResult(i, REQ_EXPORT);
        });
        findViewById(R.id.btnWhitelist).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        if (!isServiceEnabled()) tvStatus.setText(R.string.toast_service_off);
    }

    private void startOverlay() {
        if (overlay.isShown()) return;
        overlay.show();
        getSharedPreferences(SP_UI, MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY, true).apply();
        Toast.makeText(this, "调试浮窗已开启，切到其他 app 即可看到日志", Toast.LENGTH_SHORT).show();
    }

    private void stopOverlay() {
        overlay.hide();
        getSharedPreferences(SP_UI, MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY, false).apply();
    }

    private void appendLog(String line) {
        tvLog.append(line + "\n");
        // 超出一定长度截断，避免内存增长
        if (tvLog.length() > 8000) {
            tvLog.setText(tvLog.getText().subSequence(tvLog.length() - 6000, tvLog.length()));
        }
    }

    private void refreshLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : DebugLog.snapshot()) sb.append(l).append("\n");
        tvLog.setText(sb.toString());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            subscriber.importLocalAsync(uri, new Subscriber.Callback() {
                @Override public void onDone(int count, String warn) {
                    runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                            R.string.toast_import_done, Toast.LENGTH_SHORT).show());
                }
                @Override public void onError(Exception e) {
                    runOnUiThread(() -> tvStatus.setText("导入失败: " + e.getMessage()));
                }
            });
        } else if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                StringBuilder sb = new StringBuilder();
                for (String l : DebugLog.snapshot()) sb.append(l).append("\n");
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "日志已导出：" + uri.getLastPathSegment(), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                tvStatus.setText("导出失败: " + e.getMessage());
            }
        } else if (requestCode == REQ_OVERLAY) {
            // 从悬浮窗权限设置返回：已授权则直接开启
            if (Settings.canDrawOverlays(this)) {
                swDebug.setChecked(true);
                startOverlay();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DebugLog.removeListener(logListener);
        overlay.hide(); // 离开设置页时收掉浮窗，避免残留
    }

    private boolean isServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<android.accessibilityservice.AccessibilityServiceInfo> list = am.getInstalledAccessibilityServiceList();
        for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
            if (info.getId().contains("SkipAdService")) return true;
        }
        return false;
    }
}
