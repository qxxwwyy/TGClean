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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/**
 * TGClean Material You 设置界面
 *
 * 全局设置 + 频道管理（手动添加频道ID → 配置关键词/白名单）
 * 所有写操作通过 XposedService.getRemotePreferences().edit() 完成。
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
    private MaterialButton btnAddChannel;
    private MaterialButton btnAddWhitelist;
    private TextView textWhitelistCount;

    private SharedPreferences remotePrefs = null;
    private boolean localEnabled = true;
    private boolean localRegex = false;
    private boolean localReactionsEnabled = false;
    private String localKeywords = "";
    private String localReactionsEmoji = "👎";
    private int localReactionsThreshold = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

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
        // 从频道详情页返回时刷新频道列表
        if (remotePrefs != null) {
            refreshChannelList();
        }
    }

    private void initViews() {
        switchEnabled = findViewById(R.id.switch_enabled);
        switchRegex = findViewById(R.id.switch_regex);
        switchReactions = findViewById(R.id.switch_reactions);
        editKeywords = findViewById(R.id.edit_keywords);
        editReactionsEmoji = findViewById(R.id.edit_reactions_emoji);
        editReactionsThreshold = findViewById(R.id.edit_reactions_threshold);
        btnSave = findViewById(R.id.btn_save);

        // 隐藏旧的频道规则/白名单文本框
        hideLegacyViews();

        // 频道管理 RecyclerView
        recyclerViewChannels = findViewById(R.id.recycler_channels);
        recyclerViewChannels.setLayoutManager(new LinearLayoutManager(this));
        channelAdapter = new ChannelListAdapter();
        recyclerViewChannels.setAdapter(channelAdapter);

        btnAddChannel = findViewById(R.id.btn_add_channel);
        btnAddWhitelist = findViewById(R.id.btn_add_whitelist);
        textWhitelistCount = findViewById(R.id.text_whitelist_count);
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

    private void onServiceReady(XposedService service) {
        Log.i(TAG, "XposedService ready, loading config");
        runOnUiThread(() -> {
            if (layoutWaiting != null) layoutWaiting.setVisibility(View.GONE);

            try {
                remotePrefs = service.getRemotePreferences(PREFS_NAME);
                localEnabled = remotePrefs.getBoolean("filter_enabled", true);
                localRegex = remotePrefs.getBoolean("use_regex", false);
                localReactionsEnabled = remotePrefs.getBoolean("reactions_filter_enabled", false);
                localKeywords = remotePrefs.getString("global_keywords", "");
                localReactionsEmoji = remotePrefs.getString("reactions_filter_emoji", "👎");
                localReactionsThreshold = remotePrefs.getInt("reactions_filter_threshold", 10);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load remote prefs", t);
            }

            switchEnabled.setChecked(localEnabled);
            switchRegex.setChecked(localRegex);
            switchReactions.setChecked(localReactionsEnabled);
            editKeywords.setText(localKeywords);
            editReactionsEmoji.setText(localReactionsEmoji);
            editReactionsThreshold.setText(String.valueOf(localReactionsThreshold));

            refreshChannelList();
        });
    }

    private void refreshChannelList() {
        List<ChannelInfo> channels = loadChannelList();
        channelAdapter.submitList(channels);

        Set<Long> whitelist = loadWhitelist();
        int wlCount = whitelist.size();
        if (textWhitelistCount != null) {
            textWhitelistCount.setText(wlCount > 0
                    ? wlCount + " 个频道在白名单中"
                    : "暂无白名单频道");
        }
    }

    private List<ChannelInfo> loadChannelList() {
        List<ChannelInfo> channels = new ArrayList<>();
        if (remotePrefs == null) return channels;

        try {
            String raw = remotePrefs.getString("channel_rules", "");
            if (raw != null && raw.trim().startsWith("{")) {
                JSONObject json = new JSONObject(raw);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    long id = Long.parseLong(key);
                    JSONArray arr = json.getJSONArray(key);
                    Set<String> keywords = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String kw = arr.getString(i).trim();
                        if (!kw.isEmpty()) keywords.add(kw);
                    }
                    channels.add(new ChannelInfo(id, keywords));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load channel list", e);
        }
        return channels;
    }

    private Set<Long> loadWhitelist() {
        Set<Long> whitelist = new HashSet<>();
        if (remotePrefs == null) return whitelist;
        try {
            String raw = remotePrefs.getString("whitelist", "");
            if (raw != null && raw.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    whitelist.add(arr.getLong(i));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load whitelist", e);
        }
        return whitelist;
    }

    private void setupListeners() {
        btnSave.setOnClickListener(v -> saveSettings());
        if (btnAddChannel != null) {
            btnAddChannel.setOnClickListener(v -> showAddChannelDialog());
        }
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
        String text = editKeywords.getText().toString();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) keywords.add(trimmed);
        }
        writer.setGlobalKeywords(keywords);

        writer.setReactionsFilterEmoji(editReactionsEmoji.getText().toString());
        try {
            int threshold = Integer.parseInt(editReactionsThreshold.getText().toString());
            writer.setReactionsFilterThreshold(threshold);
        } catch (NumberFormatException e) {
            writer.setReactionsFilterThreshold(10);
        }

        Snackbar.make(findViewById(android.R.id.content),
                getString(R.string.config_saved), Snackbar.LENGTH_SHORT).show();
    }

    // ═════════════════════════════════════════════
    // 添加频道对话框
    // ═════════════════════════════════════════════

    private void showAddChannelDialog() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_channel, null);
        TextInputEditText editId = dialogView.findViewById(R.id.edit_channel_id);

        new MaterialAlertDialogBuilder(this)
                .setTitle("添加频道")
                .setView(dialogView)
                .setPositiveButton("添加", (d, which) -> {
                    String input = editId.getText().toString().trim();
                    if (input.isEmpty()) {
                        Snackbar.make(findViewById(android.R.id.content),
                                "请输入频道ID", Snackbar.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        // 支持粘贴 "复制聊天ID (-1001234567890)" 格式
                        long id = Long.parseLong(input.replaceAll("[^0-9-]", ""));
                        if (id == 0) throw new NumberFormatException();

                        // 创建空规则（用户进入详情页后添加关键词）
                        FilterConfigWriter writer = new FilterConfigWriter(remotePrefs);
                        Map<Long, Set<String>> rules = new HashMap<>();
                        // 保留已有规则
                        List<ChannelInfo> existing = loadChannelList();
                        for (ChannelInfo ci : existing) {
                            rules.put(ci.id, ci.keywords);
                        }
                        rules.putIfAbsent(id, new HashSet<>());
                        writer.setChannelRules(rules);

                        refreshChannelList();
                        Snackbar.make(findViewById(android.R.id.content),
                                "已添加频道: " + id, Snackbar.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Snackbar.make(findViewById(android.R.id.content),
                                "无效的频道ID", Snackbar.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
                        Set<Long> wl = loadWhitelist();
                        wl.add(id);
                        writer.setWhitelist(wl);

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
        final Set<String> keywords;
        boolean whitelisted;

        ChannelInfo(long id, Set<String> keywords) {
            this.id = id;
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
            holder.textChannelId.setText(String.valueOf(info.id));
            holder.textKeywordCount.setText(info.keywords.size() + " 条关键词");

            // 判断是否在白名单
            Set<Long> whitelist = loadWhitelist();
            info.whitelisted = whitelist.contains(info.id);
            holder.textWhitelist.setVisibility(info.whitelisted ? View.VISIBLE : View.GONE);

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, ChannelDetailActivity.class);
                intent.putExtra(ChannelDetailActivity.EXTRA_DIALOG_ID, info.id);
                startActivity(intent);
            });

            holder.btnDelete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(SettingsActivity.this)
                        .setTitle("删除频道规则")
                        .setMessage("确定删除频道 " + info.id + " 的过滤规则？")
                        .setPositiveButton("删除", (d, which) -> {
                            FilterConfigWriter writer = new FilterConfigWriter(remotePrefs);
                            writer.removeChannelRule(info.id);
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
}
