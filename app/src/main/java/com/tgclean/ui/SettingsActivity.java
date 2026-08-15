package com.tgclean.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.tgclean.App;
import com.tgclean.R;
import com.tgclean.config.FilterConfigWriter;
import com.tgclean.config.ReactionsRule;
import com.tgclean.receiver.ChannelReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/**
 * TGClean 主设置界面
 *
 * 结构：
 * - 模块状态开关
 * - 规则集列表（CRUD）
 * - Reactions 过滤
 * - 发现的频道（汇总展示覆盖状态）
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "TGClean-Settings";
    private static final String PREFS_NAME = "tgclean_config";

    // ─── 控件 ───
    private MaterialSwitch switchEnabled;
    private RecyclerView recyclerRuleSets;
    private RuleSetAdapter ruleSetAdapter;
    private RecyclerView recyclerChannels;
    private ChannelSummaryAdapter channelAdapter;
    private TextInputEditText editChannelSearch;
    private View layoutWaiting;
    private View scrollContent;
    private TextView textChannelCount;
    private TextView textEmptyRules;
    private TextView rowSearchDepth;

    private SharedPreferences remotePrefs = null;
    private SharedPreferences discoveredPrefs = null;
    private FilterConfigWriter writer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        discoveredPrefs = getSharedPreferences("discovered_channels", Context.MODE_PRIVATE);

        initViews();
        setupListeners();

        layoutWaiting = findViewById(R.id.layout_waiting);
        scrollContent = findViewById(R.id.scroll_content);

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
        if (writer != null) {
            refreshRuleSets();
            refreshChannels();
        }
    }

    private void initViews() {
        switchEnabled = findViewById(R.id.switch_enabled);

        recyclerRuleSets = findViewById(R.id.recycler_rule_sets);
        recyclerRuleSets.setLayoutManager(new LinearLayoutManager(this));
        ruleSetAdapter = new RuleSetAdapter();
        recyclerRuleSets.setAdapter(ruleSetAdapter);

        textEmptyRules = findViewById(R.id.text_empty_rules);

        recyclerChannels = findViewById(R.id.recycler_channels);
        recyclerChannels.setLayoutManager(new LinearLayoutManager(this));
        channelAdapter = new ChannelSummaryAdapter();
        recyclerChannels.setAdapter(channelAdapter);

        editChannelSearch = findViewById(R.id.edit_channel_search);
        textChannelCount = findViewById(R.id.text_channel_count);

        rowSearchDepth = findViewById(R.id.row_search_depth);
        rowSearchDepth.setOnClickListener(v -> showSearchDepthDialog());

        // 频道搜索
        editChannelSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                refreshChannels(s.toString().trim());
            }
        });
    }

    private void setupListeners() {
        findViewById(R.id.btn_add_rule_set).setOnClickListener(v -> showCreateRuleSetDialog());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
    }

    // ═════════════════════════════════════════════
    // XposedService
    // ═════════════════════════════════════════════

    private void onServiceReady(XposedService service) {
        Log.i(TAG, "XposedService ready");
        runOnUiThread(() -> {
            if (layoutWaiting != null) layoutWaiting.setVisibility(View.GONE);
            if (scrollContent != null) scrollContent.setVisibility(View.VISIBLE);

            remotePrefs = service.getRemotePreferences(PREFS_NAME);
            writer = new FilterConfigWriter(remotePrefs);

            switchEnabled.setChecked(remotePrefs.getBoolean("filter_enabled", true));
            rowSearchDepth.setText("默认检索深度："
                    + ReactionsRule.formatDepth(writer.getReactionsSearchDepth()) + " 条");

            // 执行旧数据迁移
            boolean migrated = writer.migrateLegacyIfNeeded();
            if (migrated) {
                Snackbar.make(scrollContent, "已迁移旧版配置为规则集", Snackbar.LENGTH_LONG).show();
            }

            refreshRuleSets();
            refreshChannels();
        });
    }

    // ═════════════════════════════════════════════
    // 规则集列表
    // ═════════════════════════════════════════════

    private void refreshRuleSets() {
        if (writer == null) return;
        List<FilterConfigWriter.RuleSetData> ruleSets = writer.getRuleSets();
        Map<String, Set<Long>> channelMap = writer.getRuleSetChannels();

        if (textEmptyRules != null) {
            textEmptyRules.setVisibility(ruleSets.isEmpty() ? View.VISIBLE : View.GONE);
        }

        ruleSetAdapter.submitList(ruleSets, channelMap);
    }

    private void showCreateRuleSetDialog() {
        EditText editName = new EditText(this);
        editName.setHint("例如：广告过滤");
        editName.setSingleLine(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle("新建规则集")
                .setView(editName)
                .setPositiveButton("创建", (d, which) -> {
                    String name = editName.getText().toString().trim();
                    if (name.isEmpty()) return;

                    FilterConfigWriter.RuleSetData rs = new FilterConfigWriter.RuleSetData(
                            FilterConfigWriter.RuleSetData.generateId(),
                            name, true, false, new HashSet<>());
                    writer.addRuleSet(rs);
                    refreshRuleSets();

                    // 自动跳转到详情页
                    Intent intent = new Intent(this, RuleSetDetailActivity.class);
                    intent.putExtra(RuleSetDetailActivity.EXTRA_RULE_SET_ID, rs.id);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═════════════════════════════════════════════
    // 频道汇总列表
    // ═════════════════════════════════════════════

    private void refreshChannels() {
        refreshChannels("");
    }

    private void refreshChannels(String query) {
        List<ChannelSummary> channels = loadDiscoveredChannels();
        Map<Long, List<FilterConfigWriter.RuleSetData>> channelRuleSets = writer != null
                ? writer.getChannelRuleSets() : Collections.emptyMap();
        Map<Long, ReactionsRule> reactionsRules = writer != null
                ? writer.getReactionsRules() : Collections.emptyMap();

        String lowerQuery = query.toLowerCase();

        // 过滤 + 搜索
        List<ChannelSummary> filtered = new ArrayList<>();
        for (ChannelSummary ch : channels) {
            if (!query.isEmpty()) {
                String nameLower = ch.name.toLowerCase();
                String idStr = String.valueOf(ch.id);
                if (!nameLower.contains(lowerQuery) && !idStr.contains(query)) continue;
            }
            ch.ruleSetNames = new ArrayList<>();
            List<FilterConfigWriter.RuleSetData> rsList = channelRuleSets.get(ch.id);
            if (rsList != null) {
                for (FilterConfigWriter.RuleSetData rs : rsList) {
                    ch.ruleSetNames.add(rs.name);
                }
            }
            ReactionsRule rr = reactionsRules.get(ch.id);
            ch.reactionsBadge = rr != null && rr.enabled ? "⚡" + rr.describe() : null;
            filtered.add(ch);
        }

        if (textChannelCount != null) {
            textChannelCount.setText(channels.size() + " 个频道");
        }

        channelAdapter.submitList(filtered);
    }

    private List<ChannelSummary> loadDiscoveredChannels() {
        List<ChannelSummary> result = new ArrayList<>();
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
                    result.add(new ChannelSummary(dialogId, name, lastSeen));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load discovered channels", e);
        }
        result.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return result;
    }

    // ═════════════════════════════════════════════
    // 表情筛选检索深度（全局默认）
    // ═════════════════════════════════════════════

    private void showSearchDepthDialog() {
        if (writer == null) {
            Snackbar.make(findViewById(android.R.id.content),
                    "XposedService 未就绪", Snackbar.LENGTH_LONG).show();
            return;
        }
        int current = writer.getReactionsSearchDepth();
        String[] labels = new String[ReactionsRule.DEPTH_PRESETS.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = ReactionsRule.formatDepth(ReactionsRule.DEPTH_PRESETS[i]) + " 条";
            if (ReactionsRule.DEPTH_PRESETS[i] == current) checked = i;
        }
        final int[] selected = {checked};
        new MaterialAlertDialogBuilder(this)
                .setTitle("默认检索深度")
                .setSingleChoiceItems(labels, checked, (d, which) -> selected[0] = which)
                .setPositiveButton("确定", (d, which) -> {
                    int depth = ReactionsRule.DEPTH_PRESETS[selected[0]];
                    writer.setReactionsSearchDepth(depth);
                    rowSearchDepth.setText("默认检索深度："
                            + ReactionsRule.formatDepth(depth) + " 条");
                    Snackbar.make(findViewById(android.R.id.content),
                            "已保存，Telegram 内立即生效", Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═════════════════════════════════════════════
    // 保存设置
    // ═════════════════════════════════════════════

    private void saveSettings() {
        if (writer == null) {
            Snackbar.make(findViewById(android.R.id.content),
                    "XposedService 未就绪", Snackbar.LENGTH_LONG).show();
            return;
        }

        writer.setEnabled(switchEnabled.isChecked());

        Snackbar.make(findViewById(android.R.id.content),
                getString(R.string.config_saved), Snackbar.LENGTH_SHORT).show();
    }

    // ═════════════════════════════════════════════
    // 规则集 Adapter
    // ═════════════════════════════════════════════

    class RuleSetAdapter extends RecyclerView.Adapter<RuleSetAdapter.ViewHolder> {
        private final List<FilterConfigWriter.RuleSetData> items = new ArrayList<>();
        private Map<String, Set<Long>> channelMap = new java.util.HashMap<>();

        void submitList(List<FilterConfigWriter.RuleSetData> newItems, Map<String, Set<Long>> channelMap) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            this.channelMap = channelMap != null ? channelMap : new java.util.HashMap<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_rule_set, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FilterConfigWriter.RuleSetData rs = items.get(position);

            holder.textStatusIcon.setText(rs.enabled ? "✅" : "⏸");
            holder.textName.setText(rs.name);

            Set<Long> channels = channelMap.get(rs.id);
            int channelCount = channels != null ? channels.size() : 0;

            StringBuilder info = new StringBuilder();
            info.append(rs.keywords.size()).append(" 条关键词");
            if (rs.useRegex) info.append(" · 正则");
            info.append(" · ").append(channelCount).append(" 个频道");
            holder.textInfo.setText(info.toString());

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, RuleSetDetailActivity.class);
                intent.putExtra(RuleSetDetailActivity.EXTRA_RULE_SET_ID, rs.id);
                startActivity(intent);
            });

            holder.btnDelete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(SettingsActivity.this)
                        .setTitle("删除规则集")
                        .setMessage("确定删除「" + rs.name + "」？\n不会影响频道的白名单状态。")
                        .setPositiveButton("删除", (d, which) -> {
                            writer.deleteRuleSet(rs.id);
                            refreshRuleSets();
                            refreshChannels();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textStatusIcon, textName, textInfo;
            View btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                textStatusIcon = itemView.findViewById(R.id.text_status_icon);
                textName = itemView.findViewById(R.id.text_rule_set_name);
                textInfo = itemView.findViewById(R.id.text_rule_set_info);
                btnDelete = itemView.findViewById(R.id.btn_delete_rule_set);
            }
        }
    }

    // ═════════════════════════════════════════════
    // 频道汇总 Adapter
    // ═════════════════════════════════════════════

    static class ChannelSummary {
        final long id;
        final String name;
        final long lastSeen;
        List<String> ruleSetNames = new ArrayList<>();
        String reactionsBadge;

        ChannelSummary(long id, String name, long lastSeen) {
            this.id = id;
            this.name = name;
            this.lastSeen = lastSeen;
        }
    }

    class ChannelSummaryAdapter extends RecyclerView.Adapter<ChannelSummaryAdapter.ViewHolder> {
        private final List<ChannelSummary> items = new ArrayList<>();

        void submitList(List<ChannelSummary> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_summary, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ChannelSummary ch = items.get(position);
            holder.textName.setText(ch.name);
            holder.textId.setText(String.valueOf(ch.id));

            List<String> badges = new ArrayList<>();
            if (!ch.ruleSetNames.isEmpty()) {
                badges.add("🛡 " + String.join(", ", ch.ruleSetNames));
            }
            if (ch.reactionsBadge != null) {
                badges.add(ch.reactionsBadge);
            }
            holder.textBadge.setText(badges.isEmpty() ? "—" : String.join(" · ", badges));
        }

        @Override public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textName, textId, textBadge;

            ViewHolder(View itemView) {
                super(itemView);
                textName = itemView.findViewById(R.id.text_channel_name);
                textId = itemView.findViewById(R.id.text_channel_id);
                textBadge = itemView.findViewById(R.id.text_rule_set_badge);
            }
        }
    }
}
