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

    public boolean hasEmoji2() {
        return emoji2 != null && !emoji2.isEmpty();
    }

    /** emojiSet 是否有效非空 */
    public boolean hasEmojiSet() {
        return emojiSet != null && !emojiSet.trim().isEmpty();
    }

    /** 把 emojiSet 拆成表情列表（去空白），无效/空集返回空列表 */
    public java.util.List<String> emojiSetList() {
        if (!hasEmojiSet()) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : emojiSet.trim().split("\\s+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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
        if (emoji.codePointCount(0, emoji.length()) > 16) emoji = "";
        if (emoji2.codePointCount(0, emoji2.length()) > 16) emoji2 = "";
        if (hasEmojiSet()) {
            java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
            for (String t : emojiSetList()) {
                if (t.codePointCount(0, t.length()) > 16) continue; // 超长项直接丢弃
                uniq.add(t);
                if (uniq.size() >= MAX_EMOJI_SET) break;
            }
            emojiSet = uniq.isEmpty() ? "" : String.join(" ", uniq);
        } else {
            emojiSet = "";
        }
        if (minCount < 0) minCount = 0;
        if (maxCount < 0) maxCount = 0;
        maxDepth = clampDepth(maxDepth);
    }

    /** 菜单/徽标用的简短描述，如 "❤️+👍≥10·👎≤20·白" */
    public String describe() {
        if (!enabled || !hasTarget()) return "关";
        StringBuilder sb = new StringBuilder(describeTarget()).append("≥").append(minCount);
        if (whitelistMode && hasEmoji2()) {
            sb.append("·").append(emoji2).append("≤").append(maxCount);
        }
        sb.append(whitelistMode ? "·只显" : "·隐藏");
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
