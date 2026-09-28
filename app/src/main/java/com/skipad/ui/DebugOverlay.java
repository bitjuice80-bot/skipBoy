package com.skipad.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.skipad.R;
import com.skipad.util.DebugLog;

import java.util.List;

/**
 * 悬浮调试窗：以 TYPE_APPLICATION_OVERLAY 显示最近若干条日志。
 * 改进（相对初版 MATCH_PARENT 不可触碰）：
 *  - 固定小尺寸（默认约 200x150dp），不遮挡整屏；
 *  - 标题栏按住拖动（FLAG_NOT_TOUCHABLE 已移除，仅本窗口可接收触摸）；
 *  - 右下角手柄拖动调整大小（带最小/最大限制）；
 *  - 右上角 × 隐藏，并通过 OnHideListener 通知设置页取消勾选。
 * 仍保留 FLAG_NOT_FOCUSABLE，不抢输入焦点/键盘。
 */
public class DebugOverlay implements DebugLog.Listener {
    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private View root;
    private TextView logText;
    private final int minW, minH, maxW, maxH;

    private float startX, startY;
    private int origX, origY, origW, origH;

    private OnHideListener hideListener;

    public interface OnHideListener { void onHidden(); }

    public DebugOverlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        int d = (int) (ctx.getResources().getDisplayMetrics().density + 0.5f);
        minW = 120 * d; minH = 80 * d; maxW = 420 * d; maxH = 420 * d;

        lp = new WindowManager.LayoutParams();
        lp.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; // 不抢焦点、可触摸
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = 200 * d;
        lp.height = 150 * d;
        lp.x = 0;
        lp.y = 0;
    }

    public void setOnHideListener(OnHideListener l) { this.hideListener = l; }

    public void show() {
        if (root != null) return;
        root = LayoutInflater.from(ctx).inflate(R.layout.overlay_debug, null);
        logText = root.findViewById(R.id.logText);
        logText.setMovementMethod(new ScrollingMovementMethod());
        refresh(DebugLog.snapshot());
        bindDrag();
        bindResize();
        root.findViewById(R.id.btnHide).setOnClickListener(v -> hide());
        wm.addView(root, lp);
        DebugLog.addListener(this);
    }

    public void hide() {
        if (root == null) return;
        DebugLog.removeListener(this);
        try { wm.removeView(root); } catch (Exception ignored) { }
        root = null;
        if (hideListener != null) hideListener.onHidden();
    }

    public boolean isShown() { return root != null; }

    @Override
    public void onNewLine(String line) { refresh(DebugLog.snapshot()); }

    private void bindDrag() {
        View title = root.findViewById(R.id.dragTitle);
        title.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    origX = lp.x; origY = lp.y;
                    break;
                case MotionEvent.ACTION_MOVE:
                    lp.x = origX + (int) (e.getRawX() - startX);
                    lp.y = origY + (int) (e.getRawY() - startY);
                    wm.updateViewLayout(root, lp);
                    break;
            }
            return true;
        });
    }

    private void bindResize() {
        View handle = root.findViewById(R.id.resizeHandle);
        handle.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    origW = lp.width; origH = lp.height;
                    break;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - startX);
                    int dy = (int) (e.getRawY() - startY);
                    lp.width = Math.max(minW, Math.min(maxW, origW + dx));
                    lp.height = Math.max(minH, Math.min(maxH, origH + dy));
                    wm.updateViewLayout(root, lp);
                    break;
            }
            return true;
        });
    }

    private void refresh(List<String> lines) {
        int start = Math.max(0, lines.size() - 16);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
        final String text = sb.toString();
        if (logText == null) return;
        if (logText.getHandler() != null) {
            logText.post(() -> logText.setText(text));
        } else {
            logText.setText(text);
        }
    }
}
