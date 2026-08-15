package com.tgclean.config;

/**
 * 每频道表情过滤规则（存储于 remote prefs `reactions_channel_rules`）
 *
 * JSON 形态：
 *   {"<dialogId>": {"enabled":true,"whitelist":true,
 *                   "emoji":"❤️","minCount":10,
 *                   "emoji2":"👎","maxCount":20,
 *                   "maxDepth":30000}}
 *
 * 语义：
 * - 白名单模式（whitelist=true）：只显示达标消息，其余隐藏。
 *     达标 = count(emoji) ≥ minCount 且（若配置 emoji2）count(emoji2) ≤ maxCount
 *     用途：资源频道快速定位高价值内容（如 ❤️≥10 的资源）。
 * - 黑名单模式（whitelist=false）：达标即隐藏，其余显示。
 *     达标 = count(emoji) ≥ minCount（此时 emoji 通常配为踩的表达情）
 * - 检索深度（maxDepth，条）：筛选结果太少时级联自动向前翻找的消息数上限。
 *     0 = 跟随全局默认（remote prefs `reactions_search_depth`，
 *     由 TGClean App 设置页配置，初始 DEFAULT_MAX_DEPTH）。
 *
 * 表情匹配基于 TL_reactionEmoji.emoticon 的字符串精确相等，
 * 仅支持标准 emoji（自定义表情 reaction 是 document_id，无法按字符匹配）。
 */
public class ReactionsRule {
    /** 检索深度预设（条）：弹窗/App 快速选择用 */
    public static final int[] DEPTH_PRESETS = {5000, 10000, 15000, 30000, 50000};
    /** 全局默认检索深度（条）：未单独设置的频道与关键词过滤路径共用 */
    public static final int DEFAULT_MAX_DEPTH = 15000;

    public boolean enabled;
    public boolean whitelistMode;
    public String emoji = "";
    public int minCount;
    public String emoji2 = "";
    public int maxCount;
    /** 本频道检索深度（条）；0 = 跟随全局默认 */
    public int maxDepth;

    public boolean hasEmoji2() {
        return emoji2 != null && !emoji2.isEmpty();
    }

    /**
     * 写通道入口消毒（发布前审计 M-1 附带）：intent extras 可能来自任意
     * App，钳制异常值——emoji 超长置空、负数计数/深度归零（深度 0 = 跟随默认）。
     */
    public void sanitize() {
        if (emoji == null) emoji = "";
        if (emoji2 == null) emoji2 = "";
        if (emoji.codePointCount(0, emoji.length()) > 16) emoji = "";
        if (emoji2.codePointCount(0, emoji2.length()) > 16) emoji2 = "";
        if (minCount < 0) minCount = 0;
        if (maxCount < 0) maxCount = 0;
        if (maxDepth < 0) maxDepth = 0;
    }

    /** 菜单/徽标用的简短描述，如 "❤️≥10·👎≤20·白" */
    public String describe() {
        if (!enabled || emoji == null || emoji.isEmpty()) return "关";
        StringBuilder sb = new StringBuilder(emoji).append("≥").append(minCount);
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
