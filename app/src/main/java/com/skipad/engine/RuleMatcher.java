package com.skipad.engine;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import com.skipad.model.InternalRule;
import com.skipad.util.DebugLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行一批规则的匹配与点击。命中后延迟 300ms 再点，规避"跳过"按钮尚在加载的情况，
 * 避免误触正在出现的真实内容。
 *
 * 性能核心：一次 DFS 同时匹配全部候选规则。此前每条规则各遍历一次整棵节点树，
 * 12 条全局规则 × 数千节点的重复遍历与重复 IPC 在低资源设备上开销显著；
 * 现在每个节点只取一次 text / contentDescription，供所有文字规则复用。
 *
 * 关键修复：文字节点本身往往不可点击，真正可点的是它的某个祖先（容器/卡片/整屏）。
 * 命中后向上回溯最多 6 层找第一个 isClickable 的祖先并点击，大幅提升实际跳过成功率。
 */
public class RuleMatcher {

    private static final long CLICK_DELAY_MS = 300;
    /** 点击调度后的冷却期：期间的新命中不再重复调度，防止多事件源连点同一控件 */
    private static final long CLICK_GUARD_MS = 150;
    /** 单次匹配的节点数安全上限，避免极端界面拖垮性能 */
    private static final int NODE_LIMIT = 4000;
    /** 点击日志里节点文本的最大长度：日志框全文被误命中时曾把上百行日志嵌进一行日志 */
    private static final int LOG_TEXT_MAX = 50;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    /** 点击冷却截止时间；仅主线程读写（事件回调与延迟点击都在主线程） */
    private static long pendingClickUntil;

    /**
     * 单趟遍历匹配 rules 中的所有规则，按传入顺序取第一条有命中的规则执行（延迟）点击。
     * rules 由 RuleEngine 按包名分片提供，调用方只读。
     */
    public static boolean matchAndClick(AccessibilityService svc, AccessibilityNodeInfo root,
                                        List<InternalRule> rules, String pkg) {
        if (root == null || rules == null || rules.isEmpty()) return false;

        int n = rules.size();
        // 选择器条件只在遍历前解析一次；文字规则存在时才取节点文本
        List<List<SelectorEngine.Condition>> selectorConds = new ArrayList<>(n);
        boolean hasTextRule = false;
        for (InternalRule r : rules) {
            if (InternalRule.TYPE_SELECTOR.equals(r.matchType)) {
                selectorConds.add(SelectorEngine.parse(r.matchValue));
            } else {
                selectorConds.add(null);
                hasTextRule = true;
            }
        }

        List<List<AccessibilityNodeInfo>> hits = new ArrayList<>(n);
        for (int i = 0; i < n; i++) hits.add(null);
        traverse(root, rules, selectorConds, hasTextRule, hits, new int[]{0});

        for (int i = 0; i < n; i++) {
            List<AccessibilityNodeInfo> hit = hits.get(i);
            if (hit == null || hit.isEmpty()) continue;
            InternalRule rule = rules.get(i);
            DebugLog.d("Match", "命中候选 包名=" + pkg + " 类型=" + rule.matchType
                    + " 值=\"" + rule.matchValue + "\" 命中节点数=" + hit.size());
            AccessibilityNodeInfo target = pickClickable(hit);
            if (target == null) continue;
            long now = SystemClock.uptimeMillis();
            if (now < pendingClickUntil) {
                DebugLog.d("Match", "  命中但在点击冷却期内，跳过重复调度 包名=" + pkg);
                return true;
            }
            pendingClickUntil = now + CLICK_DELAY_MS + CLICK_GUARD_MS;
            MAIN_HANDLER.postDelayed(() -> perform(target, rule, svc), CLICK_DELAY_MS);
            return true;
        }
        return false;
    }

    private static void perform(AccessibilityNodeInfo node, InternalRule rule, AccessibilityService svc) {
        if (node == null) return;
        if (InternalRule.ACTION_BACK.equals(rule.action)) {
            if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            DebugLog.d("Click", "  执行返回键 包名=" + rule.packageName);
            return;
        }
        // 向上回溯最多 6 层，找真正可点击的祖先（广告热区常在容器/卡片上）
        AccessibilityNodeInfo clickable = findClickableAncestor(node);
        if (clickable != null) {
            boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            DebugLog.d("Click", "  真实点击 文本=\"" + safeText(node) + "\" 命中可点击祖先=" + ok);
        } else {
            DebugLog.d("Click", "  !未找到可点击节点，点击未生效 文本=\"" + safeText(node) + "\"");
        }
    }

    /** 从 node 自身起向上回溯最多 6 层，返回第一个 isClickable 的节点（含自身） */
    private static AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo pickClickable(List<AccessibilityNodeInfo> list) {
        for (AccessibilityNodeInfo n : list) {
            if (n.isVisibleToUser() && (n.isClickable() || n.getParent() != null)) return n;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /** 单趟 DFS：每个节点只取一次文本，依次测试全部候选规则 */
    private static void traverse(AccessibilityNodeInfo node, List<InternalRule> rules,
                                 List<List<SelectorEngine.Condition>> selectorConds, boolean hasTextRule,
                                 List<List<AccessibilityNodeInfo>> hits, int[] counter) {
        if (node == null || counter[0] > NODE_LIMIT) return;
        counter[0]++;

        String text = null, desc = null;
        if (hasTextRule) {
            text = toStr(node.getText());
            desc = toStr(node.getContentDescription());
        }

        for (int i = 0; i < rules.size(); i++) {
            List<SelectorEngine.Condition> conds = selectorConds.get(i);
            boolean matched = (conds != null)
                    ? SelectorEngine.evaluate(node, conds)
                    : matchText(text, desc, rules.get(i));
            if (matched) {
                List<AccessibilityNodeInfo> hit = hits.get(i);
                if (hit == null) { hit = new ArrayList<>(2); hits.set(i, hit); }
                hit.add(node);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            traverse(node.getChild(i), rules, selectorConds, hasTextRule, hits, counter);
        }
    }

    /** CharSequence → String，已是 String 时零拷贝 */
    private static String toStr(CharSequence cs) {
        if (cs == null) return null;
        return cs instanceof String ? (String) cs : cs.toString();
    }

    /** text 与 desc 分别比对（不再拼接，省去每节点一次字符串分配） */
    private static boolean matchText(String text, String desc, InternalRule rule) {
        String v = rule.matchValue;
        if (v == null) return false;
        switch (rule.matchType) {
            case InternalRule.TYPE_TEXT_CONTAINS: return contains(text, v) || contains(desc, v);
            case InternalRule.TYPE_TEXT_EQUALS:   return v.equals(text) || v.equals(desc);
            case InternalRule.TYPE_TEXT_STARTS:   return startsWith(text, v) || startsWith(desc, v);
            case InternalRule.TYPE_TEXT_ENDS:     return endsWith(text, v) || endsWith(desc, v);
            default: return false;
        }
    }

    private static boolean contains(String s, String v)   { return s != null && s.contains(v); }
    private static boolean startsWith(String s, String v) { return s != null && s.startsWith(v); }
    private static boolean endsWith(String s, String v)   { return s != null && s.endsWith(v); }

    private static String safeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence t = node.getText();
        String s = t != null ? t.toString() : "";
        if (s.isEmpty()) {
            CharSequence d = node.getContentDescription();
            s = d != null ? d.toString() : "";
        }
        if (s.length() > LOG_TEXT_MAX) s = s.substring(0, LOG_TEXT_MAX) + "…";
        return s;
    }
}
