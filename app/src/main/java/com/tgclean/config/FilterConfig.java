package com.tgclean.config;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * 过滤配置管理
 *
 * 使用 libxposed RemotePreferences 实现跨进程配置共享
 * 配置在模块APP中写入，在Hook进程中读取
 */
public class FilterConfig {
    private static final String TAG = "TGClean-Config";
    private static final String PREFS_NAME = "tgclean_config";

    // 配置键
    private static final String KEY_ENABLED = "filter_enabled";
    private static final String KEY_GLOBAL_KEYWORDS = "global_keywords";
    private static final String KEY_USE_REGEX = "use_regex";
    private static final String KEY_CHANNEL_RULES = "channel_rules";
    private static final String KEY_WHITELIST = "whitelist";
    private static final String KEY_REACTIONS_ENABLED = "reactions_filter_enabled";
    private static final String KEY_REACTIONS_EMOJI = "reactions_filter_emoji";
    private static final String KEY_REACTIONS_THRESHOLD = "reactions_filter_threshold";

    private final XposedModule module;
    private final SharedPreferences prefs;
    // 必须存为字段，否则 lambda 被 WeakReference 引用后会被 GC 回收
    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    public FilterConfig(XposedModule module) {
        this.module = module;
        this.prefs = module.getRemotePreferences(PREFS_NAME);

        this.prefChangeListener = (sharedPreferences, key) -> {
            module.log(Log.INFO, TAG, "Config changed: " + key);
        };
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public boolean isUseRegex() {
        return prefs.getBoolean(KEY_USE_REGEX, false);
    }

    /**
     * 获取全局关键词列表
     */
    public Set<String> getGlobalKeywords() {
        Set<String> keywords = new HashSet<>();
        String raw = prefs.getString(KEY_GLOBAL_KEYWORDS, "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    keywords.add(trimmed);
                }
            }
        }
        return keywords;
    }

    /**
     * 获取全局正则模式
     */
    public List<Pattern> getGlobalPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        if (!isUseRegex()) return patterns;

        String raw = prefs.getString(KEY_GLOBAL_KEYWORDS, "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        patterns.add(Pattern.compile(trimmed));
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "Invalid regex: " + trimmed);
                    }
                }
            }
        }
        return patterns;
    }

    /**
     * 获取分频道关键词
     * 格式: dialogId:keyword1,keyword2;dialogId2:keyword3
     */
    public Map<Long, Set<String>> getChannelKeywords() {
        Map<Long, Set<String>> result = new HashMap<>();
        String raw = prefs.getString(KEY_CHANNEL_RULES, "");
        if (raw == null || raw.isEmpty()) return result;

        for (String rule : raw.split(";")) {
            String[] parts = rule.split(":", 2);
            if (parts.length == 2) {
                try {
                    long dialogId = Long.parseLong(parts[0].trim());
                    Set<String> keywords = new HashSet<>();
                    for (String kw : parts[1].split(",")) {
                        String trimmed = kw.trim();
                        if (!trimmed.isEmpty()) {
                            keywords.add(trimmed);
                        }
                    }
                    if (!keywords.isEmpty()) {
                        result.put(dialogId, keywords);
                    }
                } catch (NumberFormatException e) {
                    module.log(Log.WARN, TAG, "Invalid channel rule: " + rule);
                }
            }
        }
        return result;
    }

    /**
     * 获取分频道正则模式
     */
    public Map<Long, List<Pattern>> getChannelPatterns() {
        Map<Long, List<Pattern>> result = new HashMap<>();
        if (!isUseRegex()) return result;

        String raw = prefs.getString(KEY_CHANNEL_RULES, "");
        if (raw == null || raw.isEmpty()) return result;

        for (String rule : raw.split(";")) {
            String[] parts = rule.split(":", 2);
            if (parts.length == 2) {
                try {
                    long dialogId = Long.parseLong(parts[0].trim());
                    List<Pattern> patterns = new ArrayList<>();
                    for (String kw : parts[1].split(",")) {
                        String trimmed = kw.trim();
                        if (!trimmed.isEmpty()) {
                            try {
                                patterns.add(Pattern.compile(trimmed));
                            } catch (Throwable t) {
                                module.log(Log.WARN, TAG, "Invalid regex: " + trimmed);
                            }
                        }
                    }
                    if (!patterns.isEmpty()) {
                        result.put(dialogId, patterns);
                    }
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }
        return result;
    }

    /**
     * 获取白名单对话ID
     */
    public Set<Long> getWhitelist() {
        Set<Long> whitelist = new HashSet<>();
        String raw = prefs.getString(KEY_WHITELIST, "");
        if (raw != null && !raw.isEmpty()) {
            for (String id : raw.split(",")) {
                try {
                    whitelist.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }
        return whitelist;
    }

    public boolean isReactionsFilterEnabled() {
        return prefs.getBoolean(KEY_REACTIONS_ENABLED, false);
    }

    public String getReactionsFilterEmoji() {
        return prefs.getString(KEY_REACTIONS_EMOJI, "👎");
    }

    public int getReactionsFilterThreshold() {
        return prefs.getInt(KEY_REACTIONS_THRESHOLD, 10);
    }
}
