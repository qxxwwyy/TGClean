package com.tgclean.config;

/**
 * 每频道表情过滤规则（存储于 remote prefs `reactions_channel_rules`）
 *
 * JSON 形态：
 *   {"<dialogId>": {"enabled":true,"whitelist":true,
 *                   "emoji":"❤️","minCount":10,
 *                   "emoji2":"👎","maxCount":20,
 *                   "maxDepth":30000,
 *                   "emojiSet":"❤️ 👍"}}
 *
 * 语义：
 * - 白名单模式（whitelist=true）：只显示达标消息，其余隐藏。
 *     达标 = 计数(目标) ≥ minCount 且（若配置 emoji2）count(emoji2) ≤ maxCount
 *     目标计数：emojiSet 非空时 = 集合内各 emoji 计数之和（合计达标），
 *     否则 = count(emoji)（单表情）。
 *     用途：资源频道快速定位高价值内容（如 ❤️≥10 或 ❤️+👍 合计≥10）。
 * - 黑名单模式（whitelist=false）：达标即隐藏，其余显示。
 *     达标 = 计数(目标) ≥ minCount（此时目标通常配为踩的表达情）
 * - 检索深度（maxDepth，条）：筛选结果太少时级联自动向前翻找的消息数上限。
 *     0 = 跟随全局默认（remote prefs `reactions_search_depth`，
 *     由 TGClean App 设置页配置，初始 DEFAULT_MAX_DEPTH）。
 *
 * 表情匹配基于 TL_reactionEmoji.emoticon 的字符串精确相等，
 * 仅支持标准 emoji（自定义表情 reaction 是 document_id，无法按字符匹配）。
 */
public class ReactionsRule {
    /** 检索深度预设（条）：弹窗/App 快速选择用 */
    public static final int[] DEPTH_PRESETS = {500, 1000, 2000, 5000, 10000};
    /** 全局默认检索深度（条）：未单独设置的频道与关键词过滤路径共用。
     *  v2.0.2 从 15000 下调：级联迭代修复后 500 条已能覆盖多数频道，
     *  深度上限直接决定网络流量与消息缓存占用，默认值取保守档 */
    public static final int DEFAULT_MAX_DEPTH = 500;
    /** 自定义深度输入的合法区间（条） */
    public static final int MIN_DEPTH = 100;
    public static final int MAX_DEPTH = 100000;

