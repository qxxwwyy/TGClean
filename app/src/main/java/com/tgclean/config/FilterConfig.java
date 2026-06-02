package com.tgclean.config;

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
 * 过滤配置管理 — v2 JSON 存储格式
 *
 * 使用 libxposed RemotePreferences 实现跨进程配置共享。
 * channel_rules 从旧格式 "id:kw1,kw2;id:kw3" 升级为 JSON:
 *   {"-100123":["kw1","kw2"], "-100456":["kw3"]}
 * 读取时自动兼容旧格式。
 */
public class FilterConfig {
    private static final String TAG = "TGClean-Config";

    // 配置键
    static final String PREFS_NAME = "tgclean_config";
    private static final String KEY_ENABLED = "filter_enabled";
    private static final String KEY_GLOBAL_KEYWORDS = "global_keywords";
    private static final String KEY_USE_REGEX = "use_regex";
    private static final String KEY_CHANNEL_RULES = "channel_rules";        // JSON Map<Long, Set<String>>
    private static final String KEY_WHITELIST = "whitelist";                // JSON Set<Long>
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
    // 基础读写
    // ═════════════════════════════════════════════

    public SharedPreferences getPrefs() {
        return prefs;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public boolean isUseRegex() {
        return prefs.getBoolean(KEY_USE_REGEX, false);
    }

    public void setUseRegex(boolean useRegex) {
        prefs.edit().putBoolean(KEY_USE_REGEX, useRegex).apply();
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
                if (!trimmed.isEmpty()) {
                    keywords.add(trimmed);
                }
            }
        }
        return keywords;
    }

    public void setGlobalKeywords(Set<String> keywords) {
        prefs.edit().putString(KEY_GLOBAL_KEYWORDS, joinLines(keywords)).apply();
    }

    public void addGlobalKeyword(String keyword) {
        Set<String> kw = getGlobalKeywords();
        kw.add(keyword);
        setGlobalKeywords(kw);
    }

    public void removeGlobalKeyword(String keyword) {
        Set<String> kw = getGlobalKeywords();
        kw.remove(keyword);
        setGlobalKeywords(kw);
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

        // 尝试 JSON 格式
        if (raw.trim().startsWith("{")) {
            return parseChannelRulesJson(raw);
        }

        // Fallback: 旧格式解析，并自动升级为 JSON
        Map<Long, Set<String>> legacy = parseChannelRulesLegacy(raw);
        if (!legacy.isEmpty()) {
            // 自动升级存储
            saveChannelRules(legacy);
        }
        return legacy;
    }

    public void setChannelRules(Map<Long, Set<String>> rules) {
        saveChannelRules(rules);
    }

    /**
     * 添加关键词到指定频道（原子操作）
     */
    public void addChannelKeyword(long dialogId, String keyword) {
        Map<Long, Set<String>> rules = getChannelKeywords();
        rules.computeIfAbsent(dialogId, k -> new HashSet<>()).add(keyword);
        saveChannelRules(rules);
    }

    /**
     * 从指定频道移除关键词（空规则自动清理）
     */
    public void removeChannelKeyword(long dialogId, String keyword) {
        Map<Long, Set<String>> rules = getChannelKeywords();
        Set<String> kw = rules.get(dialogId);
        if (kw != null) {
            kw.remove(keyword);
            if (kw.isEmpty()) {
                rules.remove(dialogId);
            }
        }
        saveChannelRules(rules);
    }

    /**
     * 添加频道到白名单
     */
    public void addChannelRule(long dialogId, Set<String> keywords) {
        Map<Long, Set<String>> rules = getChannelKeywords();
        rules.put(dialogId, new HashSet<>(keywords));
        saveChannelRules(rules);
    }

    /**
     * 移除整个频道的规则
     */
    public void removeChannelRule(long dialogId) {
        Map<Long, Set<String>> rules = getChannelKeywords();
        rules.remove(dialogId);
        saveChannelRules(rules);
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

        // JSON 格式
        if (raw.trim().startsWith("[")) {
            return parseWhitelistJson(raw);
        }

        // Fallback: 旧格式逗号分隔
        Set<Long> legacy = parseWhitelistLegacy(raw);
        if (!legacy.isEmpty()) {
            saveWhitelist(legacy);
        }
        return legacy;
    }

    public void setWhitelist(Set<Long> whitelist) {
        saveWhitelist(whitelist);
    }

    public void addToWhitelist(long dialogId) {
        Set<Long> wl = getWhitelist();
        wl.add(dialogId);
        saveWhitelist(wl);
    }

    public void removeFromWhitelist(long dialogId) {
        Set<Long> wl = getWhitelist();
        wl.remove(dialogId);
        saveWhitelist(wl);
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

    public void setReactionsFilterEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REACTIONS_ENABLED, enabled).apply();
    }

    public String getReactionsFilterEmoji() {
        return prefs.getString(KEY_REACTIONS_EMOJI, "👎");
    }

    public void setReactionsFilterEmoji(String emoji) {
        prefs.edit().putString(KEY_REACTIONS_EMOJI, emoji).apply();
    }

    public int getReactionsFilterThreshold() {
        return prefs.getInt(KEY_REACTIONS_THRESHOLD, 10);
    }

    public void setReactionsFilterThreshold(int threshold) {
        prefs.edit().putInt(KEY_REACTIONS_THRESHOLD, threshold).apply();
    }

    // ═════════════════════════════════════════════
    // 内部序列化/反序列化
    // ═════════════════════════════════════════════

    private void saveChannelRules(Map<Long, Set<String>> rules) {
        if (rules.isEmpty()) {
            prefs.edit().putString(KEY_CHANNEL_RULES, "").apply();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<Long, Set<String>> entry : rules.entrySet()) {
                JSONArray arr = new JSONArray();
                for (String kw : entry.getValue()) {
                    arr.put(kw);
                }
                json.put(String.valueOf(entry.getKey()), arr);
            }
            prefs.edit().putString(KEY_CHANNEL_RULES, json.toString()).apply();
        } catch (JSONException e) {
            module.log(Log.ERROR, TAG, "Failed to serialize channel rules", e);
        }
    }

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

    private void saveWhitelist(Set<Long> whitelist) {
        if (whitelist.isEmpty()) {
            prefs.edit().putString(KEY_WHITELIST, "").apply();
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (Long id : whitelist) {
                arr.put(id);
            }
            prefs.edit().putString(KEY_WHITELIST, arr.toString()).apply();
        } catch (JSONException e) {
            module.log(Log.ERROR, TAG, "Failed to serialize whitelist", e);
        }
    }

    private Set<Long> parseWhitelistJson(String raw) {
        Set<Long> result = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                result.put(arr.getLong(i));
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

    private static String joinLines(Set<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }
}
