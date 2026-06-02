package com.tgclean.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.tgclean.App;
import com.tgclean.R;
import com.tgclean.config.FilterConfigWriter;
import com.tgclean.receiver.ChannelReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/**
 * TGClean 设置界面
 *
 * 频道列表从 SharedPreferences 自动获取（Hook 端通过 BroadcastReceiver 发送发现数据）。
 * 全局设置 + 频道管理 + 白名单。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "TGClean-Settings";
    private static final String PREFS_NAME = "tgclean_config";

    // ─── 全局设置控件 ───
    private MaterialSwitch switchEnabled;
    private MaterialSwitch switchRegex;
    private MaterialSwitch switchReactions;
    private TextInputEditText editKeywords;
    private TextInputEditText editReactionsEmoji;
    private TextInputEditText editReactionsThreshold;
    private MaterialButton btnSave;
    private LinearLayout layoutWaiting;
    private TextView textWaiting;

    // ─── 频道管理 ───
    private RecyclerView recyclerViewChannels;
    private ChannelListAdapter channelAdapter;
    private MaterialButton btnAddWhitelist;
    private TextView textWhitelistCount;
    private TextView textChannelCount;

    private SharedPreferences remotePrefs = null;
    private SharedPreferences discoveredPrefs = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        discoveredPrefs = getSharedPreferences("discovered_channels", Context.MODE_PRIVATE);

        initViews();
        setupListeners();

        layoutWaiting = findViewById(R.id.layout_waiting);
        textWaiting = findViewById(R.id.text_waiting);
        if (layoutWaiting != null && textWaiting != null) {
            layoutWaiting.setVisibility(View.VISIBLE);
            textWaiting.setText("正在连接 XposedService...");
        }

        App.addServiceReadyListener(serviceReadyListener);
    }

    private final App.ServiceReadyListener serviceReadyListener = this::onServiceReady;

    @Override
    protected void onDestroy() {
        App.removeServiceReadyListener(serviceReadyListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChannelList();
        refreshWhitelistCount();
    }

    private void initViews() {
        switchEnabled = findViewById(R.id.switch_enabled);
        switchRegex = findViewById(R.id.switch_regex);
        switchReactions = findViewById(R.id.switch_reactions);
        editKeywords = findViewById(R.id.edit_keywords);
        editReactionsEmoji = findViewById(R.id.edit_reactions_emoji);
        editReactionsThreshold = findViewById(R.id.edit_reactions_threshold);
        btnSave = findViewById(R.id.btn_save);

        hideLegacyViews();

        recyclerViewChannels = findViewById(R.id.recycler_channels);
        recyclerViewChannels.setLayoutManager(new LinearLayoutManager(this));
        channelAdapter = new ChannelListAdapter();
        recyclerViewChannels.setAdapter(channelAdapter);

        btnAddWhitelist = findViewById(R.id.btn_add_whitelist);
        textWhitelistCount = findViewById(R.id.text_whitelist_count);
        textChannelCount = findViewById(R.id.text_channel_count);
    }

    private void hideLegacyViews() {
        View channelSection = findViewById(R.id.edit_channel_rules);
        if (channelSection != null) {
            View parent = (View) channelSection.getParent();
            if (parent instanceof LinearLayout) ((LinearLayout) parent).setVisibility(View.GONE);
        }
        View pasteBtn = findViewById(R.id.btn_paste_id);
        if (pasteBtn != null) pasteBtn.setVisibility(View.GONE);
    }

    // ═════════════════════════════════════════════
    // 频道列表加载（从 SharedPreferences）
    // ═════════════════════════════════════════════

    private void refreshChannelList() {
        List<ChannelInfo> channels = new ArrayList<>();

        try {
            String json = ChannelReceiver.getChannelsJson(this);
            JSONObject obj = new JSONObject(json);

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    long dialogId = Long.parseLong(key);
                    JSONObject ch = obj.getJSONObject(key);
                    String name = ch.optString("name", String.valueOf(dialogId));
                    long lastSeen = ch.optLong("last_seen", 0);

                    Set<String> keywords = getChannelKeywords(dialogId);
                    channels.add(new ChannelInfo(dialogId, name, lastSeen, keywords));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load discovered channels", e);
        }

        // 按 lastSeen 倒序
        channels.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));

        if (textChannelCount != null) {
            textChannelCount.setText("已发现 " + channels.size() + " 个频道");
        }

        channelAdapter.submitList(channels);
    }

    // ═════════════════════════════════════════════
    // XposedService
    // ═════════════════════════════════════════════

    private void onServiceReady(XposedService service) {
        Log.i(TAG, "XposedService ready");
        runOnUiThread(() -> {
            if (layoutWaiting != null) layoutWaiting.setVisibility(View.GONE);

            try {
                remotePrefs = service.getRemotePreferences(PREFS_NAME);
                switchEnabled.setChecked(remotePrefs.getBoolean("filter_enabled", true));
                switchRegex.setChecked(remotePrefs.getBoolean("use_regex", false));
                switchReactions.setChecked(remotePrefs.getBoolean("reactions_filter_enabled", false));
                editKeywords.setText(remotePrefs.getString("global_keywords", ""));
                editReactionsEmoji.setText(remotePrefs.getString("reactions_filter_emoji", "👎"));
                editReactionsThreshold.setText(String.valueOf(
                        remotePrefs.getInt("reactions_filter_threshold", 10)));
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load prefs", t);
            }

            refreshWhitelistCount();
            refreshChannelList();
        });
    }

    private void refreshWhitelistCount() {
        Set<Long> wl = getWhitelist();
        if (textWhitelistCount != null) {
            textWhitelistCount.setText(wl.size() > 0
                    ? wl.size() + " 个频道在白名单中"
                    : "暂无白名单频道");
        }
    }

    // ═════════════════════════════════════════════
    // 配置读写
    // ═════════════════════════════════════════════

    private Set<String> getChannelKeywords(long dialogId) {
        Set<String> keywords = new HashSet<>();
        if (remotePrefs == null) return keywords;
        try {
            String raw = remotePrefs.getString("channel_rules", "");
            if (raw != null && raw.trim().startsWith("{")) {
                JSONObject json = new JSONObject(raw);
                String key = String.valueOf(dialogId);
                if (json.has(key)) {
                    JSONArray arr = json.getJSONArray(key);
                    for (int i = 0; i < arr.length(); i++) {
                        String kw = arr.getString(i).trim();
                        if (!kw.isEmpty()) keywords.add(kw);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load keywords for " + dialogId, e);
        }
        return keywords;
    }

    private Set<Long> getWhitelist() {
        Set<Long> whitelist = new HashSet<>();
        if (remotePrefs == null) return whitelist;
        try {
            String raw = remotePrefs.getString("whitelist", "");
            if (raw != null && raw.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) whitelist.add(arr.getLong(i));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load whitelist", e);
        }
        return whitelist;
    }

    private void setupListeners() {
        btnSave.setOnClickListener(v -> saveSettings());
        if (btnAddWhitelist != null) {
            btnAddWhitelist.setOnClickListener(v -> showAddWhitelistDialog());
        }
    }

    private void saveSettings() {
        if (remotePrefs == null) {
            Snackbar.make(findViewById(android.R.id.content),
                    "XposedService 未就绪", Snackbar.LENGTH_LONG).show();
            return;
        }

        FilterConfigWriter writer = new FilterConfigWriter(remotePrefs);
        writer.setEnabled(switchEnabled.isChecked());
        writer.setUseRegex(switchRegex.isChecked());
        writer.setReactionsFilterEnabled(switchReactions.isChecked());

        Set<String> keywords = new HashSet<>();
        for (String line : editKeywords.getText().toString().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) keywords.add(trimmed);
        }
        writer.setGlobalKeywords(keywords);

        writer.setReactionsFilterEmoji(editReactionsEmoji.getText().toString());
        try {
            writer.setReactionsFilterThreshold(
                    Integer.parseInt(editReactionsThreshold.getText().toString()));
        } catch (NumberFormatException e) {
            writer.setReactionsFilterThreshold(10);
        }

        Snackbar.make(findViewById(android.R.id.content),
                getString(R.string.config_saved), Snackbar.LENGTH_SHORT).show();
    }

    private void showAddWhitelistDialog() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_channel, null);
        TextInputEditText editId = dialogView.findViewById(R.id.edit_channel_id);

        new MaterialAlertDialogBuilder(this)
                .setTitle("添加白名单频道")
                .setMessage("输入频道ID，该频道不会被过滤")
                .setView(dialogView)
                .setPositiveButton("添加", (d, which) -> {
                    String input = editId.getText().toString().trim();
                    if (input.isEmpty()) return;
                    try {
                        long id = Long.parseLong(input.replaceAll("[^0-9-]", ""));
                        if (id == 0) throw new NumberFormatException();

                        FilterConfigWriter writer = new FilterConfigWriter(remotePrefs);
                        Set<Long> wl = getWhitelist();
                        wl.add(id);
                        writer.setWhitelist(wl);

                        refreshWhitelistCount();
                        refreshChannelList();
                        Snackbar.make(findViewById(android.R.id.content),
                                "已加入白名单: " + id, Snackbar.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Snackbar.make(findViewById(android.R.id.content),
                                "无效的频道ID", Snackbar.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═════════════════════════════════════════════
    // 频道列表 Adapter
    // ═════════════════════════════════════════════

    static class ChannelInfo {
        final long id;
        final String name;
        final long lastSeen;
        final Set<String> keywords;
        boolean whitelisted;

        ChannelInfo(long id, String name, long lastSeen, Set<String> keywords) {
            this.id = id;
            this.name = name;
            this.lastSeen = lastSeen;
            this.keywords = keywords;
        }
    }

    class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.ViewHolder> {
        private final List<ChannelInfo> items = new ArrayList<>();

        void submitList(List<ChannelInfo> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ChannelInfo info = items.get(position);
            holder.textChannelId.setText(info.name != null ? info.name : String.valueOf(info.id));
            holder.textKeywordCount.setText(info.keywords.size() > 0
                    ? info.keywords.size() + " 条关键词"
                    : "未配置规则");

            Set<Long> whitelist = getWhitelist();
            info.whitelisted = whitelist.contains(info.id);
            holder.textWhitelist.setVisibility(info.whitelisted ? View.VISIBLE : View.GONE);

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, ChannelDetailActivity.class);
                intent.putExtra(ChannelDetailActivity.EXTRA_DIALOG_ID, info.id);
                intent.putExtra(ChannelDetailActivity.EXTRA_CHANNEL_NAME, info.name != null ? info.name : "");
                startActivity(intent);
            });

            // 长按删除发现记录（不影响过滤规则）
            holder.btnDelete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(SettingsActivity.this)
                        .setTitle("删除频道")
                        .setMessage("从发现列表中移除「" + (info.name != null ? info.name : info.id) + "」？\n（不会删除已配置的过滤规则）")
                        .setPositiveButton("移除", (d, which) -> {
                            removeDiscoveredChannel(info.id);
                            refreshChannelList();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textChannelId;
            TextView textKeywordCount;
            TextView textWhitelist;
            View btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                textChannelId = itemView.findViewById(R.id.text_channel_id);
                textKeywordCount = itemView.findViewById(R.id.text_keyword_count);
                textWhitelist = itemView.findViewById(R.id.text_whitelist_badge);
                btnDelete = itemView.findViewById(R.id.btn_delete_channel);
            }
        }
    }

    // ═════════════════════════════════════════════
    // 发现频道删除
    // ═════════════════════════════════════════════

    private void removeDiscoveredChannel(long dialogId) {
        try {
            SharedPreferences.Editor editor = discoveredPrefs.edit();
            String json = discoveredPrefs.getString("channels_json", "{}");
            JSONObject channels = new JSONObject(json);
            channels.remove(String.valueOf(dialogId));
            editor.putString("channels_json", channels.toString());
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove discovered channel", e);
        }
    }
}
