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
 * 数据结构：
 *   rule_sets: JSON array of rule set objects
 *     [{id, name, enabled, keywords[], use_regex}]
 *   rule_set_channels: JSON object
 *     {ruleSetId: [dialogId, ...]}
 *   global_keywords: string (newline separated)
 *   whitelist: JSON array of dialogId
 */
public class FilterConfig {
    private static final String TAG = "TGClean-Config";

    static final String PREFS_NAME = "tgclean_config";
    private static final String KEY_ENABLED = "filter_enabled";
    private static final String KEY_GLOBAL_KEYWORDS = "global_keywords";
    private static final String KEY_USE_REGEX = "use_regex";
    private static final String KEY_CHANNEL_RULES = "channel_rules"; // legacy
    private static final String KEY_WHITELIST = "whitelist";
    private static final String KEY_REACTIONS_RULES = "reactions_channel_rules";
    private static final String KEY_REACTIONS_DEPTH = "reactions_search_depth";
    private static final String KEY_RULE_SETS = "rule_sets";
    private static final String KEY_RULE_SET_CHANNELS = "rule_set_channels";

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

    /**
     * 表情筛选全局默认检索深度（条）。每频道规则 maxDepth>0 时被其覆盖；
     * 关键词过滤触发的级联也用它。RemotePreferences 按 key 实时更新。
     */
    public int getReactionsSearchDepth() {
        int v = prefs.getInt(KEY_REACTIONS_DEPTH, ReactionsRule.DEFAULT_MAX_DEPTH);
        return v > 0 ? v : ReactionsRule.DEFAULT_MAX_DEPTH;
    }

    // ═════════════════════════════════════════════
    // 规则集（Rule Sets）
    // ═════════════════════════════════════════════

    public List<RuleSet> getRuleSets() {
        String raw = prefs.getString(KEY_RULE_SETS, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("[")) {
            return new ArrayList<>();
        }
        return parseRuleSetsJson(raw);
    }

    /**
     * 获取 规则集ID → 频道ID列表 的映射
     */
    public Map<String, Set<Long>> getRuleSetChannels() {
        String raw = prefs.getString(KEY_RULE_SET_CHANNELS, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("{")) {
            return new HashMap<>();
        }
        return parseRuleSetChannelsJson(raw);
    }

    // ═════════════════════════════════════════════
    // 调试日志 / 写通道配对令牌
    // ═════════════════════════════════════════════

    private static final String KEY_DEBUG_LOG = "debug_log";
    private static final String KEY_PAIRING_TOKEN = "pairing_token";

    /**
     * 调试日志开关（默认关）。关时逐条消息明细（FILTERED 预览、RX-DEBUG、
     * 启动配置 dump）不打，仅保留批量摘要——用户通信内容不进 LSPosed 日志。
     */
    public boolean isDebugLog() {
        return prefs.getBoolean(KEY_DEBUG_LOG, false);
    }

    /**
     * 写通道配对令牌：App 端首次生成后写入 remote prefs（存于 LSPosed
     * 框架数据目录，第三方 App 不可读），TG 侧只读后随保存广播带回，
     * App 端校验一致才落盘——防任意 App 伪造 intent 改写过滤规则。
     * 空串 = 尚未配对（信任首次）。
     */
    public String getPairingToken() {
        String v = prefs.getString(KEY_PAIRING_TOKEN, "");
        return v != null ? v : "";
    }

    // ═════════════════════════════════════════════
    // 全局关键词（兜底匹配）
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
    // 旧版分频道规则（兼容读取）
    // ═════════════════════════════════════════════

    public Map<Long, Set<String>> getChannelKeywords() {
        String raw = prefs.getString(KEY_CHANNEL_RULES, "");
        if (raw == null || raw.isEmpty()) return new HashMap<>();

        if (raw.trim().startsWith("{")) {
            return parseChannelRulesJson(raw);
        }
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

    // ═════════════════════════════════════════════
    // 每频道表情过滤规则
    // ═════════════════════════════════════════════

    /**
     * 获取 dialogId → 表情过滤规则 的映射。
     * 旧版全局 reactions 配置（reactions_filter_*）已被此机制取代，不再读取。
     */
    public Map<Long, ReactionsRule> getReactionsChannelRules() {
        String raw = prefs.getString(KEY_REACTIONS_RULES, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("{")) {
            return new HashMap<>();
        }
        Map<Long, ReactionsRule> result = new HashMap<>();
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject r = obj.optJSONObject(key);
                if (r == null) continue;
                ReactionsRule rule = new ReactionsRule();
                rule.enabled = r.optBoolean("enabled", false);
                rule.whitelistMode = r.optBoolean("whitelist", true);
                rule.emoji = r.optString("emoji", "");
                rule.minCount = r.optInt("minCount", 0);
                rule.emoji2 = r.optString("emoji2", "");
                rule.maxCount = r.optInt("maxCount", 0);
                rule.maxDepth = r.optInt("maxDepth", 0);
                try {
                    result.put(Long.parseLong(key), rule);
                } catch (NumberFormatException ignored) {}
            }
        } catch (JSONException e) {
            module.log(Log.WARN, TAG, "Failed to parse reactions rules JSON: " + e.getMessage());
        }
        return result;
    }

    // ═════════════════════════════════════════════
    // 内部反序列化
    // ═════════════════════════════════════════════

    private List<RuleSet> parseRuleSetsJson(String raw) {
        List<RuleSet> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id", "");
                String name = obj.optString("name", "");
                boolean enabled = obj.optBoolean("enabled", true);
                boolean useRegex = obj.optBoolean("use_regex", false);

                Set<String> keywords = new HashSet<>();
                JSONArray kwArr = obj.optJSONArray("keywords");
                if (kwArr != null) {
                    for (int j = 0; j < kwArr.length(); j++) {
                        String kw = kwArr.getString(j).trim();
                        if (!kw.isEmpty()) keywords.add(kw);
                    }
                }

                if (!id.isEmpty()) {
                    result.add(new RuleSet(id, name, enabled, useRegex, keywords));
                }
            }
        } catch (JSONException e) {
            module.log(Log.WARN, TAG, "Failed to parse rule sets JSON: " + e.getMessage());
        }
        return result;
    }

    private Map<String, Set<Long>> parseRuleSetChannelsJson(String raw) {
        Map<String, Set<Long>> result = new HashMap<>();
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String ruleSetId = keys.next();
                JSONArray arr = obj.getJSONArray(ruleSetId);
                Set<Long> dialogIds = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    dialogIds.add(arr.getLong(i));
                }
                result.put(ruleSetId, dialogIds);
            }
        } catch (JSONException e) {
            module.log(Log.WARN, TAG, "Failed to parse rule set channels JSON: " + e.getMessage());
        }
        return result;
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

    // ═════════════════════════════════════════════
    // 规则集数据模型
    // ═════════════════════════════════════════════

    public static class RuleSet {
        public final String id;
        public final String name;
        public final boolean enabled;
        public final boolean useRegex;
        public final Set<String> keywords;

        public RuleSet(String id, String name, boolean enabled, boolean useRegex, Set<String> keywords) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.useRegex = useRegex;
            this.keywords = keywords;
        }
    }
}
