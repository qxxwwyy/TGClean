package com.tgclean.filter;

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
 * 支持：
 * - 全局关键词（应用于所有对话）
 * - 分频道关键词（仅应用于指定频道/群组）
 * - 纯文本匹配 + 正则匹配
 * - 白名单（不过滤的对话）
 */
public class KeywordEngine {
    private final FilterConfig config;

    // 全局关键词（纯文本）
    private Set<String> globalKeywords = new HashSet<>();
    // 全局正则模式
    private List<Pattern> globalPatterns = new ArrayList<>();
    // 分频道关键词: dialogId -> keywords
    private Map<Long, Set<String>> channelKeywords = new HashMap<>();
    // 分频道正则: dialogId -> patterns
    private Map<Long, List<Pattern>> channelPatterns = new HashMap<>();
    // 白名单对话（不过滤）
    private Set<Long> whitelist = new HashSet<>();

    public KeywordEngine(FilterConfig config) {
        this.config = config;
        loadRules();
    }

    /**
     * 检查过滤是否启用
     */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 加载过滤规则
     */
    public void loadRules() {
        // 从配置加载全局关键词
        globalKeywords = config.getGlobalKeywords();
        globalPatterns = config.getGlobalPatterns();

        // 加载分频道规则
        channelKeywords = config.getChannelKeywords();
        channelPatterns = config.getChannelPatterns();

        // 加载白名单
        whitelist = config.getWhitelist();
    }

    /**
     * 检查消息是否应被过滤
     *
     * @param text     消息文本
     * @param dialogId 对话ID（负数=频道/群组，正数=私聊）
     * @return true=应过滤
     */
    public boolean shouldFilter(String text, long dialogId) {
        if (text == null || text.isEmpty()) return false;

        // 白名单检查
        if (whitelist.contains(dialogId)) return false;

        // 分频道关键词检查（优先级高于全局）
        Set<String> channelKw = channelKeywords.get(dialogId);
        if (channelKw != null && !channelKw.isEmpty()) {
            for (String keyword : channelKw) {
                if (text.contains(keyword)) return true;
            }
        }

        // 分频道正则检查
        List<Pattern> channelPat = channelPatterns.get(dialogId);
        if (channelPat != null) {
            for (Pattern pattern : channelPat) {
                if (pattern.matcher(text).find()) return true;
            }
        }

        // 全局关键词检查
        for (String keyword : globalKeywords) {
            if (text.contains(keyword)) return true;
        }

        // 全局正则检查
        for (Pattern pattern : globalPatterns) {
            if (pattern.matcher(text).find()) return true;
        }

        return false;
    }

    /**
     * 检查消息是否应被过滤（包含Reactions检查）
     *
     * @param text       消息文本
     * @param dialogId   对话ID
     * @param reactions  消息的Reactions数据（TLRPC.TL_messageReactions）
     * @return true=应过滤
     */
    public boolean shouldFilter(String text, long dialogId, Object reactions) {
        // 先检查文本关键词
        if (shouldFilter(text, dialogId)) return true;

        // 再检查Reactions
        if (reactions != null && config.isReactionsFilterEnabled()) {
            return checkReactions(reactions);
        }

        return false;
    }

    /**
     * 检查Reactions是否触发过滤
     *
     * Telegram Reactions 结构：
     * TLRPC.TL_messageReactions
     *   └── results: ArrayList<ReactionCount>
     *         ├── reaction: Reaction (TL_reactionEmoji.emoticon)
     *         └── count: int
     */
    private boolean checkReactions(Object reactions) {
        try {
            // 获取 results 字段
            java.lang.reflect.Field resultsField = reactions.getClass().getDeclaredField("results");
            resultsField.setAccessible(true);
            Object results = resultsField.get(reactions);
            if (!(results instanceof List)) return false;

            List<?> reactionCountList = (List<?>) results;
            String targetEmoji = config.getReactionsFilterEmoji();
            int threshold = config.getReactionsFilterThreshold();

            for (Object reactionCount : reactionCountList) {
                // 获取 reaction 字段
                java.lang.reflect.Field reactionField = reactionCount.getClass().getDeclaredField("reaction");
                reactionField.setAccessible(true);
                Object reaction = reactionField.get(reactionCount);

                // 检查是否为 TL_reactionEmoji
                if (reaction != null && reaction.getClass().getSimpleName().equals("TL_reactionEmoji")) {
                    java.lang.reflect.Field emoticonField = reaction.getClass().getDeclaredField("emoticon");
                    emoticonField.setAccessible(true);
                    String emoticon = (String) emoticonField.get(reaction);

                    if (targetEmoji.equals(emoticon)) {
                        // 获取 count
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
