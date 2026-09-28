package com.skipad.util;

/**
 * 把 GKD 的 JSON5（压缩格式）清洗为标准 JSON，便于 org.json 解析。
 * 处理：去块注释、单引号字符串→双引号、无引号 key 加引号、去尾随逗号。
 * 注意：不去除行注释 //，否则会误伤 https:// 类 URL（GKD 订阅含 snapshotUrls）。
 *
 * 适用范围：覆盖 GKD 压缩订阅常见写法。若遇到复杂 JSON5（多行字符串、特殊转义），
 * 建议替换为正式 JSON5 库（如 com.eclipsesource.minimal-json 之外的 json5 实现）。
 */
public class Json5Util {
    public static String toStandardJson(String raw) {
        if (raw == null) return "{}";
        String s = raw;
        // 1) 块注释 /* */
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        // 2) 不去除行注释 // ：GKD 压缩订阅本无行注释，且会误删 https:// 这类 URL
        // 3) 单引号字符串 → 双引号（假设内容不含双引号）
        s = s.replaceAll("'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'", "\"$1\"");
        // 4) 无引号 key → 加引号： { 或 , 后跟 字母/数字/下划线 再跟冒号
        s = s.replaceAll("([{,]\\s*)([A-Za-z_][\\w]*)\\s*:", "$1\"$2\":");
        // 5) 尾随逗号 ,} 或 ,]
        s = s.replaceAll(",(\\s*[}\\]])", "$1");
        return s;
    }
}
