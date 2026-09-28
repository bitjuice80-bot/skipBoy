package com.skipad.model;

/**
 * 归一化后的统一规则模型。两种订阅源（李跳跳规则 / GKD 订阅）最终都转成它，
 * 运行时匹配器与去重合并都只认这个结构。
 */
public class InternalRule {
    public static final String TYPE_TEXT_CONTAINS = "TEXT_CONTAINS";
    public static final String TYPE_TEXT_EQUALS   = "TEXT_EQUALS";
    public static final String TYPE_TEXT_STARTS    = "TEXT_STARTS";
    public static final String TYPE_TEXT_ENDS      = "TEXT_ENDS";
    public static final String TYPE_SELECTOR       = "SELECTOR";   // GKD 高级选择器

    public static final String ACTION_CLICK = "click";
    public static final String ACTION_BACK  = "back";

    /** 目标包名；"*" 表示全局生效（对应 GKD globalGroups） */
    public String packageName;
    /** 匹配类型，见上常量 */
    public String matchType;
    /** 匹配值：文字或 GKD 选择器字符串 */
    public String matchValue;
    /** 动作：click / back */
    public String action;
    /** 来源标记，仅用于调试/统计 */
    public String source;
    /** 归一化去重指纹（包名|canonicalMatch|动作），由 RuleNormalizer 计算 */
    public String fingerprint;

    public InternalRule(String packageName, String matchType, String matchValue, String action, String source) {
        this.packageName = packageName;
        this.matchType = matchType;
        this.matchValue = matchValue;
        this.action = action;
        this.source = source;
    }
}
