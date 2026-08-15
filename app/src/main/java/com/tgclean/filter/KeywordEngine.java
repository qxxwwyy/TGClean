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
     * 反射字段缓存：host 端类 → 解析出的 Field。
     * TLRPC 的 results/reaction/count/emoticon 声明在抽象父类
     * （MessageReactions / ReactionCount / Reaction）上，运行时拿到的是
     * TL_messageReactions / TL_reactionCount / TL_reactionEmoji 子类实例，
     * 所以必须沿继承链查找而不是 getDeclaredField。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Field> fieldCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Field cachedField(Class<?> clazz, String name) {
        return fieldCache.computeIfAbsent(clazz, c -> findFieldInHierarchy(c, name));
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
     */
    private boolean applyReactionsRule(Object reactions, ReactionsRule rule) {
        int likeCount = countReaction(reactions, rule.emoji);
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

                // 仅 TL_reactionEmoji 有 emoticon 字符串；自定义表情(document_id)/付费星星无法按字符匹配
                if (reaction == null || !reaction.getClass().getSimpleName().equals("TL_reactionEmoji")) {
                    continue;
                }
                Field emoticonField = cachedField(reaction.getClass(), "emoticon");
                if (emoticonField == null) continue;
                if (!emoji.equals(emoticonField.get(reaction))) continue;

                Field countField = cachedField(rcClass, "count");
                if (countField == null) continue;
                return countField.getInt(reactionCount);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }
}
