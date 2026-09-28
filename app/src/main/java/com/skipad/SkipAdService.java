package com.skipad;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.skipad.engine.RuleMatcher;
import com.skipad.model.InternalRule;
import com.skipad.rule.RuleEngine;
import com.skipad.util.DebugLog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 核心：事件驱动的无障碍跳过服务。
 * 设计铁律（低资源）：
 *  1. 仅在系统回调 onAccessibilityEvent 中处理，绝不另起线程轮询；
 *  2. 以【活跃窗口】的包名为过滤基准（而非事件包名）：事件可能来自状态栏/桌面/文件选择器，
 *     而实际搜索的是 getRootInActiveWindow 返回的窗口，二者错配曾导致
 *     B 站弹幕被误关、设置页日志框在按返回键时被误点；
 *  3. 自身窗口与系统窗口（桌面/状态栏/AOD/输入法）直接忽略，永不搜索；
 *  4. CONTENT_CHANGED 按窗口包名去抖 + 点击冷却，杜绝事件风暴下的重复点击；
 *  5. 命中后用 RuleMatcher 单趟遍历精准查找并（延迟）点击，点到一条即停。
 */
public class SkipAdService extends AccessibilityService {
    /** 同一窗口两次 CONTENT_CHANGED 处理的最小间隔（ms） */
    private static final long CONTENT_DEBOUNCE_MS = 200;

    /** 整包忽略的系统窗口；桌面/输入法/AOD 由 isIgnoredWindow 按命名特征兜住 */
    private static final Set<String> IGNORED_WINDOWS = new HashSet<>(Arrays.asList(
            "com.android.systemui",   // 状态栏/锁屏/通知面板
            "android"                 // 系统 toast 等窗口，节点不可点，搜了也白搜
    ));

    private RuleEngine engine;
    /** 各窗口包名最近一次进入匹配流程的时间戳；仅主线程读写（无障碍回调在主线程） */
    private final Map<String, Long> lastHandledAt = new HashMap<>();

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        engine = RuleEngine.getInstance(this);
        engine.load(); // 加载内置规则 + 本地合并库（零联网）
        DebugLog.d("Service", "无障碍服务已连接，规则已加载");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (engine == null) return;
        int type = event.getEventType();
        // 仅处理窗口状态/内容变化（开屏广告出现会触发这类事件）
        boolean isContent = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        if (!isContent && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        // 自身事件便宜过滤：设置页日志框变化会高频产生事件，先挡掉省一次取窗口的 IPC
        if (getPackageName().contentEquals(pkg)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 【关键】以实际要搜索的窗口包名为准。事件包名可能来自 systemui/launcher/documentsui，
        // 与活跃窗口完全是两回事——只有窗口包名才决定节点树里会有什么文本
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null) return;
        String windowPackage = rootPkg.toString();

        // 忽略自身窗口：设置页日志框的文本就是日志缓冲（含"跳过/关闭"关键词），而触发事件的
        // 包名多为 launcher/systemui/documentsui，仅按事件包名过滤拦不住（实测误点过日志框）
        if (getPackageName().equals(windowPackage)) return;
        // 桌面/状态栏/AOD/输入法窗口永远不宿主开屏广告
        if (isIgnoredWindow(windowPackage)) return;

        // 事件风暴限流：STATE_CHANGED（新窗口出现，最关键的跳过时机）立即处理并刷新时间戳，
        // 顺带压制随后的 CONTENT 风暴；CONTENT_CHANGED 同窗口 200ms 内只处理一次。
        // 按"窗口"而非"事件包名"去抖——不同系统包的事件会打到同一窗口，按事件包名各算各的
        // 曾导致同一秒内对同一弹幕开关连点 10 次
        long now = SystemClock.uptimeMillis();
        Long last = lastHandledAt.get(windowPackage);
        if (isContent && last != null && now - last < CONTENT_DEBOUNCE_MS) return;
        lastHandledAt.put(windowPackage, now);

        // 只取当前窗口包名 + 全局("*")的规则分片，不碰其他包
        List<InternalRule> rules = engine.getRulesForPackage(windowPackage);
        if (rules.isEmpty()) {
            return; // 无规则：静默返回，省掉日志与后续一切开销
        }
        DebugLog.d("Detect", "打开/检测到界面 包名=" + windowPackage
                + " 事件=" + (isContent ? "CONTENT" : "STATE") + " 候选规则数=" + rules.size());

        // 单趟遍历完成全部候选规则的匹配，内部命中即停
        RuleMatcher.matchAndClick(this, root, rules, windowPackage);
        // targetSdk 34 下节点由系统自动回收；低版本如需兼容可在此 root.recycle()
    }

    /** 永远不可能是开屏广告宿主的窗口 */
    private static boolean isIgnoredWindow(String pkg) {
        if (IGNORED_WINDOWS.contains(pkg)) return true;
        // 桌面：com.huawei.android.launcher / com.android.launcher3 / com.miui.home…
        // 输入法：*inputmethod*；息屏显示 AOD：*.aod
        return pkg.endsWith(".launcher") || pkg.contains("inputmethod") || pkg.endsWith(".aod");
    }

    @Override
    public void onInterrupt() {
        // 服务被系统中断时无需特殊处理
    }
}
