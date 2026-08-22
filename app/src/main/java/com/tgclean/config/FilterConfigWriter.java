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

    /** remote prefs 组名，与 hook 端 FilterConfig.PREFS_NAME 一致 */
    public static final String PREFS_NAME = "tgclean_config";

    private static final String KEY_ENABLED = "filter_enabled";
    private static final String KEY_USE_REGEX = "use_regex";
    private static final String KEY_CHANNEL_RULES = "channel_rules"; // legacy
    private static final String KEY_REACTIONS_RULES = "reactions_channel_rules";
    private static final String KEY_REACTIONS_DEPTH = "reactions_search_depth";
    private static final String KEY_DEBUG_LOG = "debug_log";
    private static final String KEY_PAIRING_TOKEN = "pairing_token";
    // 注：频道发现数据存于 App 本地 prefs（ChannelReceiver 管理），不在本 remote prefs 中
    private static final String KEY_RULE_SETS = "rule_sets";
    private static final String KEY_RULE_SET_CHANNELS = "rule_set_channels";
    private static final String KEY_MIGRATED_LEGACY = "migrated_legacy_v2";

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
    // 规则集 CRUD
    // ═════════════════════════════════════════════

    public List<RuleSetData> getRuleSets() {
        String raw = prefs.getString(KEY_RULE_SETS, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("[")) {
            return new ArrayList<>();
        }
        return parseRuleSetsJson(raw);
    }

    public void saveRuleSets(List<RuleSetData> ruleSets) {
        if (ruleSets == null || ruleSets.isEmpty()) {
            prefs.edit().putString(KEY_RULE_SETS, "").apply();
            return;
        }
        JSONArray arr = new JSONArray();
        for (RuleSetData rs : ruleSets) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", rs.id);
                obj.put("name", rs.name);
                obj.put("enabled", rs.enabled);
                obj.put("use_regex", rs.useRegex);
                JSONArray kwArr = new JSONArray();
                for (String kw : rs.keywords) kwArr.put(kw);
                obj.put("keywords", kwArr);
                arr.put(obj);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to serialize rule set: " + rs.id, e);
            }
        }
        prefs.edit().putString(KEY_RULE_SETS, arr.toString()).apply();
    }

    public void addRuleSet(RuleSetData ruleSet) {
        List<RuleSetData> sets = getRuleSets();
        sets.add(ruleSet);
        saveRuleSets(sets);
    }

    public void updateRuleSet(RuleSetData ruleSet) {
        List<RuleSetData> sets = getRuleSets();
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).id.equals(ruleSet.id)) {
                sets.set(i, ruleSet);
                break;
            }
        }
        saveRuleSets(sets);
    }

    public void deleteRuleSet(String ruleSetId) {
        List<RuleSetData> sets = getRuleSets();
        sets.removeIf(rs -> rs.id.equals(ruleSetId));
        saveRuleSets(sets);

        // 同时清理频道映射
        removeRuleSetChannels(ruleSetId);
    }

    // ═════════════════════════════════════════════
    // 规则集 ↔ 频道映射
    // ═════════════════════════════════════════════

    public Map<String, Set<Long>> getRuleSetChannels() {
        String raw = prefs.getString(KEY_RULE_SET_CHANNELS, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("{")) {
            return new java.util.HashMap<>();
        }
        return parseRuleSetChannelsJson(raw);
    }

    /**
     * 获取 频道ID → 规则集列表 的反向映射
     */
    public Map<Long, List<RuleSetData>> getChannelRuleSets() {
        List<RuleSetData> allSets = getRuleSets();
        Map<String, Set<Long>> channelMap = getRuleSetChannels();
        Map<Long, List<RuleSetData>> result = new java.util.HashMap<>();

        for (Map.Entry<String, Set<Long>> entry : channelMap.entrySet()) {
            RuleSetData rs = findRuleSet(allSets, entry.getKey());
            if (rs == null) continue;
            for (Long dialogId : entry.getValue()) {
                result.computeIfAbsent(dialogId, k -> new ArrayList<>()).add(rs);
            }
        }
        return result;
    }

    public void setRuleSetChannels(String ruleSetId, Set<Long> dialogIds) {
        Map<String, Set<Long>> map = getRuleSetChannels();
        if (dialogIds == null || dialogIds.isEmpty()) {
            map.remove(ruleSetId);
        } else {
            map.put(ruleSetId, dialogIds);
        }
        saveRuleSetChannels(map);
    }

    public void addChannelToRuleSet(String ruleSetId, long dialogId) {
        Map<String, Set<Long>> map = getRuleSetChannels();
        map.computeIfAbsent(ruleSetId, k -> new HashSet<>()).add(dialogId);
        saveRuleSetChannels(map);
    }

    public void removeChannelFromRuleSet(String ruleSetId, long dialogId) {
        Map<String, Set<Long>> map = getRuleSetChannels();
        Set<Long> ids = map.get(ruleSetId);
        if (ids != null) {
            ids.remove(dialogId);
            if (ids.isEmpty()) map.remove(ruleSetId);
            saveRuleSetChannels(map);
        }
    }

    private void removeRuleSetChannels(String ruleSetId) {
        Map<String, Set<Long>> map = getRuleSetChannels();
        map.remove(ruleSetId);
        saveRuleSetChannels(map);
    }

    private void saveRuleSetChannels(Map<String, Set<Long>> map) {
        if (map.isEmpty()) {
            prefs.edit().putString(KEY_RULE_SET_CHANNELS, "").apply();
            return;
        }
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Set<Long>> entry : map.entrySet()) {
            JSONArray arr = new JSONArray();
            for (Long id : entry.getValue()) arr.put(id);
            try { obj.put(entry.getKey(), arr); } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_RULE_SET_CHANNELS, obj.toString()).apply();
    }

    private RuleSetData findRuleSet(List<RuleSetData> sets, String id) {
        for (RuleSetData rs : sets) {
            if (rs.id.equals(id)) return rs;
        }
        return null;
    }

    // ═════════════════════════════════════════════
    // 旧版数据迁移
    // ═════════════════════════════════════════════

    /**
     * 将旧版 channel_rules（{dialogId: [keywords]}）迁移为规则集。
     * 每个有关键词的频道创建一个独立规则集。
     * 返回 true 如果执行了迁移。
     */
    public boolean migrateLegacyIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_LEGACY, false)) return false;

        String raw = prefs.getString(KEY_CHANNEL_RULES, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("{")) {
            // 没有旧数据，标记已迁移
            prefs.edit().putBoolean(KEY_MIGRATED_LEGACY, true).apply();
            return false;
        }

        try {
            JSONObject json = new JSONObject(raw);
            List<RuleSetData> ruleSets = getRuleSets();
            Map<String, Set<Long>> channelMap = getRuleSetChannels();
            long now = System.currentTimeMillis();

            Iterator<String> keys = json.keys();
            int migrated = 0;
            while (keys.hasNext()) {
                String dialogIdStr = keys.next();
                JSONArray kwArr = json.getJSONArray(dialogIdStr);
                Set<String> keywords = new HashSet<>();
                for (int i = 0; i < kwArr.length(); i++) {
                    String kw = kwArr.getString(i).trim();
                    if (!kw.isEmpty()) keywords.add(kw);
                }
                if (keywords.isEmpty()) continue;

                long dialogId = Long.parseLong(dialogIdStr);
                String rsId = "rs_migrated_" + dialogId + "_" + (migrated++);
                String rsName = "迁移-频道" + dialogId;

                RuleSetData rs = new RuleSetData(rsId, rsName, true, false, keywords);
                ruleSets.add(rs);
                channelMap.computeIfAbsent(rsId, k -> new HashSet<>()).add(dialogId);
            }

            if (!ruleSets.isEmpty()) {
                saveRuleSets(ruleSets);
                saveRuleSetChannels(channelMap);
            }

            // 清除旧数据
            prefs.edit().remove(KEY_CHANNEL_RULES).putBoolean(KEY_MIGRATED_LEGACY, true).apply();
            Log.i(TAG, "Migrated " + migrated + " legacy channel rules to rule sets");
            return true;
        } catch (JSONException | NumberFormatException e) {
            Log.e(TAG, "Failed to migrate legacy rules", e);
            return false;
        }
    }

    // ═════════════════════════════════════════════
    // 每频道表情过滤规则
    // ═════════════════════════════════════════════

    public Map<Long, ReactionsRule> getReactionsRules() {
        Map<Long, ReactionsRule> result = new java.util.HashMap<>();
        String raw = prefs.getString(KEY_REACTIONS_RULES, "");
        if (raw == null || raw.isEmpty() || !raw.trim().startsWith("{")) return result;
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
            Log.e(TAG, "Failed to parse reactions rules", e);
        }
        return result;
    }

    /** 保存单个频道的规则（enabled=false 时保留配置以便再次启用） */
    public void setReactionsRule(long dialogId, ReactionsRule rule) {
        try {
            Map<Long, ReactionsRule> all = getReactionsRules();
            all.put(dialogId, rule);

            JSONObject obj = new JSONObject();
            for (Map.Entry<Long, ReactionsRule> e : all.entrySet()) {
                JSONObject r = new JSONObject();
                r.put("enabled", e.getValue().enabled);
                r.put("whitelist", e.getValue().whitelistMode);
                r.put("emoji", e.getValue().emoji != null ? e.getValue().emoji : "");
                r.put("minCount", e.getValue().minCount);
                r.put("emoji2", e.getValue().emoji2 != null ? e.getValue().emoji2 : "");
                r.put("maxCount", e.getValue().maxCount);
                r.put("maxDepth", e.getValue().maxDepth);
                obj.put(String.valueOf(e.getKey()), r);
            }
            prefs.edit().putString(KEY_REACTIONS_RULES, obj.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save reactions rule for " + dialogId, e);
        }
    }

    /**
     * 表情筛选全局默认检索深度（条）。每频道规则 maxDepth=0 时级联用它，
     * 关键词过滤触发的级联同样用它。框架按 key 实时推送到 TG 进程。
     */
    public int getReactionsSearchDepth() {
        int v = prefs.getInt(KEY_REACTIONS_DEPTH, ReactionsRule.DEFAULT_MAX_DEPTH);
        return v > 0 ? v : ReactionsRule.DEFAULT_MAX_DEPTH;
    }

    public void setReactionsSearchDepth(int depth) {
        prefs.edit().putInt(KEY_REACTIONS_DEPTH, depth).apply();
    }

    // ═════════════════════════════════════════════
    // 调试日志 / 写通道配对令牌
    // ═════════════════════════════════════════════

    /** 调试日志开关（默认关）：关时 hook 端不打逐条消息明细，保护用户通信内容 */
    public boolean isDebugLog() {
        return prefs.getBoolean(KEY_DEBUG_LOG, false);
    }

    public void setDebugLog(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEBUG_LOG, enabled).apply();
    }

    /**
     * 配对令牌：首次调用时生成并写入 remote prefs（框架数据目录，第三方
     * App 不可读）。TG 侧 hook 只读同一 key，随保存广播带回，App 端比对
     * 一致才落盘——防任意 App 伪造 intent 改写过滤规则（发布前审计 M-1）。
     *
     * @return [令牌, 是否本次新生成]；新生成且对端尚无令牌时按信任首次处理
     */
    public Object[] ensurePairingToken() {
        String existing = prefs.getString(KEY_PAIRING_TOKEN, "");
        if (existing != null && !existing.isEmpty()) {
            return new Object[]{existing, false};
        }
        String token = java.util.UUID.randomUUID().toString();
        prefs.edit().putString(KEY_PAIRING_TOKEN, token).apply();
        return new Object[]{token, true};
    }

    // ═════════════════════════════════════════════
    // 内部工具
    // ═════════════════════════════════════════════

    // ─── 解析 ───

    private List<RuleSetData> parseRuleSetsJson(String raw) {
        List<RuleSetData> result = new ArrayList<>();
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
                if (!id.isEmpty()) result.add(new RuleSetData(id, name, enabled, useRegex, keywords));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse rule sets", e);
        }
        return result;
    }

    private Map<String, Set<Long>> parseRuleSetChannelsJson(String raw) {
        Map<String, Set<Long>> result = new java.util.HashMap<>();
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String ruleSetId = keys.next();
                JSONArray arr = obj.getJSONArray(ruleSetId);
                Set<Long> ids = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) ids.add(arr.getLong(i));
                result.put(ruleSetId, ids);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse rule set channels", e);
        }
        return result;
    }

    // ═════════════════════════════════════════════
    // 规则集数据模型（App端可写）
    // ═════════════════════════════════════════════

    public static class RuleSetData {
        public String id;
        public String name;
        public boolean enabled;
        public boolean useRegex;
        public Set<String> keywords;

        public RuleSetData(String id, String name, boolean enabled, boolean useRegex, Set<String> keywords) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.useRegex = useRegex;
            this.keywords = keywords != null ? keywords : new HashSet<>();
        }

        /**
         * 生成新规则集 ID
         */
        public static String generateId() {
            return "rs_" + System.currentTimeMillis();
        }
    }
}
