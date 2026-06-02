package com.tgclean.config;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 过滤配置写入器 — App 端专用
 *
 * 在 TGClean App 进程中通过 XposedService.getRemotePreferences() 获取
 * SharedPreferences 实例，此实例的 edit() 是可写的。
 *
 * Hook 端（Telegram 进程）的 RemotePreferences 是只读的，不能使用此类。
 */
public class FilterConfigWriter {
    private static final String TAG = "TGClean-ConfigWriter";

    private static final String KEY_ENABLED = "filter_enabled";
    private static final String KEY_GLOBAL_KEYWORDS = "global_keywords";
    private static final String KEY_USE_REGEX = "use_regex";
    private static final String KEY_CHANNEL_RULES = "channel_rules";
    private static final String KEY_WHITELIST = "whitelist";
    private static final String KEY_REACTIONS_ENABLED = "reactions_filter_enabled";
    private static final String KEY_REACTIONS_EMOJI = "reactions_filter_emoji";
    private static final String KEY_REACTIONS_THRESHOLD = "reactions_filter_threshold";
    private static final String KEY_DISCOVERED_CHANNELS = "discovered_channels";

    private final SharedPreferences prefs;

    public FilterConfigWriter(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    // ═════════════════════════════════════════════
    // 基础设置
    // ═════════════════════════════════════════════

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public void setUseRegex(boolean useRegex) {
        prefs.edit().putBoolean(KEY_USE_REGEX, useRegex).apply();
    }

    // ═════════════════════════════════════════════
    // 全局关键词
    // ═════════════════════════════════════════════

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

    private Set<String> getGlobalKeywords() {
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

    // ═════════════════════════════════════════════
    // 分频道关键词（JSON 格式）
    // ═════════════════════════════════════════════

    public void setChannelRules(Map<Long, Set<String>> rules) {
        saveChannelRules(rules);
    }

    public void addChannelKeyword(long dialogId, String keyword) {
        Map<Long, Set<String>> rules = getChannelKeywords();
        rules.computeIfAbsent(dialogId, k -> new HashSet<>()).add(keyword);
        saveChannelRules(rules);
    }

    public void removeChannelKeyword(long dialogId, String keyword) {
        Map<Long, Set<String>> rules = getChannelKeywords();
        Set<String> kw = rules.get(dialogId);
        if (kw != null) {
            kw.remove(keyword);
            if (kw.isEmpty()) rules.remove(dialogId);
        }
        saveChannelRules(rules);
    }

    public void removeChannelRule(long dialogId) {
        Map<Long, Set<String>> rules = getChannelKeywords();
        rules.remove(dialogId);
        saveChannelRules(rules);
    }

    private Map<Long, Set<String>> getChannelKeywords() {
        String raw = prefs.getString(KEY_CHANNEL_RULES, "");
        if (raw == null || raw.isEmpty()) return new java.util.HashMap<>();
        if (raw.trim().startsWith("{")) return parseChannelRulesJson(raw);
        return new java.util.HashMap<>();
    }

    private void saveChannelRules(Map<Long, Set<String>> rules) {
        if (rules.isEmpty()) {
            prefs.edit().putString(KEY_CHANNEL_RULES, "").apply();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<Long, Set<String>> entry : rules.entrySet()) {
                JSONArray arr = new JSONArray();
                for (String kw : entry.getValue()) arr.put(kw);
                json.put(String.valueOf(entry.getKey()), arr);
            }
            prefs.edit().putString(KEY_CHANNEL_RULES, json.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize channel rules", e);
        }
    }

    private Map<Long, Set<String>> parseChannelRulesJson(String raw) {
        Map<Long, Set<String>> result = new java.util.HashMap<>();
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
                if (!keywords.isEmpty()) result.put(Long.parseLong(key), keywords);
            }
        } catch (JSONException | NumberFormatException e) {
            Log.e(TAG, "Failed to parse channel rules JSON", e);
        }
        return result;
    }

    // ═════════════════════════════════════════════
    // 白名单（JSON 数组）
    // ═════════════════════════════════════════════

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

    private Set<Long> getWhitelist() {
        String raw = prefs.getString(KEY_WHITELIST, "");
        if (raw == null || raw.isEmpty()) return new HashSet<>();
        if (raw.trim().startsWith("[")) return parseWhitelistJson(raw);
        return new HashSet<>();
    }

    private void saveWhitelist(Set<Long> whitelist) {
        if (whitelist.isEmpty()) {
            prefs.edit().putString(KEY_WHITELIST, "").apply();
            return;
        }
        JSONArray arr = new JSONArray();
        for (Long id : whitelist) arr.put(id);
        prefs.edit().putString(KEY_WHITELIST, arr.toString()).apply();
    }

    private Set<Long> parseWhitelistJson(String raw) {
        Set<Long> result = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) result.add(arr.getLong(i));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse whitelist JSON", e);
        }
        return result;
    }

    // ═════════════════════════════════════════════
    // Reactions 过滤
    // ═════════════════════════════════════════════

    public void setReactionsFilterEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REACTIONS_ENABLED, enabled).apply();
    }

    public void setReactionsFilterEmoji(String emoji) {
        prefs.edit().putString(KEY_REACTIONS_EMOJI, emoji).apply();
    }

    public void setReactionsFilterThreshold(int threshold) {
        prefs.edit().putInt(KEY_REACTIONS_THRESHOLD, threshold).apply();
    }

    // ═════════════════════════════════════════════
    // 频道发现列表（Hook 端写入，App 端读取）
    // ═════════════════════════════════════════════

    /**
     * 读取 Hook 端发现的频道列表
     * 格式：[{id: -100123, name: "频道A"}, ...]
     */
    public List<DiscoveredChannel> getDiscoveredChannels() {
        List<DiscoveredChannel> result = new ArrayList<>();
        String raw = prefs.getString(KEY_DISCOVERED_CHANNELS, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("[")) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                long id = obj.optLong("id", 0);
                String name = obj.optString("name", String.valueOf(id));
                if (id != 0) result.add(new DiscoveredChannel(id, name));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse discovered channels", e);
        }
        return result;
    }

    // ═════════════════════════════════════════════
    // 内部工具
    // ═════════════════════════════════════════════

    private static String joinLines(Set<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 发现的频道数据模型
     */
    public static class DiscoveredChannel {
        public final long id;
        public final String name;

        public DiscoveredChannel(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
