package com.tgclean.config;

/**
 * 每频道表情过滤规则（存储于 remote prefs `reactions_channel_rules`）
 *
 * JSON 形态：
 *   {"<dialogId>": {"enabled":true,"whitelist":true,
 *                   "emoji":"❤️","minCount":10,
 *                   "emoji2":"👎","maxCount":20}}
 *
 * 语义：
 * - 白名单模式（whitelist=true）：只显示达标消息，其余隐藏。
 *     达标 = count(emoji) ≥ minCount 且（若配置 emoji2）count(emoji2) ≤ maxCount
 *     用途：资源频道快速定位高价值内容（如 ❤️≥10 的资源）。
 * - 黑名单模式（whitelist=false）：达标即隐藏，其余显示。
 *     达标 = count(emoji) ≥ minCount（此时 emoji 通常配为踩的表达情）
 *
 * 表情匹配基于 TL_reactionEmoji.emoticon 的字符串精确相等，
 * 仅支持标准 emoji（自定义表情 reaction 是 document_id，无法按字符匹配）。
 */
public class ReactionsRule {
    public boolean enabled;
    public boolean whitelistMode;
    public String emoji = "";
    public int minCount;
    public String emoji2 = "";
    public int maxCount;

    public boolean hasEmoji2() {
        return emoji2 != null && !emoji2.isEmpty();
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
}
