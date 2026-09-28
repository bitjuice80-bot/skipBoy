package com.skipad.rule;

import android.content.Context;
import android.util.Log;

import com.skipad.model.InternalRule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎：把内置规则 + 订阅合并库加载进内存，按包名分片，供 SkipAdService 高速查询。
 * 运行时零联网，只在 onServiceConnected / 订阅更新后 reload()。
 */
public class RuleEngine {
    private static final String MERGED_FILE = "merged_rules.json";
    private static RuleEngine instance;
    private final Context appCtx;
    private final Map<String, List<InternalRule>> byPackage = new HashMap<>();
    /** 包名 → (包规则+全局规则) 合并结果缓存；规则只在 load() 时变化，事件高频查询走零分配快路径 */
    private final Map<String, List<InternalRule>> combinedCache = new HashMap<>();
    private static final List<InternalRule> EMPTY_RULES = Collections.emptyList();

    private RuleEngine(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static synchronized RuleEngine getInstance(Context ctx) {
        if (instance == null) instance = new RuleEngine(ctx);
        return instance;
    }

    /** 加载内置规则 + 本地合并库（若有） */
    public void load() {
        byPackage.clear();
        combinedCache.clear();
        int builtinN = 0, mergedN = 0;
        // 1) 内置精简规则（res/raw/rules_builtin.json，统一内部格式）
        try {
            int resId = appCtx.getResources().getIdentifier("rules_builtin", "raw", appCtx.getPackageName());
            InputStream is = appCtx.getResources().openRawResource(resId);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                InternalRule r = new InternalRule(
                        o.optString("packageName", "*"),
                        o.optString("matchType", InternalRule.TYPE_TEXT_CONTAINS),
                        o.optString("matchValue"),
                        o.optString("action", InternalRule.ACTION_CLICK), "builtin");
                r.fingerprint = RuleNormalizer.fingerprint(r);
                add(r);
                builtinN++;
            }
        } catch (Exception e) { Log.e("RuleEngine", "load builtin failed", e); }

        // 2) 订阅合并库（Subscriber 生成的 merged_rules.json）
        try {
            String merged = readMerged();
            if (merged != null) {
                JSONArray arr = new JSONArray(merged);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    InternalRule r = new InternalRule(
                            o.getString("packageName"), o.getString("matchType"),
                            o.getString("matchValue"), o.getString("action"),
                            o.optString("source", "sub"));
                    r.fingerprint = RuleNormalizer.fingerprint(r);
                    add(r);
                    mergedN++;
                }
            }
        } catch (Exception e) { Log.e("RuleEngine", "load merged failed", e); }
        com.skipad.util.DebugLog.d("Engine", "规则加载完成 内置=" + builtinN
                + "条 订阅合并库=" + mergedN + "条 总计=" + (builtinN + mergedN) + "条");
    }

    private void add(InternalRule r) {
        byPackage.computeIfAbsent(r.packageName, k -> new ArrayList<>()).add(r);
    }

    /**
     * 取当前包名规则 + 全局("*")规则。返回的列表由调用方只读，勿修改。
     * 常见情形（无全局规则 / 包无专属规则）直接返回既有分片或空列表，零分配。
     */
    public List<InternalRule> getRulesForPackage(String pkg) {
        List<InternalRule> global = byPackage.get("*");
        if (global == null || global.isEmpty()) {
            List<InternalRule> specific = byPackage.get(pkg);
            return specific != null ? specific : EMPTY_RULES;
        }
        List<InternalRule> cached = combinedCache.get(pkg);
        if (cached != null) return cached;
        List<InternalRule> specific = byPackage.get(pkg);
        if (specific == null) {
            combinedCache.put(pkg, global);
            return global;
        }
        List<InternalRule> merged = new ArrayList<>(specific.size() + global.size());
        merged.addAll(specific);
        merged.addAll(global);
        combinedCache.put(pkg, merged);
        return merged;
    }

    /** 持久化合并后的规则（供 reload 读取） */
    public void saveMerged(List<InternalRule> all) {
        try {
            JSONArray arr = new JSONArray();
            for (InternalRule r : all) {
                JSONObject o = new JSONObject();
                o.put("packageName", r.packageName);
                o.put("matchType", r.matchType);
                o.put("matchValue", r.matchValue);
                o.put("action", r.action);
                o.put("source", r.source);
                arr.put(o);
            }
            try (FileOutputStream fos = appCtx.openFileOutput(MERGED_FILE, Context.MODE_PRIVATE)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { Log.e("RuleEngine", "save merged failed", e); }
    }

    private String readMerged() {
        try {
            File f = new File(appCtx.getFilesDir(), MERGED_FILE);
            if (!f.exists()) return null;
            try (FileInputStream fis = new FileInputStream(f)) {
                // 单次 read 不保证读满（大文件可能被截断成残缺 JSON），循环读全
                ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } catch (Exception e) { return null; }
    }

    public void reload() { load(); }
}
