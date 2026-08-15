package com.tgclean.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.tgclean.RemoteConfigStore;
import com.tgclean.config.FilterConfigWriter;
import com.tgclean.config.ReactionsRule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 接收 Hook 端（Telegram 进程）发来的广播，是 TG → App 的单向桥：
 *
 * 1. com.tgclean.ACTION_CHANNEL_DISCOVERED — 频道发现
 *    a) 单频道：dialog_id / name / last_seen extras（增量上报）
 *    b) 批量：batch_json extras（首次全量扫描，单个广播携带全部频道）
 *    存入本地 SharedPreferences（discovered_channels）。
 *
 * 2. com.tgclean.ACTION_REACTIONS_RULE — 每频道表情过滤规则保存请求
 *    （hook 端 remote prefs 只读，写入必须经 App 进程）
 *    extras: dialog_id / enabled / whitelist / emoji / min_count / emoji2 / max_count
 *    经 RemoteConfigStore 写入 remote prefs；服务未就绪时排队等待。
 *
 * 使用 component-explicit broadcast 可以绕过 Android 11+ package visibility 限制，
 * 因为 AMS 内部使用 MATCH_ALL 查询，不受应用层可见性约束。
 */
public class ChannelReceiver extends BroadcastReceiver {

    private static final String TAG = "TGClean-ChannelReceiver";

    public static final String ACTION_CHANNEL_DISCOVERED =
            "com.tgclean.ACTION_CHANNEL_DISCOVERED";

    public static final String ACTION_REACTIONS_RULE =
            "com.tgclean.ACTION_REACTIONS_RULE";

    // Intent extras
    public static final String EXTRA_DIALOG_ID = "dialog_id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_LAST_SEEN = "last_seen";
    public static final String EXTRA_BATCH_JSON = "batch_json";

    // SharedPreferences
    private static final String PREFS_NAME = "discovered_channels";
    private static final String KEY_CHANNELS = "channels_json";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_REACTIONS_RULE.equals(action)) {
            handleReactionsRule(context, intent);
            return;
        }
        if (!ACTION_CHANNEL_DISCOVERED.equals(action)) return;

        String batchJson = intent.getStringExtra(EXTRA_BATCH_JSON);
        if (batchJson != null) {
            handleBatch(context, batchJson);
            return;
        }

        handleSingle(context, intent);
    }

    public static final String ACTION_REACTIONS_RULE_SAVED =
            "com.tgclean.ACTION_REACTIONS_RULE_SAVED";

    private static final String TG_PACKAGE = "org.telegram.messenger";

    /**
     * 表情过滤规则保存：写入 remote prefs（经 RemoteConfigStore，
     * XposedService 未就绪时排队）。写入成功后向 TG 进程回发确认广播。
     */
    private void handleReactionsRule(Context context, Intent intent) {
        long dialogId = intent.getLongExtra(EXTRA_DIALOG_ID, 0);
        if (dialogId == 0) return;

        ReactionsRule rule = new ReactionsRule();
        rule.enabled = intent.getBooleanExtra("enabled", false);
        rule.whitelistMode = intent.getBooleanExtra("whitelist", true);
        rule.emoji = intent.getStringExtra("emoji");
        rule.minCount = intent.getIntExtra("min_count", 0);
        rule.emoji2 = intent.getStringExtra("emoji2");
        rule.maxCount = intent.getIntExtra("max_count", 0);
        if (rule.emoji == null) rule.emoji = "";
        if (rule.emoji2 == null) rule.emoji2 = "";

        RemoteConfigStore.submit(svc -> {
            FilterConfigWriter writer = new FilterConfigWriter(
                    svc.getRemotePreferences(FilterConfigWriter.PREFS_NAME));
            writer.setReactionsRule(dialogId, rule);
            // 写入落地后回执，TG 侧据此提示真实结果
            Intent reply = new Intent(ACTION_REACTIONS_RULE_SAVED);
            reply.setPackage(TG_PACKAGE);
            reply.putExtra("dialog_id", dialogId);
            context.sendBroadcast(reply);
        });
        Log.i(TAG, "Reactions rule queued: dialog=" + dialogId
                + " enabled=" + rule.enabled + " (" + rule.describe() + ")");
    }

    /** 批量模式：单个广播携带全部频道 */
    private void handleBatch(Context context, String batchJson) {
        try {
            JSONArray batch = new JSONArray(batchJson);
            if (batch.length() == 0) return;

            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONObject channels = readChannels(sp);

            int merged = 0;
            for (int i = 0; i < batch.length(); i++) {
                JSONObject item = batch.getJSONObject(i);
                long dialogId = item.optLong("id", 0);
                if (dialogId == 0) continue;

                JSONObject ch = new JSONObject();
                ch.put("name", item.optString("name", String.valueOf(dialogId)));
                ch.put("last_seen", item.optLong("last_seen", 0));
                channels.put(String.valueOf(dialogId), ch);
                merged++;
            }

            sp.edit().putString(KEY_CHANNELS, channels.toString()).apply();
            Log.i(TAG, "Batch received: " + merged + " channels, total: " + channels.length());

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse batch channel data", e);
        } catch (Throwable t) {
            Log.e(TAG, "Error in handleBatch", t);
        }
    }

    /** 单频道模式：增量上报 */
    private void handleSingle(Context context, Intent intent) {
        try {
            long dialogId = intent.getLongExtra(EXTRA_DIALOG_ID, 0);
            String name = intent.getStringExtra(EXTRA_NAME);
            long lastSeen = intent.getLongExtra(EXTRA_LAST_SEEN, 0);

            if (dialogId == 0) {
                Log.w(TAG, "Received broadcast with dialogId=0, ignoring");
                return;
            }

            Log.i(TAG, "Received channel: " + dialogId + " (" + name + ")");

            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONObject channels = readChannels(sp);

            JSONObject ch = new JSONObject();
            ch.put("name", name != null ? name : String.valueOf(dialogId));
            ch.put("last_seen", lastSeen);
            channels.put(String.valueOf(dialogId), ch);

            sp.edit().putString(KEY_CHANNELS, channels.toString()).apply();

            Log.i(TAG, "Saved channel to prefs. Total: " + channels.length());

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse/store channel data", e);
        } catch (Throwable t) {
            Log.e(TAG, "Error in onReceive", t);
        }
    }

    private static JSONObject readChannels(SharedPreferences sp) throws JSONException {
        String json = sp.getString(KEY_CHANNELS, "{}");
        return new JSONObject(json);
    }

    /**
     * 读取所有已发现频道（静态方法，供 UI 层调用）
     * 返回 JSON 字符串，由调用方解析
     */
    public static String getChannelsJson(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_CHANNELS, "{}");
    }
}
