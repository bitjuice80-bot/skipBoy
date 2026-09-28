package com.skipad.rule;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.skipad.engine.SelectorEngine;
import com.skipad.model.InternalRule;
import com.skipad.util.Json5Util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把异构订阅源归一化为 InternalRule：
 *  - 李跳跳规则：顶层是数组，元素 key 是【包名 hashCode】，value 是转义 JSON 字符串。
 *    需用 PackageManager 把已装包名算 hashCode 反查回真实包名。
 *  - GKD 订阅：JSON5，含 globalGroups / apps[].groups[].rules[].matches（高级选择器）。
 * 归一化阶段会把"简单选择器" [text*="x"] 识别为快速 TEXT_CONTAINS 类型，
 * 从而与李跳跳的同义规则指纹一致，便于 Merger 跨源去重。
 */
public class RuleNormalizer {

    /** 李跳跳规则：把包名 hashCode 反查回真实包名 */
    public static Map<Long, String> buildHashToPackage(Context ctx) {
        Map<Long, String> map = new HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            map.put((long) ai.packageName.hashCode(), ai.packageName);
        }
        return map;
    }

    public static List<InternalRule> fromLiTiaotiao(String json, Map<Long, String> hashToPkg) {
        List<InternalRule> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String hashKey = obj.keys().next();
                long hash = Long.parseLong(hashKey);
                String pkg = hashToPkg.get(hash);
                if (pkg == null) continue; // 设备上未安装该应用，跳过（离线场景常见）
                JSONObject inner = new JSONObject(obj.getString(hashKey));
                JSONArray rules = inner.optJSONArray("popup_rules");
                if (rules == null) continue;
                for (int j = 0; j < rules.length(); j++) {
                    JSONObject r = rules.getJSONObject(j);
                    String id = clean(r.optString("id"));
                    if (id.isEmpty()) continue;
                    InternalRule ir = new InternalRule(pkg, InternalRule.TYPE_TEXT_CONTAINS, id,
                            InternalRule.ACTION_CLICK, "litiaotiao");
                    ir.fingerprint = fingerprint(ir);
                    out.add(ir);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return out;
    }

    public static List<InternalRule> fromGkd(String json5) {
        List<InternalRule> out = new ArrayList<>();
        try {
            String json = Json5Util.toStandardJson(json5);
            JSONObject root = new JSONObject(json);
            JSONArray globals = root.optJSONArray("globalGroups");
            if (globals != null) {
                for (int i = 0; i < globals.length(); i++) {
                    addRules(out, "*", globals.getJSONObject(i));
                }
            }
            JSONArray apps = root.optJSONArray("apps");
            if (apps != null) {
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.getJSONObject(i);
                    String pkg = app.optString("id");
                    JSONArray groups = app.optJSONArray("groups");
                    if (groups == null) continue;
                    for (int k = 0; k < groups.length(); k++) {
                        addRules(out, pkg, groups.getJSONObject(k));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return out;
    }

    private static void addRules(List<InternalRule> out, String pkg, JSONObject group) throws JSONException {
        JSONArray rules = group.optJSONArray("rules");
        if (rules == null) return;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            Object m = r.opt("matches");
            String action = mapAction(r.optString("action", "click"));
            if (m instanceof String) addOne(out, pkg, (String) m, action);
            else if (m instanceof JSONArray) {
                JSONArray arr = (JSONArray) m;
                for (int x = 0; x < arr.length(); x++) addOne(out, pkg, arr.optString(x), action);
            }
        }
    }

    private static void addOne(List<InternalRule> out, String pkg, String selector, String action) {
        String[] simple = trySimple(selector);
        InternalRule ir;
        if (simple != null) {
            ir = new InternalRule(pkg, simple[0], simple[1], action, "gkd");
        } else {
            ir = new InternalRule(pkg, InternalRule.TYPE_SELECTOR, selector, action, "gkd");
        }
        ir.fingerprint = fingerprint(ir);
        out.add(ir);
    }

    /** 解析简单选择器，返回 [TYPE, value]，否则 null（保留为 SELECTOR） */
    private static String[] trySimple(String selector) {
        List<SelectorEngine.Condition> cs = SelectorEngine.parse(selector);
        if (cs.size() != 1) return null;
        SelectorEngine.Condition c = cs.get(0);
        if (!("text".equals(c.attr) || "desc".equals(c.attr))) return null;
        String type;
        switch (c.op) {
            case "*": type = InternalRule.TYPE_TEXT_CONTAINS; break;
            case "^": type = InternalRule.TYPE_TEXT_STARTS; break;
            case "$": type = InternalRule.TYPE_TEXT_ENDS; break;
            case "=": type = InternalRule.TYPE_TEXT_EQUALS; break;
            default: return null;
        }
        return new String[]{ type, c.value };
    }

    private static String mapAction(String a) {
        if (a == null) return InternalRule.ACTION_CLICK;
        if (a.contains("back")) return InternalRule.ACTION_BACK;
        return InternalRule.ACTION_CLICK; // click / clickCenter 均视为点击
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("|", "").replace("=", "").trim();
    }

    /** 去重指纹：包名 | 归一化匹配 | 动作 */
    public static String fingerprint(InternalRule r) {
        String canonical;
        if (InternalRule.TYPE_SELECTOR.equals(r.matchType)) {
            canonical = "S:" + r.matchValue.replaceAll("\\s+", "").toLowerCase();
        } else {
            canonical = "T:" + r.matchValue.toLowerCase();
        }
        return r.packageName + "|" + canonical + "|" + r.action;
    }
}
