package com.skipad.subscriber;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.skipad.merger.Merger;
import com.skipad.model.InternalRule;
import com.skipad.rule.RuleEngine;
import com.skipad.rule.RuleNormalizer;
import com.skipad.util.DebugLog;
import com.skipad.util.Json5Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订阅管理：双源更新 + 版本/ETag 检测 + 去重合并写本地库。
 * 仅"更新时"联网；运行时零联网，完全吃本地 merged_rules.json。
 */
public class Subscriber {
    // 大陆网络下 raw.githubusercontent.com 普遍被墙/DNS污染会 timeout，
    // 因此李跳跳源走 jsDelivr 镜像优先、raw 兜底；GKD 主订阅走国内 npmmirror 直连。
    private static final String[] LITIAOTIAO_URLS = {
            "https://fastly.jsdelivr.net/gh/Snoopy1866/LiTiaotiao-Custom-Rules@main/AllRules.json",
            "https://cdn.jsdelivr.net/gh/Snoopy1866/LiTiaotiao-Custom-Rules@main/AllRules.json",
            "https://raw.githubusercontent.com/Snoopy1866/LiTiaotiao-Custom-Rules/main/AllRules.json"
    };
    // 版本探测纯优化用途，允许全部失败（不阻断主订阅拉取）
    private static final String[] GKD_VERSION_URLS = {
            "https://fastly.jsdelivr.net/gh/ganlinte/GKD-subscription@main/dist/ganlin_gkd.version.json5",
            "https://raw.githubusercontent.com/ganlinte/GKD-subscription/main/dist/ganlin_gkd.version.json5"
    };
    private static final String GKD_URL = "https://registry.npmmirror.com/@ganlinte/gkd-subscription/latest/files/dist/ganlin_gkd.json5";
    private static final String SP = "skipad_sub";
    private final Context ctx;
    private final RuleEngine engine;

    public Subscriber(Context ctx, RuleEngine engine) {
        this.ctx = ctx.getApplicationContext();
        this.engine = engine;
    }

    /** 后台更新两个源，合并去重后写本地库并 reload。
     *  部分成功即成功：某源失败不阻断另一源入库，仅当两源全失败才回调 onError。 */
    public void updateAsync(final Callback cb) {
        new Thread(() -> {
            try {
                List<InternalRule> li = null, gkd = null;
                StringBuilder errs = new StringBuilder();
                try {
                    li = fetchLiTiaotiao();
                } catch (Exception e) {
                    errs.append("李跳跳源失败: ").append(e.getMessage()).append("；");
                }
                try {
                    gkd = fetchGkd();
                } catch (Exception e) {
                    errs.append("GKD源失败: ").append(e.getMessage());
                }
                if (li == null && gkd == null) {
                    throw new Exception(errs.toString());
                }
                List<List<InternalRule>> sources = new ArrayList<>();
                sources.add(li);   // 先李跳跳（可为 null，Merger 跳过）
                sources.add(gkd);  // 后 GKD（同指纹优先保留 GKD）
                List<InternalRule> merged = Merger.merge(sources);
                engine.saveMerged(merged);
                engine.reload();
                // 【诊断关键】打印各源解析条数：若 GKD 解析为 0，说明 JSON5 清洗/解析有问题
                int liN = li == null ? -1 : li.size();
                int gkdN = gkd == null ? -1 : gkd.size();
                DebugLog.d("Sub", "订阅完成 李跳跳=" + liN + "条 GKD=" + gkdN
                        + "条 合并去重=" + merged.size() + "条");
                final String warn = errs.toString();
                if (cb != null) cb.onDone(merged.size(),
                        warn.isEmpty() ? null : warn + " [李=" + liN + " GKD=" + gkdN + "]");
            } catch (final Exception e) {
                if (cb != null) cb.onError(e);
            }
        }).start();
    }

    private List<InternalRule> fetchLiTiaotiao() throws Exception {
        String json = httpGetAny(LITIAOTIAO_URLS);
        String etag = getEtag(LITIAOTIAO_URLS[0]);
        // 仅记录 ETag 供参考；该源约2年未更新，低频，直接重新解析
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString("lt_etag", etag == null ? "" : etag).apply();
        Map<Long, String> map = RuleNormalizer.buildHashToPackage(ctx);
        return RuleNormalizer.fromLiTiaotiao(json, map);
    }

    private List<InternalRule> fetchGkd() throws Exception {
        // 版本号比对只是优化信息，【尽力而为】：探测失败（如 raw 被墙）绝不阻断主订阅拉取
        int ver = 0;
        try {
            String verRaw = httpGetAny(GKD_VERSION_URLS);
            JSONObject jo = new JSONObject(Json5Util.toStandardJson(verRaw));
            ver = jo.optInt("version", 0);
        } catch (Exception ignore) { }
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putInt("gkd_ver", ver).apply();
        // 主订阅走 registry.npmmirror.com（国内直连可达）
        String json5 = httpGet(GKD_URL);
        return RuleNormalizer.fromGkd(json5);
    }

    /** 导入本地规则文件（离线部署场景）：自动识别李跳跳 / GKD / 统一内部格式 */
    public void importLocalAsync(Uri uri, final Callback cb) {
        new Thread(() -> {
            try {
                InputStream is = ctx.getContentResolver().openInputStream(uri);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                String raw = sb.toString();
                List<InternalRule> rules;
                if (raw.contains("popup_rules")) {
                    rules = RuleNormalizer.fromLiTiaotiao(raw, RuleNormalizer.buildHashToPackage(ctx));
                } else if (raw.contains("globalGroups") || raw.contains("\"apps\"")) {
                    rules = RuleNormalizer.fromGkd(raw);
                } else {
                    rules = parseInternal(raw);
                }
                engine.saveMerged(rules);
                engine.reload();
                if (cb != null) cb.onDone(rules.size(), null);
            } catch (final Exception e) {
                if (cb != null) cb.onError(e);
            }
        }).start();
    }

    private List<InternalRule> parseInternal(String raw) throws Exception {
        List<InternalRule> out = new ArrayList<>();
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            InternalRule r = new InternalRule(o.optString("packageName", "*"),
                    o.optString("matchType", InternalRule.TYPE_TEXT_CONTAINS),
                    o.optString("matchValue"), o.optString("action", InternalRule.ACTION_CLICK), "local");
            r.fingerprint = RuleNormalizer.fingerprint(r);
            out.add(r);
        }
        return out;
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        // 连接 6s / 读取 20s：镜像探测要快速失败，避免多镜像串行等待过久
        conn.setConnectTimeout(6000); conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "SkipAd/1.0");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { conn.disconnect(); }
    }

    /** 依次尝试多个镜像地址，第一个成功即返回；全部失败抛出最后一个异常 */
    private static String httpGetAny(String[] urls) throws Exception {
        Exception last = null;
        for (String u : urls) {
            try { return httpGet(u); } catch (Exception e) { last = e; }
        }
        throw (last != null) ? last : new Exception("no mirror configured");
    }

    private static String getEtag(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            String etag = conn.getHeaderField("ETag");
            conn.disconnect();
            return etag;
        } catch (Exception e) { return null; }
    }

    public interface Callback {
        /** warn 非空表示部分源失败但整体入库成功（附带失败原因文本） */
        void onDone(int count, String warn);
        void onError(Exception e);
    }
}
