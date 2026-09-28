package com.skipad.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 极简调试日志：环形缓冲 + 监听回调 + Logcat 双写。
 * 用途：让 SkipAdService 在运行时把"打开应用/检测界面/匹配规则/点击"等事件透出，
 * 既可在设置页实时显示，也可通过悬浮窗在切到其他 app 时看到。
 * 缓冲上限 300 行，超出丢弃最旧；始终写入 Logcat（tag=SkipAd），方便 adb logcat -s SkipAd。
 */
public class DebugLog {
    public interface Listener {
        void onNewLine(String line);
    }

    private static final int MAX = 300;
    private static final LinkedList<String> buffer = new LinkedList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Object lock = new Object();
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");
    /** 时间戳按秒缓存：事件风暴下每秒只做一次格式化，其余行直接复用 */
    private static long cachedSec = -1;
    private static String cachedTimeStr = "";

    /** 总开关：发布版可置 false 关闭全部日志 */
    public static boolean ENABLED = true;

    public static void d(String tag, String msg) {
        if (!ENABLED) return;
        String line;
        synchronized (lock) {
            long sec = System.currentTimeMillis() / 1000;
            if (sec != cachedSec) {
                cachedSec = sec;
                cachedTimeStr = SDF.format(new Date(sec * 1000));
            }
            line = cachedTimeStr + " [" + tag + "] " + msg;
            buffer.addLast(line);
            while (buffer.size() > MAX) buffer.removeFirst();
        }
        Log.d("SkipAd", line);
        for (Listener l : listeners) {
            try { l.onNewLine(line); } catch (Throwable ignored) { }
        }
    }

    public static List<String> snapshot() {
        synchronized (lock) { return new LinkedList<>(buffer); }
    }

    public static void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static void clear() {
        synchronized (lock) { buffer.clear(); }
    }
}
