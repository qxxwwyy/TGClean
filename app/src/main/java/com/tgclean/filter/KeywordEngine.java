package com.tgclean.filter;

import android.content.SharedPreferences;

import com.tgclean.config.FilterConfig;
import com.tgclean.config.ReactionsRule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 关键词匹配引擎
 *
 * 匹配优先级：
 * 1. 白名单 → 放行（关键词与表情检查在内全部跳过）
 * 2. 规则集匹配 → 检查该规则集是否覆盖了当前频道，再匹配关键词
 * 3. 旧版分频道规则（兼容迁移期）
 * 4. 全局关键词兜底
 * 5. 每频道表情规则（与文本无关，纯媒体消息也参与）：
 *    白名单模式只显示达标消息，黑名单模式隐藏达标消息
 *
 * 支持：纯文本匹配 + 正则匹配 + 每规则集独立正则开关
 *
 * 线程模型：
 * - 规则以 RulesSnapshot（不可变对象）为单元整体发布，volatile 读。
 *   配置变更回调线程重建 snapshot 后一次交换，消息线程读到的永远是
 *   一份自洽的规则（不会出现"新规则集+旧频道映射"的撕裂状态）。
 * - Reactions 反射字段按 Class 缓存在 ConcurrentHashMap，避免每条消息
 *   重复反射查找。
 */
public class KeywordEngine {
    private final FilterConfig config;

    /** 不可变规则快照 — 所有字段 final，构造完成后不会修改 */
    private static final class RulesSnapshot {
        final List<FilterConfig.RuleSet> ruleSets;
        final Map<String, Set<Long>> ruleSetChannels;
        final Map<String, List<Pattern>> ruleSetPatterns;
        final Set<String> globalKeywords;
        final List<Pattern> globalPatterns;
        final Map<Long, Set<String>> channelKeywords;
        final Map<Long, List<Pattern>> channelPatterns;
        final Set<Long> whitelist;
        final Map<Long, ReactionsRule> reactionsRules;

        RulesSnapshot(List<FilterConfig.RuleSet> ruleSets,
                      Map<String, Set<Long>> ruleSetChannels,
                      Map<String, List<Pattern>> ruleSetPatterns,
                      Set<String> globalKeywords,
                      List<Pattern> globalPatterns,
                      Map<Long, Set<String>> channelKeywords,
                      Map<Long, List<Pattern>> channelPatterns,
                      Set<Long> whitelist,
                      Map<Long, ReactionsRule> reactionsRules) {
            this.ruleSets = ruleSets;
            this.ruleSetChannels = ruleSetChannels;
            this.ruleSetPatterns = ruleSetPatterns;
            this.globalKeywords = globalKeywords;
            this.globalPatterns = globalPatterns;
            this.channelKeywords = channelKeywords;
            this.channelPatterns = channelPatterns;
            this.whitelist = whitelist;
            this.reactionsRules = reactionsRules;
        }
    }

    private volatile RulesSnapshot snapshot;

