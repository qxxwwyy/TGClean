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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/**
 * 规则集详情页 — 编辑单个规则集
 *
 * 功能：
 * - 启用/禁用开关
 * - 正则开关
 * - 规则集名称编辑
 * - 关键词 CRUD
 * - 频道勾选（从已发现频道中选择）
 */
public class RuleSetDetailActivity extends AppCompatActivity {

    private static final String TAG = "TGClean-RuleSetDetail";
    private static final String PREFS_NAME = "tgclean_config";

    public static final String EXTRA_RULE_SET_ID = "rule_set_id";

    private String ruleSetId;
    private SharedPreferences remotePrefs = null;
    private SharedPreferences discoveredPrefs = null;
    private FilterConfigWriter writer = null;

    // 当前编辑状态
    private FilterConfigWriter.RuleSetData currentRuleSet = null;
    private Set<Long> currentChannels = new HashSet<>();
    private List<ChannelItem> allChannels = new ArrayList<>();

    // ─── 控件 ───
    private MaterialToolbar toolbar;
    private MaterialSwitch switchEnabled;
    private MaterialSwitch switchRegex;
    private TextInputEditText editName;
    private RecyclerView recyclerKeywords;
    private KeywordAdapter keywordAdapter;
    private TextView textKeywordCount;
    private TextView textEmptyKeywords;
    private RecyclerView recyclerChannels;
    private ChannelCheckAdapter channelAdapter;
    private TextView textChannelCount;
    private TextInputEditText editChannelSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rule_set_detail);

        ruleSetId = getIntent().getStringExtra(EXTRA_RULE_SET_ID);
        if (ruleSetId == null || ruleSetId.isEmpty()) {
            finish();
            return;
        }

        discoveredPrefs = getSharedPreferences("discovered_channels", Context.MODE_PRIVATE);

        initViews();
        setupListeners();

        XposedService service = App.getService();
        if (service != null) {
            remotePrefs = service.getRemotePreferences(PREFS_NAME);
            writer = new FilterConfigWriter(remotePrefs);
            loadData();
        } else {
            serviceReadyListener = svc -> runOnUiThread(() -> {
                remotePrefs = svc.getRemotePreferences(PREFS_NAME);
                writer = new FilterConfigWriter(remotePrefs);
                loadData();
            });
            App.addServiceReadyListener(serviceReadyListener);
        }
    }

    private App.ServiceReadyListener serviceReadyListener;

    @Override
    protected void onDestroy() {
        // 防止 XposedService 迟迟未就绪时 listener 挂在 App 静态列表里泄漏 Activity
        if (serviceReadyListener != null) {
            App.removeServiceReadyListener(serviceReadyListener);
        }
        super.onDestroy();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("规则集详情");
        toolbar.setNavigationOnClickListener(v -> finish());

        switchEnabled = findViewById(R.id.switch_enabled);
        switchRegex = findViewById(R.id.switch_regex);
        editName = findViewById(R.id.edit_rule_set_name);

        recyclerKeywords = findViewById(R.id.recycler_keywords);
        recyclerKeywords.setLayoutManager(new LinearLayoutManager(this));
        keywordAdapter = new KeywordAdapter();
        recyclerKeywords.setAdapter(keywordAdapter);

        textKeywordCount = findViewById(R.id.text_keyword_count);
        textEmptyKeywords = findViewById(R.id.text_empty_keywords);

        recyclerChannels = findViewById(R.id.recycler_channels);
        recyclerChannels.setLayoutManager(new LinearLayoutManager(this));
        channelAdapter = new ChannelCheckAdapter();
        recyclerChannels.setAdapter(channelAdapter);

        textChannelCount = findViewById(R.id.text_channel_count);
        editChannelSearch = findViewById(R.id.edit_channel_search);

        // 频道搜索
        editChannelSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                channelAdapter.filter(s.toString().trim());
            }
        });
    }

    private void setupListeners() {
        // 启用/禁用 — 实时保存
        switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
            if (writer == null || currentRuleSet == null) return;
            currentRuleSet.enabled = checked;
            writer.updateRuleSet(currentRuleSet);
        });

        // 正则开关 — 实时保存
        switchRegex.setOnCheckedChangeListener((btn, checked) -> {
            if (writer == null || currentRuleSet == null) return;
            currentRuleSet.useRegex = checked;
            writer.updateRuleSet(currentRuleSet);
        });

        // 名称 — 失焦保存
        editName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && writer != null && currentRuleSet != null) {
                String newName = editName.getText().toString().trim();
                if (!newName.isEmpty() && !newName.equals(currentRuleSet.name)) {
                    currentRuleSet.name = newName;
                    writer.updateRuleSet(currentRuleSet);
                }
            }
        });

        // 添加关键词
        findViewById(R.id.btn_add_keyword).setOnClickListener(v -> showAddKeywordDialog());

        // 全选 / 取消全选
        findViewById(R.id.btn_select_all).setOnClickListener(v -> selectAllChannels(true));
        findViewById(R.id.btn_select_none).setOnClickListener(v -> selectAllChannels(false));
    }

    private void loadData() {
        if (writer == null) return;

        // 加载规则集
        List<FilterConfigWriter.RuleSetData> ruleSets = writer.getRuleSets();
        for (FilterConfigWriter.RuleSetData rs : ruleSets) {
            if (rs.id.equals(ruleSetId)) {
                currentRuleSet = rs;
                break;
            }
        }

        if (currentRuleSet == null) {
            Snackbar.make(findViewById(android.R.id.content),
                    "规则集不存在", Snackbar.LENGTH_LONG).show();
            finish();
            return;
        }

        // 加载频道映射
        Map<String, Set<Long>> channelMap = writer.getRuleSetChannels();
        currentChannels = new HashSet<>(channelMap.getOrDefault(ruleSetId, new HashSet<>()));

        // 更新UI
        toolbar.setTitle(currentRuleSet.name);
        switchEnabled.setChecked(currentRuleSet.enabled);
        switchRegex.setChecked(currentRuleSet.useRegex);
        editName.setText(currentRuleSet.name);

        refreshKeywords();
        loadAndShowChannels();
    }

    // ═════════════════════════════════════════════
    // 关键词管理
    // ═════════════════════════════════════════════

    private void refreshKeywords() {
        List<String> kwList = new ArrayList<>(currentRuleSet.keywords);
        keywordAdapter.submitList(kwList);

        if (textKeywordCount != null) {
            textKeywordCount.setText(kwList.size() + " 条");
        }
        if (textEmptyKeywords != null) {
            textEmptyKeywords.setVisibility(kwList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void showAddKeywordDialog() {
        EditText editKeyword = new EditText(this);
        editKeyword.setHint("输入关键词");
        editKeyword.setSingleLine(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle("添加关键词")
                .setView(editKeyword)
                .setPositiveButton("添加", (d, which) -> {
                    String kw = editKeyword.getText().toString().trim();
                    if (kw.isEmpty()) return;

                    currentRuleSet.keywords.add(kw);
                    writer.updateRuleSet(currentRuleSet);
                    refreshKeywords();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═════════════════════════════════════════════
    // 频道勾选
    // ═════════════════════════════════════════════

    private void loadAndShowChannels() {
        allChannels = loadDiscoveredChannels();
        channelAdapter.submitList(allChannels, currentChannels);
        updateChannelCount();
    }

    private void selectAllChannels(boolean selected) {
        for (ChannelItem ch : allChannels) {
            if (selected) {
                currentChannels.add(ch.id);
            } else {
                currentChannels.remove(ch.id);
            }
        }
        writer.setRuleSetChannels(ruleSetId, currentChannels);
        channelAdapter.setCheckedChannels(currentChannels);
        updateChannelCount();
    }

    private void updateChannelCount() {
        if (textChannelCount != null) {
            textChannelCount.setText(currentChannels.size() + " 个频道");
        }
    }

    private List<ChannelItem> loadDiscoveredChannels() {
        List<ChannelItem> result = new ArrayList<>();
        try {
            String json = com.tgclean.receiver.ChannelReceiver.getChannelsJson(this);
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    long dialogId = Long.parseLong(key);
                    JSONObject ch = obj.getJSONObject(key);
                    String name = ch.optString("name", String.valueOf(dialogId));
                    result.add(new ChannelItem(dialogId, name));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load channels", e);
        }
        // 按 name 排序
        Collections.sort(result, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    // ═════════════════════════════════════════════
    // 关键词 Adapter
    // ═════════════════════════════════════════════

    class KeywordAdapter extends RecyclerView.Adapter<KeywordAdapter.ViewHolder> {
        private final List<String> items = new ArrayList<>();

        void submitList(List<String> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_keyword, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String keyword = items.get(position);
            holder.textKeyword.setText(keyword);
            holder.btnDelete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(RuleSetDetailActivity.this)
                        .setTitle("删除关键词")
                        .setMessage("确定删除「" + keyword + "」？")
                        .setPositiveButton("删除", (d, which) -> {
                            currentRuleSet.keywords.remove(keyword);
                            writer.updateRuleSet(currentRuleSet);
                            refreshKeywords();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textKeyword;
            View btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                textKeyword = itemView.findViewById(R.id.text_keyword);
                btnDelete = itemView.findViewById(R.id.btn_delete_keyword);
            }
        }
    }

    // ═════════════════════════════════════════════
    // 频道勾选 Adapter
    // ═════════════════════════════════════════════

    static class ChannelItem {
        final long id;
        final String name;

        ChannelItem(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    class ChannelCheckAdapter extends RecyclerView.Adapter<ChannelCheckAdapter.ViewHolder> {
        private final List<ChannelItem> allItems = new ArrayList<>();
        private final List<ChannelItem> filteredItems = new ArrayList<>();
        private Set<Long> checkedChannels = new HashSet<>();

        void submitList(List<ChannelItem> items, Set<Long> checked) {
            allItems.clear();
            allItems.addAll(items);
            checkedChannels = new HashSet<>(checked);
            applyFilter("");
        }

        void setCheckedChannels(Set<Long> checked) {
            checkedChannels = new HashSet<>(checked);
            notifyDataSetChanged();
        }

        void filter(String query) {
            applyFilter(query);
        }

        private void applyFilter(String query) {
            filteredItems.clear();
            String lowerQuery = query.toLowerCase();
            for (ChannelItem ch : allItems) {
                if (query.isEmpty()) {
                    filteredItems.add(ch);
                } else {
                    if (ch.name.toLowerCase().contains(lowerQuery)
                            || String.valueOf(ch.id).contains(query)) {
                        filteredItems.add(ch);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_checkbox, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ChannelItem ch = filteredItems.get(position);
            holder.textName.setText(ch.name);
            holder.textId.setText(String.valueOf(ch.id));

            // 阻止 checkbox 触发 item 点击的递归
            holder.checkbox.setOnCheckedChangeListener(null);
            holder.checkbox.setChecked(checkedChannels.contains(ch.id));
            holder.checkbox.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    checkedChannels.add(ch.id);
                } else {
                    checkedChannels.remove(ch.id);
                }
                writer.setRuleSetChannels(ruleSetId, checkedChannels);
                updateChannelCount();
            });

            // 点击整行也切换
            holder.itemView.setOnClickListener(v -> {
                boolean newState = !holder.checkbox.isChecked();
                holder.checkbox.setChecked(newState);
            });
        }

        @Override public int getItemCount() { return filteredItems.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCheckBox checkbox;
            TextView textName, textId;

            ViewHolder(View itemView) {
                super(itemView);
                checkbox = itemView.findViewById(R.id.checkbox_channel);
                textName = itemView.findViewById(R.id.text_channel_name);
                textId = itemView.findViewById(R.id.text_channel_id);
            }
        }
    }
}