    /** 自定义深度钳制：越界值收到边界，非法（≤0）返回 0（= 跟随全局默认） */
    public static int clampDepth(int v) {
        if (v <= 0) return 0;
        return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, v));
    }

    public boolean enabled;
    public boolean whitelistMode;
    public String emoji = "";
    public int minCount;
    public String emoji2 = "";
    public int maxCount;
    /** 本频道检索深度（条）；0 = 跟随全局默认 */
    public int maxDepth;
    /**
     * 目标表情集合（空格分隔多枚 emoji，如 "❤️ 👍 🔥"）：非空时达标计数
     * = 集合内各 emoji 的 reaction 数之和（合计 ≥ minCount 即达标），
     * 单 emoji 字段被忽略。空 = 未启用多表情，回落单 emoji 语义。
     * 旧版 App 不认识该字段，整表重写时会剥离——引擎侧以"空则回落单 emoji"
     * 兜底，两代版本互存无损。
     */
    public String emojiSet = "";

    /** emojiSet 允许的最大表情数（弹窗快速选择+手输的合法性上界） */
    public static final int MAX_EMOJI_SET = 6;
    /** 单个 emoji 字符串允许的最大码点数（sanitize 与双端 token 解析共用） */
    public static final int MAX_EMOJI_CODEPOINTS = 16;

    public boolean hasEmoji2() {
        return emoji2 != null && !emoji2.isEmpty();
    }

    /** emojiSet 是否有效非空 */
    public boolean hasEmojiSet() {
        return emojiSet != null && !emojiSet.trim().isEmpty();
    }

    // 热路径缓存：引擎对每条消息调用 emojiSetList()，规则对象在快照重建时
    // 整体替换（引擎侧永不失效）；写路径改动 emojiSet 后经 sanitize() 失效
    private transient java.util.List<String> emojiSetCache;

    /** 把 emojiSet 拆成表情列表（去空白），无效/空集返回空列表（结果勿改动） */
    public java.util.List<String> emojiSetList() {
        if (!hasEmojiSet()) return java.util.Collections.emptyList();
        java.util.List<String> c = emojiSetCache;
        if (c == null) {
            c = new java.util.ArrayList<>();
            for (String t : emojiSet.trim().split("\\s+")) {
                if (!t.isEmpty()) c.add(t);
            }
            emojiSetCache = c;
        }
        return c;
    }

    /** 达标目标的展示形态：多表情 "❤️+👍"，单表情 "❤️"；均无效返回空串 */
    public String describeTarget() {
        if (hasEmojiSet()) return String.join("+", emojiSetList());
        return emoji == null ? "" : emoji;
    }

    /** 规则是否配置了有效目标（enabled 判"关"用） */
    public boolean hasTarget() {
        return hasEmojiSet() || (emoji != null && !emoji.isEmpty());
    }

    /**
     * 写通道入口消毒（发布前审计 M-1 附带）：intent extras 可能来自任意
     * App，钳制异常值——emoji 超长置空、负数计数/深度归零（深度 0 = 跟随默认）。
     * emojiSet 逐项消毒：去超长项、去重（保序）、超上限截断。
     */
    public void sanitize() {
        if (emoji == null) emoji = "";
        if (emoji2 == null) emoji2 = "";
        if (emojiSet == null) emojiSet = "";
        if (emoji.codePointCount(0, emoji.length()) > MAX_EMOJI_CODEPOINTS) emoji = "";
        if (emoji2.codePointCount(0, emoji2.length()) > MAX_EMOJI_CODEPOINTS) emoji2 = "";
        if (hasEmojiSet()) {
            java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
            for (String t : emojiSetList()) {
                if (t.codePointCount(0, t.length()) > MAX_EMOJI_CODEPOINTS) continue; // 超长项直接丢弃
                if (t.replace("\uFE0F", "").isEmpty()) continue; // 纯变体符永不匹配
                uniq.add(t);
                if (uniq.size() >= MAX_EMOJI_SET) break;
            }
            emojiSet = uniq.isEmpty() ? "" : String.join(" ", uniq);
        } else {
            emojiSet = "";
        }
        emojiSetCache = null; // emojiSet 可能已被改写，失效缓存
        if (minCount < 0) minCount = 0;
        if (maxCount < 0) maxCount = 0;
        maxDepth = clampDepth(maxDepth);
    }

    // ═════════════════════════════════════════════
    // 跨进程契约（单一来源）：JSON 键与 Intent extras 键只在此处定义。
    // 读端容忍缺失键（optX 默认值），写端总是全量输出——旧版读到新键忽略、
    // 新版读到旧数据回落默认，双向兼容。
    // ═════════════════════════════════════════════

    public org.json.JSONObject toJSONObject() throws org.json.JSONException {
        org.json.JSONObject r = new org.json.JSONObject();
        r.put("enabled", enabled);
        r.put("whitelist", whitelistMode);
        r.put("emoji", emoji != null ? emoji : "");
        r.put("minCount", minCount);
        r.put("emoji2", emoji2 != null ? emoji2 : "");
        r.put("maxCount", maxCount);
        r.put("maxDepth", maxDepth);
        r.put("emojiSet", emojiSet != null ? emojiSet : "");
        return r;
    }

    public static ReactionsRule fromJSONObject(org.json.JSONObject r) {
        ReactionsRule rule = new ReactionsRule();
        rule.enabled = r.optBoolean("enabled", false);
        rule.whitelistMode = r.optBoolean("whitelist", true);
        rule.emoji = r.optString("emoji", "");
        rule.minCount = r.optInt("minCount", 0);
        rule.emoji2 = r.optString("emoji2", "");
        rule.maxCount = r.optInt("maxCount", 0);
        rule.maxDepth = r.optInt("maxDepth", 0);
        rule.emojiSet = r.optString("emojiSet", ""); // 新版字段，旧数据缺失回落单 emoji
        return rule;
    }

    /** 规则字段写入 intent（不含 dialog_id/token/nonce 等传输控制字段） */
    public android.content.Intent toIntent(android.content.Intent intent) {
        intent.putExtra("enabled", enabled);
        intent.putExtra("whitelist", whitelistMode);
        intent.putExtra("emoji", emoji != null ? emoji : "");
        intent.putExtra("emoji_set", emojiSet != null ? emojiSet : "");
        intent.putExtra("min_count", minCount);
        intent.putExtra("emoji2", emoji2 != null ? emoji2 : "");
        intent.putExtra("max_count", maxCount);
        intent.putExtra("max_depth", maxDepth); // 0 = 跟随全局默认
        return intent;
    }

    public static ReactionsRule fromIntent(android.content.Intent i) {
        ReactionsRule rule = new ReactionsRule();
        rule.enabled = i.getBooleanExtra("enabled", false);
        rule.whitelistMode = i.getBooleanExtra("whitelist", true);
        rule.emoji = i.getStringExtra("emoji");
        rule.emojiSet = i.getStringExtra("emoji_set");
        rule.minCount = i.getIntExtra("min_count", 0);
        rule.emoji2 = i.getStringExtra("emoji2");
        rule.maxCount = i.getIntExtra("max_count", 0);
        rule.maxDepth = i.getIntExtra("max_depth", 0);
        if (rule.emoji == null) rule.emoji = "";
        if (rule.emoji2 == null) rule.emoji2 = "";
        if (rule.emojiSet == null) rule.emojiSet = "";
        return rule;
    }

    /** 菜单/徽标用的简短描述，如 "❤️+👍≥10·👎≤20·白" */
    public String describe() {
        if (!enabled || !hasTarget()) return "关";
        return formatRule(whitelistMode, describeTarget(), minCount, emoji2, maxCount);
    }

    /** 规则格式化（单一来源）：目标标签形如 "❤️+👍"（多表情合计）或 "❤️" */
    public static String formatRule(boolean whitelist, String targetLabel, int minCount,
                                    String emoji2, int maxCount) {
        StringBuilder sb = new StringBuilder(targetLabel).append("≥").append(minCount);
        if (whitelist && emoji2 != null && !emoji2.isEmpty()) {
            sb.append("·").append(emoji2).append("≤").append(maxCount);
        }
        sb.append(whitelist ? "·只显" : "·隐藏");
        return sb.toString();
    }

    /** 调试日志用：带 Unicode 码点的完整描述，暴露变体选择符等不可见差异 */
    public String describeWithCodepoints() {
        StringBuilder sb = new StringBuilder("mode=");
        sb.append(whitelistMode ? "whitelist" : "blacklist");
        sb.append(" emoji=").append(codepoints(emoji)).append(" min=").append(minCount);
        if (hasEmojiSet()) {
            sb.append(" emojiSet=");
            for (String t : emojiSetList()) sb.append(codepoints(t)).append(' ');
            sb.deleteCharAt(sb.length() - 1);
        }
        if (hasEmoji2()) {
            sb.append(" emoji2=").append(codepoints(emoji2)).append(" max=").append(maxCount);
        }
        sb.append(" depth=").append(maxDepth > 0 ? formatDepth(maxDepth) : "default");
        return sb.toString();
    }

    /** 深度显示：5000 → "5000"，15000 → "1.5万"，50000 → "5万" */
    public static String formatDepth(int v) {
        if (v >= 10000) {
            float w = v / 10000f;
            String s = (w == Math.floor(w)) ? String.valueOf((int) w)
                    : String.valueOf(Math.round(w * 10) / 10f);
            return s + "万";
        }
        return String.valueOf(v);
    }

    private static String codepoints(String s) {
        if (s == null) return "null";
        if (s.isEmpty()) return "EMPTY";
        StringBuilder cp = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (cp.length() > 0) cp.append(' ');
            cp.append(String.format("U+%X", c));
            i += Character.charCount(c);
        }
        return cp + "「" + s + "」";
    }
}