    // 必须存为字段，否则 lambda 被 WeakReference 引用后会被 GC 回收
    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    public KeywordEngine(FilterConfig config) {
        this.config = config;
        this.snapshot = buildSnapshot();

        this.prefChangeListener = (prefs, key) -> reload();
        config.getPrefs().registerOnSharedPreferenceChangeListener(prefChangeListener);
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 重建并原子交换规则快照（由配置变更回调触发）
     */
    public void reload() {
        snapshot = buildSnapshot();
    }

    private RulesSnapshot buildSnapshot() {
        List<FilterConfig.RuleSet> ruleSets = config.getRuleSets();
        Map<String, Set<Long>> ruleSetChannels = config.getRuleSetChannels();

        // 预编译规则集正则
        Map<String, List<Pattern>> ruleSetPatterns = new HashMap<>();
        for (FilterConfig.RuleSet rs : ruleSets) {
            if (rs.useRegex) {
                List<Pattern> patterns = new ArrayList<>();
                for (String kw : rs.keywords) {
                    try {
                        patterns.add(Pattern.compile(kw));
                    } catch (Throwable t) {
                        // skip invalid
                    }
                }
                ruleSetPatterns.put(rs.id, patterns);
            }
        }

        return new RulesSnapshot(
                ruleSets,
                ruleSetChannels,
                ruleSetPatterns,
                config.getGlobalKeywords(),
                config.getGlobalPatterns(),
                config.getChannelKeywords(),
                config.getChannelPatterns(),
                config.getWhitelist(),
                config.getReactionsChannelRules());
    }

    // ═════════════════════════════════════════════
    // 核心匹配
    // ═════════════════════════════════════════════

    /**
     * 检查消息是否应被过滤（关键词 + 每频道表情规则）
     *
     * 白名单频道：关键词和表情规则全部跳过，直接放行。
     * 表情规则与文本无关（纯媒体消息也参与），关键词部分仅对有文本的消息生效。
     *
     * @param text     消息文本（可为 null/空）
     * @param dialogId 对话ID（负数=频道/群组，正数=私聊）
     * @param reactions TLRPC.Message.reactions（可为 null）
     * @return true=应过滤
     */
    public boolean shouldFilter(String text, long dialogId, Object reactions) {
        RulesSnapshot rules = snapshot;

        // 1. 白名单检查 — 最高优先级，含表情规则在内全部放行
        if (rules.whitelist.contains(dialogId)) return false;

        // 2~4. 关键词匹配（仅对有文本的消息）
        if (text != null && !text.isEmpty() && matchKeywords(text, dialogId, rules)) {
            return true;
        }

        // 5. 每频道表情规则
        ReactionsRule rule = rules.reactionsRules.get(dialogId);
        if (rule != null && rule.enabled) {
            return applyReactionsRule(reactions, rule);
        }

        return false;
    }

    /** 当前频道是否有启用中的表情规则（调试与评估共用） */
    public ReactionsRule getActiveRule(long dialogId) {
        ReactionsRule rule = snapshot.reactionsRules.get(dialogId);
        return rule != null && rule.enabled ? rule : null;
    }

    private boolean matchKeywords(String text, long dialogId, RulesSnapshot rules) {
        // 规则集匹配
        for (FilterConfig.RuleSet rs : rules.ruleSets) {
            if (!rs.enabled) continue;

            Set<Long> channels = rules.ruleSetChannels.get(rs.id);
            if (channels == null || !channels.contains(dialogId)) continue;

            if (matchRuleSetKeywords(text, rs, rules.ruleSetPatterns)) {
                return true;
            }
        }

        // 旧版分频道关键词（兼容迁移期）
        Set<String> channelKw = rules.channelKeywords.get(dialogId);
        if (channelKw != null) {
            for (String keyword : channelKw) {
                if (text.contains(keyword)) return true;
            }
        }

        List<Pattern> channelPat = rules.channelPatterns.get(dialogId);
        if (channelPat != null) {
            for (Pattern pattern : channelPat) {
                if (pattern.matcher(text).find()) return true;
            }
        }

        // 全局关键词兜底
        for (String keyword : rules.globalKeywords) {
            if (text.contains(keyword)) return true;
        }

        for (Pattern pattern : rules.globalPatterns) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    /** 兼容保留：仅关键词匹配 */
    public boolean shouldFilter(String text, long dialogId) {
        return shouldFilter(text, dialogId, null);
    }

    private boolean matchRuleSetKeywords(String text, FilterConfig.RuleSet rs,
                                         Map<String, List<Pattern>> patternCache) {
        if (rs.useRegex) {
            List<Pattern> patterns = patternCache.get(rs.id);
            if (patterns != null) {
                for (Pattern pattern : patterns) {
                    if (pattern.matcher(text).find()) return true;
                }
            }
        } else {
            for (String keyword : rs.keywords) {
                if (text.contains(keyword)) return true;
            }
        }
        return false;
    }

    // ═════════════════════════════════════════════
    // 每频道表情规则匹配
    // ═════════════════════════════════════════════

    /**
     * 反射字段缓存：类全名#字段名 → 解析出的 Field。
     * TLRPC 的 results/reaction/count/emoticon 声明在抽象父类
     * （MessageReactions / ReactionCount / Reaction）上，运行时拿到的是
     * TL_messageReactions / TL_reactionCount / TL_reactionEmoji 子类实例，
     * 所以必须沿继承链查找而不是 getDeclaredField。
     *
     * ⚠️ key 必须含字段名：早期版本只按 Class 缓存，同类的第二个字段
     * （如 reaction 之后的 count）会命中前一个字段的缓存，getInt() 作用在
     * 非原始类型字段上抛 IllegalArgumentException 被静默吞掉 → 计数恒 0
     * → 表情白名单全隐藏（v19 日志 RX-DEBUG 的 err 行即此症状）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Field> fieldCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Field cachedField(Class<?> clazz, String name) {
        return fieldCache.computeIfAbsent(clazz.getName() + "#" + name,
                k -> findFieldInHierarchy(clazz, name));
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 应用每频道表情规则。
     * 白名单模式：达标 → 显示（false），不达标 → 过滤（true）。
     * 黑名单模式：达标 → 过滤（true），不达标 → 显示（false）。
     * 目标计数：emojiSet 非空 → 集合内各 emoji 计数之和；否则单 emoji。
     */
    private boolean applyReactionsRule(Object reactions, ReactionsRule rule) {
        int likeCount = rule.hasEmojiSet()
                ? countReactionsSum(reactions, rule.emojiSetList())
                : countReaction(reactions, rule.emoji);
        if (rule.whitelistMode) {
            if (likeCount < rule.minCount) return true;             // 正面不达标 → 隐藏
            if (rule.hasEmoji2()
                    && countReaction(reactions, rule.emoji2) > rule.maxCount) {
                return true;                                        // 负面超限 → 隐藏
            }
            return false;
        } else {
            return likeCount >= rule.minCount;                      // 达标 → 隐藏
        }
    }

    /** 统计指定标准 emoji 的 reaction 数量（reactions 为 null 时返回 0） */
    private static int countReaction(Object reactions, String emoji) {
        if (reactions == null || emoji == null || emoji.isEmpty()) return 0;
        String normTarget = stripFe0f(emoji);
        try {
            Field resultsField = cachedField(reactions.getClass(), "results");
            if (resultsField == null) return 0;
            Object results = resultsField.get(reactions);
            if (!(results instanceof List)) return 0;

            for (Object reactionCount : (List<?>) results) {
                if (reactionCount == null) continue;
                Class<?> rcClass = reactionCount.getClass();

                Field reactionField = cachedField(rcClass, "reaction");
                if (reactionField == null) continue;
                Object reaction = reactionField.get(reactionCount);

                // 不按类名过滤：只要 reaction 对象沿继承链能找到 emoticon 字符串字段
                // 即视为标准 emoji reaction（TL_reactionCustomEmoji 只有 document_id，
                // 层级查找不到 emoticon，自然跳过）
                if (reaction == null) continue;
                Field emoticonField = cachedField(reaction.getClass(), "emoticon");
                if (emoticonField == null) continue;
                Object emoticonObj = emoticonField.get(reaction);
                if (!(emoticonObj instanceof String)) continue;
                String emoticon = (String) emoticonObj;

                // 归一化比较：忽略 U+FE0F 变体选择符（TG 默认爱心是 "❤"，
                // 快速选择按钮写入的是 "❤️"，其余多数 emoji 无此差异）
                if (!emoticon.equals(emoji) && !stripFe0f(emoticon).equals(normTarget)) {
                    continue;
                }

                Field countField = cachedField(rcClass, "count");
                if (countField == null) continue;
                return countField.getInt(reactionCount);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 去掉 U+FE0F（VARIATION SELECTOR-16），用于表情字符串归一化比较 */
    static String stripFe0f(String s) {
        return s == null ? "" : s.replace("\uFE0F", "");
    }

    /**
     * 多表情求和：统计一组标准 emoji 的 reaction 计数之和。
     * 单趟遍历 results，命中集合任一 emoji（FE0F 归一化后比较）即累加，
     * 未命中的条目不计（与单表情版 countReaction 同一套反射与容错）。
     */
    private static int countReactionsSum(Object reactions, java.util.List<String> emojis) {
        if (reactions == null || emojis == null || emojis.isEmpty()) return 0;
        java.util.HashSet<String> targets = new java.util.HashSet<>(emojis.size() * 4);
        for (String e : emojis) {
            if (e == null || e.isEmpty()) continue;
            targets.add(e);            // 原串（含 FE0F 形态）
            targets.add(stripFe0f(e)); // 归一化形态
        }
        if (targets.isEmpty()) return 0;
        int sum = 0;
        try {
            Field resultsField = cachedField(reactions.getClass(), "results");
            if (resultsField == null) return 0;
            Object results = resultsField.get(reactions);
            if (!(results instanceof List)) return 0;

            for (Object reactionCount : (List<?>) results) {
                if (reactionCount == null) continue;
                Class<?> rcClass = reactionCount.getClass();

                Field reactionField = cachedField(rcClass, "reaction");
                if (reactionField == null) continue;
                Object reaction = reactionField.get(reactionCount);
                if (reaction == null) continue;

                // 同 countReaction：沿继承链找 emoticon 字符串字段，
                // 自定义表情（只有 document_id）自然跳过
                Field emoticonField = cachedField(reaction.getClass(), "emoticon");
                if (emoticonField == null) continue;
                Object emoticonObj = emoticonField.get(reaction);
                if (!(emoticonObj instanceof String)) continue;
                String emoticon = (String) emoticonObj;

                if (!targets.contains(emoticon)
                        && !targets.contains(stripFe0f(emoticon))) {
                    continue;
                }

                Field countField = cachedField(rcClass, "count");
                if (countField == null) continue;
                sum += countField.getInt(reactionCount);
            }
        } catch (Throwable ignored) {
        }
        return sum;
    }

    // ═════════════════════════════════════════════
    // 调试：reactions 结构转储（RX-DEBUG 日志用）
    // ═════════════════════════════════════════════

    /**
     * 把 TLRPC.Message.reactions 转成可读字符串，用于定位匹配失败原因。
     * 例：TL_messageReactions min=true results=[TL_reactionEmoji(U+2764「❤」):120, TL_reactionCustomEmoji(no-emoticon):45]
     */
    public static String debugReactions(Object reactions) {
        if (reactions == null) return "reactions=null";
        StringBuilder sb = new StringBuilder(reactions.getClass().getSimpleName());
        try {
            Field minField = cachedField(reactions.getClass(), "min");
            if (minField != null) {
                sb.append(" min=").append(minField.getBoolean(reactions));
            }
        } catch (Throwable ignored) {}
        try {
            Field resultsField = cachedField(reactions.getClass(), "results");
            Object results = resultsField != null ? resultsField.get(reactions) : null;
            if (!(results instanceof List)) {
                sb.append(" results=NOT_LIST(")
                        .append(results == null ? "null" : results.getClass().getName()).append(')');
                return sb.toString();
            }
            List<?> list = (List<?>) results;
            sb.append(" count=").append(list.size()).append(" results=[");
            for (int i = 0; i < list.size() && i < 6; i++) {
                Object rc = list.get(i);
                if (i > 0) sb.append(", ");
                sb.append(debugReactionCount(rc));
            }
            if (list.size() > 6) sb.append(" …+").append(list.size() - 6);
            sb.append(']');
        } catch (Throwable t) {
            sb.append(" dump-error:").append(t);
        }
        return sb.toString();
    }

    private static String debugReactionCount(Object rc) {
        if (rc == null) return "null";
        StringBuilder sb = new StringBuilder();
        try {
            Object reaction = null;
            int count = -1;
            Field rf = cachedField(rc.getClass(), "reaction");
            if (rf != null) reaction = rf.get(rc);
            Field cf = cachedField(rc.getClass(), "count");
            if (cf != null) count = cf.getInt(rc);

            if (reaction == null) {
                sb.append("reaction=null");
            } else {
                String cls = reaction.getClass().getSimpleName();
                Field ef = findFieldInHierarchy(reaction.getClass(), "emoticon");
                if (ef != null) {
                    Object v = ef.get(reaction);
                    sb.append(cls).append('(').append(codepoints(v)).append(')');
                } else {
                    // 无 emoticon 字段 = 自定义表情/付费星星 reaction
                    sb.append(cls).append("(no-emoticon)");
                }
            }
            sb.append(':').append(count);
        } catch (Throwable t) {
            sb.append("err:").append(t);
        }
        return sb.toString();
    }

    /** 字符串转 Unicode 码点表示，暴露不可见的变体选择符/ZWJ 差异 */
    static String codepoints(Object v) {
        if (!(v instanceof String)) return String.valueOf(v);
        String s = (String) v;
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
