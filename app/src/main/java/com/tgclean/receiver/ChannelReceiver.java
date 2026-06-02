package com.tgclean.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 接收 Hook 端发来的频道发现广播
 *
 * Hook 端（Telegram 进程）在 ChatActivity.onResume 时发送 component-explicit broadcast，
 * 本 receiver 接收后将频道信息存入本地 SharedPreferences。
 *
 * 使用 component-explicit broadcast 可以绕过 Android 11+ package visibility 限制，
 * 因为 AMS 内部使用 MATCH_ALL 查询，不受应用层可见性约束。
 */
public class ChannelReceiver extends BroadcastReceiver {

    private static final String TAG = "TGClean-ChannelReceiver";

    public static final String ACTION_CHANNEL_DISCOVERED =
            "com.tgclean.ACTION_CHANNEL_DISCOVERED";

    // Intent extras
    public static final String EXTRA_DIALOG_ID = "dialog_id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_LAST_SEEN = "last_seen";

    // SharedPreferences
    private static final String PREFS_NAME = "discovered_channels";
    private static final String KEY_CHANNELS = "channels_json";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CHANNEL_DISCOVERED.equals(intent.getAction())) return;

        try {
            long dialogId = intent.getLongExtra(EXTRA_DIALOG_ID, 0);
            String name = intent.getStringExtra(EXTRA_NAME);
            long lastSeen = intent.getLongExtra(EXTRA_LAST_SEEN, 0);

            if (dialogId == 0) {
                Log.w(TAG, "Received broadcast with dialogId=0, ignoring");
                return;
            }

            Log.i(TAG, "Received channel: " + dialogId + " (" + name + ")");

            // UPSERT into SharedPreferences JSON
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_CHANNELS, "{}");
            JSONObject channels = new JSONObject(json);

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

    /**
     * 读取所有已发现频道（静态方法，供 UI 层调用）
     * 返回 JSON 字符串，由调用方解析
     */
    public static String getChannelsJson(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_CHANNELS, "{}");
    }
}
