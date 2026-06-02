package com.tgclean.config;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * 过滤配置读取器 — Hook 端专用（只读）
 *
 * ⚠️ Hook 端（Telegram 进程）的 RemotePreferences 是只读的，
 *    所有 edit() 调用会抛 UnsupportedOperationException。
 *    写入操作必须在 App 端通过 FilterConfigWriter 完成。
 *
 * 使用 libxposed RemotePreferences 实现跨进程配置共享。
 * channel_rules 使用 JSON 格式：
 *   {"-100123":["kw1","kw2"], "-100456":["kw3"]}
 * 读取时自动兼容旧格式 "id:kw1,kw2;id:kw3"。
 */
public class FilterConfig {
    private static final String TAG = "TGClean-Config";

    static final String PREFS_NAME = "tgclean_config";
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
    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    public FilterConfig(XposedModule module) {
        this.module = module;
        this.prefs = module.getRemotePreferences(PREFS_NAME);

        this.prefChangeListener = (sharedPreferences, key) -> {
            module.log(Log.INFO, TAG, "Config changed: " + key);
        };
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener);
    }

    // ═════════════════════════════════════════════
    // ⚠️ 此类所有方法均为只读！
    // 写入操作请使用 App 端的 FilterConfigWriter
    // ═════════════════════════════════════════════

    public SharedPreferences getPrefs() {
        return prefs;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public boolean isUseRegex() {
        return prefs.getBoolean(KEY_USE_REGEX, false);
    }

    // ═════════════════════════════════════════════
    // 全局关键词
    // ═════════════════════════════════════════════

    public Set<String> getGlobalKeywords() {
        Set<String> keywords = new HashSet<>();
        String raw = prefs.getString(KEY_GLOBAL_KEYWORDS, "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) keywords.add(trimmed);
            }
        }
        return keywords;
    }

    public List<Pattern> getGlobalPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        if (!isUseRegex()) return patterns;
        for (String kw : getGlobalKeywords()) {
            try {
                patterns.add(Pattern.compile(kw));
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Invalid regex: " + kw);
            }
        }
        return patterns;
    }

    // ═════════════════════════════════════════════
    // 分频道规则 — JSON 存储格式
    // ═════════════════════════════════════════════

    /**
     * 获取分频道关键词
     * 读取时自动兼容旧格式 "id:kw1,kw2;id2:kw3"
     */
    public Map<Long, Set<String>> getChannelKeywords() {
        String raw = prefs.getString(KEY_CHANNEL_RULES, "");
        if (raw == null || raw.isEmpty()) return new HashMap<>();

        if (raw.trim().startsWith("{")) {
            return parseChannelRulesJson(raw);
        }

        // Fallback: 旧格式解析
        return parseChannelRulesLegacy(raw);
    }

    public Map<Long, List<Pattern>> getChannelPatterns() {
        Map<Long, List<Pattern>> result = new HashMap<>();
        if (!isUseRegex()) return result;

        for (Map.Entry<Long, Set<String>> entry : getChannelKeywords().entrySet()) {
            List<Pattern> patterns = new ArrayList<>();
            for (String kw : entry.getValue()) {
                try {
                    patterns.add(Pattern.compile(kw));
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "Invalid regex: " + kw);
                }
            }
            if (!patterns.isEmpty()) {
                result.put(entry.getKey(), patterns);
            }
        }
        return result;
    }

    // ═════════════════════════════════════════════
    // 白名单
    // ═════════════════════════════════════════════

    public Set<Long> getWhitelist() {
        String raw = prefs.getString(KEY_WHITELIST, "");
        if (raw == null || raw.isEmpty()) return new HashSet<>();

        if (raw.trim().startsWith("[")) {
            return parseWhitelistJson(raw);
        }

        return parseWhitelistLegacy(raw);
    }

    public boolean isWhitelisted(long dialogId) {
        return getWhitelist().contains(dialogId);
    }

    // ═════════════════════════════════════════════
    // Reactions 过滤
    // ═════════════════════════════════════════════

    public boolean isReactionsFilterEnabled() {
        return prefs.getBoolean(KEY_REACTIONS_ENABLED, false);
    }

    public String getReactionsFilterEmoji() {
        return prefs.getString(KEY_REACTIONS_EMOJI, "👎");
    }

    public int getReactionsFilterThreshold() {
        return prefs.getInt(KEY_REACTIONS_THRESHOLD, 10);
    }

    // ═════════════════════════════════════════════
    // 内部反序列化
    // ═════════════════════════════════════════════

    private Map<Long, Set<String>> parseChannelRulesJson(String raw) {
        Map<Long, Set<String>> result = new HashMap<>();
        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray arr = json.getJSONArray(key);
                Set<String> keywords = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String kw = arr.getString(i).trim();
                    if (!kw.isEmpty()) keywords.add(kw);
                }
                if (!keywords.isEmpty()) {
                    result.put(Long.parseLong(key), keywords);
                }
            }
        } catch (JSONException | NumberFormatException e) {
            module.log(Log.WARN, TAG, "Failed to parse channel rules JSON: " + e.getMessage());
        }
        return result;
    }

    private Map<Long, Set<String>> parseChannelRulesLegacy(String raw) {
        Map<Long, Set<String>> result = new HashMap<>();
        for (String rule : raw.split(";")) {
            String[] parts = rule.split(":", 2);
            if (parts.length == 2) {
                try {
                    long dialogId = Long.parseLong(parts[0].trim());
                    Set<String> keywords = new HashSet<>();
                    for (String kw : parts[1].split(",")) {
                        String trimmed = kw.trim();
                        if (!trimmed.isEmpty()) keywords.add(trimmed);
                    }
                    if (!keywords.isEmpty()) result.put(dialogId, keywords);
                } catch (NumberFormatException e) {
                    module.log(Log.WARN, TAG, "Invalid channel rule: " + rule);
                }
            }
        }
        return result;
    }

    private Set<Long> parseWhitelistJson(String raw) {
        Set<Long> result = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getLong(i));
            }
        } catch (JSONException e) {
            module.log(Log.WARN, TAG, "Failed to parse whitelist JSON: " + e.getMessage());
        }
        return result;
    }

    private Set<Long> parseWhitelistLegacy(String raw) {
        Set<Long> result = new HashSet<>();
        for (String id : raw.split(",")) {
            try {
                result.add(Long.parseLong(id.trim()));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return result;
    }
}
