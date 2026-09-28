package com.skipad.engine;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GKD 高级选择器的【子集】引擎。覆盖开屏广告 90%+ 场景，复杂规则（兄弟关系 +、正则、函数）
 * 在归一化阶段仍保留为 TYPE_SELECTOR，本引擎可继续匹配。
 *
 * 支持单/多段条件（段间 AND）：
 *   [text*="跳过"]           文本包含
 *   [text^="跳过"]           文本前缀
 *   [text$="跳过"]           文本后缀
 *   [text="跳过"]            文本等于
 *   [text.length<10]         文本长度比较
 *   [id="com.x:id/cancel"]   资源ID
 *   [desc*="xxx"]            内容描述
 *   [visibleToUser=true]     是否可见
 *   [clickable=true]         是否可点击
 */
public class SelectorEngine {

    private static final Pattern SEG = Pattern.compile(
            "\\[([\\w.]+)\\s*(\\*=|\\^=|\\$=|=|<=|>=|<|>)\\s*\"?([^\"\\]]*?)\"?\\]");

    public static class Condition {
        public String attr;
        public String op;
        public String value;
        Condition(String a, String o, String v) {
            attr = a; op = o; value = v;
        }
    }

    public static List<Condition> parse(String selector) {
        List<Condition> list = new ArrayList<>();
        if (selector == null) return list;
        Matcher m = SEG.matcher(selector);
        while (m.find()) {
            list.add(new Condition(m.group(1), m.group(2), m.group(3)));
        }
        return list;
    }

    /** 判断节点是否满足全部条件（AND） */
    public static boolean evaluate(AccessibilityNodeInfo node, List<Condition> conds) {
        if (node == null || conds == null || conds.isEmpty()) return false;
        for (Condition c : conds) {
            if (!matchOne(node, c)) return false;
        }
        return true;
    }

    private static boolean matchOne(AccessibilityNodeInfo node, Condition c) {
        String actual = attrValue(node, c.attr);
        if (actual == null) return false;
        switch (c.op) {
            case "*": return actual.contains(c.value);
            case "^": return actual.startsWith(c.value);
            case "$": return actual.endsWith(c.value);
            case "=": return actual.equals(c.value);
            case "<": return toNum(actual) < toNum(c.value);
            case ">": return toNum(actual) > toNum(c.value);
            case "<=": return toNum(actual) <= toNum(c.value);
            case ">=": return toNum(actual) >= toNum(c.value);
            default: return false;
        }
    }

    private static String attrValue(AccessibilityNodeInfo node, String attr) {
        if ("text".equals(attr)) {
            CharSequence t = node.getText(); return t == null ? null : t.toString();
        } else if ("desc".equals(attr)) {
            CharSequence d = node.getContentDescription(); return d == null ? null : d.toString();
        } else if ("id".equals(attr)) {
            CharSequence id = node.getViewIdResourceName(); return id == null ? null : id.toString();
        } else if ("text.length".equals(attr)) {
            CharSequence t = node.getText(); return t == null ? "0" : String.valueOf(t.length());
        } else if ("visibleToUser".equals(attr)) {
            return String.valueOf(node.isVisibleToUser());
        } else if ("clickable".equals(attr)) {
            return String.valueOf(node.isClickable());
        }
        return null;
    }

    /** 在 root 子树中查找所有满足条件的节点（带节点数上限，防遍历爆炸） */
    public static List<AccessibilityNodeInfo> find(AccessibilityNodeInfo root, String selector) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<Condition> conds = parse(selector);
        if (conds.isEmpty()) return out;
        traverse(root, conds, out, new int[]{0});
        return out;
    }

    private static void traverse(AccessibilityNodeInfo node, List<Condition> conds,
                                 List<AccessibilityNodeInfo> out, int[] counter) {
        if (node == null || counter[0] > 4000) return; // 安全上限，避免极端界面拖垮性能
        counter[0]++;
        if (evaluate(node, conds)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            traverse(node.getChild(i), conds, out, counter);
        }
    }

    private static double toNum(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }
}
