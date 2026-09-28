package com.skipad.merger;

import com.skipad.model.InternalRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整合去重：把多份订阅源归一化后的规则按指纹去重合并。
 * 同一 (包名|归一化匹配|动作) 只保留一条；后传入的源优先
 * （调用方按 李跳跳→GKD 顺序，GKD 同指纹覆盖，保留信息更丰富的版本）。
 * 去重直接减少运行时规则总条数，是"降低资源消耗"的关键一环。
 */
public class Merger {
    public static List<InternalRule> merge(List<List<InternalRule>> sources) {
        Map<String, InternalRule> dedup = new LinkedHashMap<>();
        for (List<InternalRule> src : sources) {
            if (src == null) continue;
            for (InternalRule r : src) {
                if (r == null || r.fingerprint == null) continue;
                dedup.put(r.fingerprint, r); // 同指纹后者覆盖前者
            }
        }
        return new ArrayList<>(dedup.values());
    }
}
