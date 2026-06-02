package com.tgclean.filter;

import android.content.SharedPreferences;

import com.tgclean.config.FilterConfig;

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
 * 1. 白名单 → 放行
 * 2. 规则集匹配 → 检查该规则集是否覆盖了当前频道，再匹配关键词
 * 3. 旧版分频道规则（兼容迁移期）
 * 4. 全局关键词兜底
 * 5. Reactions 过滤
 *
 * 支持：纯文本匹配 + 正则匹配 + 每规则集独立正则开关
 */
public class KeywordEngine {
    private final FilterConfig config;

    // 规则集缓存
    private List<FilterConfig.RuleSet> ruleSets = new ArrayList<>();
    private Map<String, Set<Long>> ruleSetChannels = new HashMap<>();

    // 全局关键词
    private Set<String> globalKeywords = new HashSet<>();
    private List<Pattern> globalPatterns = new ArrayList<>();

    // 旧版分频道关键词（兼容）
    private Map<Long, Set<String>> channelKeywords = new HashMap<>();
    private Map<Long, List<Pattern>> channelPatterns = new HashMap<>();

    // 白名单
    private Set<Long> whitelist = new HashSet<>();

    // 必须存为字段，否则 lambda 被 WeakReference 引用后会被 GC 回收
    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    public KeywordEngine(FilterConfig config) {
        this.config = config;
        loadRules();

        this.prefChangeListener = (prefs, key) -> loadRules();
        config.getPrefs().registerOnSharedPreferenceChangeListener(prefChangeListener);
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 加载过滤规则
     */
    public void loadRules() {
        // 加载规则集
        ruleSets = config.getRuleSets();
        ruleSetChannels = config.getRuleSetChannels();

        // 预编译规则集正则
        compileRuleSetPatterns();

        // 加载全局关键词
        globalKeywords = config.getGlobalKeywords();
        globalPatterns = config.getGlobalPatterns();

        // 兼容：加载旧版分频道规则
        channelKeywords = config.getChannelKeywords();
        channelPatterns = config.getChannelPatterns();

        // 加载白名单
        whitelist = config.getWhitelist();
    }

    // ═════════════════════════════════════════════
    // 规则集正则缓存
    // ═════════════════════════════════════════════

    private final Map<String, List<Pattern>> ruleSetPatternCache = new HashMap<>();

    private void compileRuleSetPatterns() {
        ruleSetPatternCache.clear();
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
                ruleSetPatternCache.put(rs.id, patterns);
            }
        }
    }

    // ═════════════════════════════════════════════
    // 核心匹配
    // ═════════════════════════════════════════════

    /**
     * 检查消息是否应被过滤
     *
     * @param text     消息文本
     * @param dialogId 对话ID（负数=频道/群组，正数=私聊）
     * @return true=应过滤
     */
    public boolean shouldFilter(String text, long dialogId) {
        if (text == null || text.isEmpty()) return false;

        // 1. 白名单检查
        if (whitelist.contains(dialogId)) return false;

        // 2. 规则集匹配（核心新逻辑）
        for (FilterConfig.RuleSet rs : ruleSets) {
            if (!rs.enabled) continue;

            // 检查该规则集是否覆盖了此频道
            Set<Long> channels = ruleSetChannels.get(rs.id);
            if (channels == null || !channels.contains(dialogId)) continue;

            // 关键词匹配
            if (matchKeywords(text, rs.keywords, rs.useRegex, rs.id)) {
                return true;
            }
        }

        // 3. 旧版分频道关键词（兼容迁移期）
        Set<String> channelKw = channelKeywords.get(dialogId);
        if (channelKw != null && !channelKw.isEmpty()) {
            for (String keyword : channelKw) {
                if (text.contains(keyword)) return true;
            }
        }

        List<Pattern> channelPat = channelPatterns.get(dialogId);
        if (channelPat != null) {
            for (Pattern pattern : channelPat) {
                if (pattern.matcher(text).find()) return true;
            }
        }

        // 4. 全局关键词检查
        for (String keyword : globalKeywords) {
            if (text.contains(keyword)) return true;
        }

        for (Pattern pattern : globalPatterns) {
            if (pattern.matcher(text).find()) return true;
        }

        return false;
    }

    /**
     * 检查消息是否应被过滤（包含Reactions检查）
     */
    public boolean shouldFilter(String text, long dialogId, Object reactions) {
        if (shouldFilter(text, dialogId)) return true;

        if (reactions != null && config.isReactionsFilterEnabled()) {
            return checkReactions(reactions);
        }

        return false;
    }

    private boolean matchKeywords(String text, Set<String> keywords, boolean useRegex, String ruleSetId) {
        if (useRegex) {
            List<Pattern> patterns = ruleSetPatternCache.get(ruleSetId);
            if (patterns != null) {
                for (Pattern pattern : patterns) {
                    if (pattern.matcher(text).find()) return true;
                }
            }
        } else {
            for (String keyword : keywords) {
                if (text.contains(keyword)) return true;
            }
        }
        return false;
    }

    // ═════════════════════════════════════════════
    // Reactions 过滤（不变）
    // ═════════════════════════════════════════════

    private boolean checkReactions(Object reactions) {
        try {
            java.lang.reflect.Field resultsField = reactions.getClass().getDeclaredField("results");
            resultsField.setAccessible(true);
            Object results = resultsField.get(reactions);
            if (!(results instanceof List)) return false;

            List<?> reactionCountList = (List<?>) results;
            String targetEmoji = config.getReactionsFilterEmoji();
            int threshold = config.getReactionsFilterThreshold();

            for (Object reactionCount : reactionCountList) {
                java.lang.reflect.Field reactionField = reactionCount.getClass().getDeclaredField("reaction");
                reactionField.setAccessible(true);
                Object reaction = reactionField.get(reactionCount);

                if (reaction != null && reaction.getClass().getSimpleName().equals("TL_reactionEmoji")) {
                    java.lang.reflect.Field emoticonField = reaction.getClass().getDeclaredField("emoticon");
                    emoticonField.setAccessible(true);
                    String emoticon = (String) emoticonField.get(reaction);

                    if (targetEmoji.equals(emoticon)) {
                        java.lang.reflect.Field countField = reactionCount.getClass().getDeclaredField("count");
                        countField.setAccessible(true);
                        int count = countField.getInt(reactionCount);

                        if (count >= threshold) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Reactions检查失败不影响整体过滤
        }
        return false;
    }
}
