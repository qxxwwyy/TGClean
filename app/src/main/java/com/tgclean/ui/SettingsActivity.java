package com.tgclean.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.tgclean.App;
import com.tgclean.R;
import com.tgclean.config.FilterConfigWriter;
import com.tgclean.config.ReactionsRule;
import com.tgclean.receiver.ChannelReceiver;

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
 * 结构（全部即改即存，无"保存"按钮——remote prefs 实时推送架构下保存是伪概念，
 * 且 100+ 频道时按钮在页面底部要滑很久才能到达，发布前 UX 复核 P0-2）：
 * - LSPosed 服务等待层（默认可见，超时给出排障指引 + 重试）
 * - 模块总开关 / 调试日志开关
 * - 表情筛选全局默认检索深度
 * - 规则集列表（CRUD）
 * - 发现的频道（默认折叠 10 条 + 显示全部；点击行弹规则集勾选；搜索防抖 + 内存过滤）
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "TGClean-Settings";
    private static final String PREFS_NAME = "tgclean_config";
    /** 服务等待超时：超过即认为 LSPosed 未就绪，展示排障指引（UX 复核 P0-1） */
    private static final long SERVICE_TIMEOUT_MS = 5000;
    /** 频道列表折叠条数：只 measure 10 行，同时免掉"滑到底才能保存"（UX 复核专节） */
    private static final int CHANNEL_COLLAPSED_COUNT = 10;
    /** 搜索防抖（性能审计 P1-1） */
    private static final long SEARCH_DEBOUNCE_MS = 200;

    // ─── 控件 ───
    private MaterialSwitch switchEnabled;
    private MaterialSwitch switchDebugLog;
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
    private TextView textEmptyChannels;

    private SharedPreferences remotePrefs = null;
    private FilterConfigWriter writer = null;

    /** 频道数据缓存：解析一次，键入搜索只做内存过滤（性能审计 P1-1） */
    private List<ChannelSummary> channelCache = new ArrayList<>();
    private Map<Long, List<FilterConfigWriter.RuleSetData>> ruleSetsByChannel = Collections.emptyMap();
    private Map<Long, ReactionsRule> reactionsRulesCache = Collections.emptyMap();
    /** 初始回填抑制：避免 setChecked 触发"即时保存"写回（UX 复核 P0-2 配套） */
    private boolean suppressSwitchWrite = false;
    private String lastQuery = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::runSearch;
    private final Runnable serviceTimeoutRunnable = this::onServiceTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        setupListeners();

        layoutWaiting = findViewById(R.id.layout_waiting);
        scrollContent = findViewById(R.id.scroll_content);
        // 等待层 XML 默认可见；超时未就绪给出可操作的排障指引
        mainHandler.postDelayed(serviceTimeoutRunnable, SERVICE_TIMEOUT_MS);

        App.addServiceReadyListener(serviceReadyListener);
    }

    private final App.ServiceReadyListener serviceReadyListener = this::onServiceReady;

    @Override
    protected void onDestroy() {
        App.removeServiceReadyListener(serviceReadyListener);
        mainHandler.removeCallbacks(serviceTimeoutRunnable);
        mainHandler.removeCallbacks(searchRunnable);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (writer != null) {
            refreshRuleSets();
            rebuildChannelData();
            applyChannelFilter(lastQuery);
        }
    }

    private void initViews() {
        switchEnabled = findViewById(R.id.switch_enabled);
        switchDebugLog = findViewById(R.id.switch_debug_log);

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
        textEmptyChannels = findViewById(R.id.text_empty_channels);

        rowSearchDepth = findViewById(R.id.row_search_depth);
        rowSearchDepth.setOnClickListener(v -> showSearchDepthDialog());

        // 频道搜索（防抖后走内存过滤）
        editChannelSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                lastQuery = s.toString().trim();
                mainHandler.removeCallbacks(searchRunnable);
                mainHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
    }

    private void setupListeners() {
        findViewById(R.id.btn_add_rule_set).setOnClickListener(v -> showCreateRuleSetDialog());

        // 等待层「重试」：重置提示并重新计时（binder 就绪时 onServiceReady 会自动接管）
        findViewById(R.id.btn_waiting_retry).setOnClickListener(v -> {
            TextView tw = findViewById(R.id.text_waiting);
            tw.setText(R.string.waiting_service);
            v.setVisibility(View.GONE);
            findViewById(R.id.waiting_indicator).setVisibility(View.VISIBLE);
            mainHandler.removeCallbacks(serviceTimeoutRunnable);
            mainHandler.postDelayed(serviceTimeoutRunnable, SERVICE_TIMEOUT_MS);
        });

        // 总开关即改即存（发布前 UX 复核 P0-2：全 App 唯一需要"保存"的交互，删除）
        switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressSwitchWrite || writer == null) return;
            writer.setEnabled(checked);
            Snackbar.make(getRoot(), checked ? "已开启过滤，新消息即时生效" : "已关闭过滤",
                    Snackbar.LENGTH_SHORT).show();
        });

        switchDebugLog.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressSwitchWrite || writer == null) return;
            writer.setDebugLog(checked);
        });
    }

    private View getRoot() {
        return scrollContent != null ? scrollContent : findViewById(android.R.id.content);
    }

    /** 服务超时：多半是 LSPosed 未装好/未启用模块/未勾 scope，给出可操作的指引 */
    private void onServiceTimeout() {
        if (writer != null) return;
        TextView tw = findViewById(R.id.text_waiting);
        if (tw != null) tw.setText(R.string.waiting_service_timeout);
        View retry = findViewById(R.id.btn_waiting_retry);
        if (retry != null) retry.setVisibility(View.VISIBLE);
        View indicator = findViewById(R.id.waiting_indicator);
        if (indicator != null) indicator.setVisibility(View.GONE);
    }

    // ═════════════════════════════════════════════
    // XposedService
    // ═════════════════════════════════════════════

    private void onServiceReady(XposedService service) {
        Log.i(TAG, "XposedService ready");
        runOnUiThread(() -> {
            mainHandler.removeCallbacks(serviceTimeoutRunnable);
            if (layoutWaiting != null) layoutWaiting.setVisibility(View.GONE);
            if (scrollContent != null) scrollContent.setVisibility(View.VISIBLE);

            remotePrefs = service.getRemotePreferences(PREFS_NAME);
            writer = new FilterConfigWriter(remotePrefs);

            suppressSwitchWrite = true;
            switchEnabled.setChecked(remotePrefs.getBoolean("filter_enabled", true));
            switchDebugLog.setChecked(writer.isDebugLog());
            suppressSwitchWrite = false;
            rowSearchDepth.setText(ReactionsRule.formatDepth(
                    writer.getReactionsSearchDepth()) + " 条 ›");

            // 执行旧数据迁移
            boolean migrated = writer.migrateLegacyIfNeeded();
            if (migrated) {
                Snackbar.make(getRoot(), "已迁移旧版配置为规则集", Snackbar.LENGTH_LONG).show();
            }

            refreshRuleSets();
            rebuildChannelData();
            applyChannelFilter(lastQuery);
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
                    if (writer == null) {
                        Snackbar.make(getRoot(), "LSPosed 服务未就绪", Snackbar.LENGTH_LONG).show();
                        return;
                    }

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

    /** 重建频道缓存（onResume / 服务就绪 / 数据被勾选弹窗改动时调用） */
    private void rebuildChannelData() {
        List<ChannelSummary> channels = new ArrayList<>();
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
                    channels.add(new ChannelSummary(dialogId, name, lastSeen));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load discovered channels", e);
        }
        channels.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        channelCache = channels;
        ruleSetsByChannel = writer != null ? writer.getChannelRuleSets() : Collections.emptyMap();
        reactionsRulesCache = writer != null ? writer.getReactionsRules() : Collections.emptyMap();
    }

    private void runSearch() {
        applyChannelFilter(lastQuery);
    }

    /** 纯内存过滤 + 徽标装配（键入路径零 JSON 解析） */
    private void applyChannelFilter(String query) {
        String lowerQuery = query.toLowerCase();
        List<ChannelSummary> filtered = new ArrayList<>();
        for (ChannelSummary ch : channelCache) {
            if (!query.isEmpty()) {
                String nameLower = ch.name.toLowerCase();
                String idStr = String.valueOf(ch.id);
                if (!nameLower.contains(lowerQuery) && !idStr.contains(query)) continue;
            }
            ch.ruleSetNames = new ArrayList<>();
            List<FilterConfigWriter.RuleSetData> rsList = ruleSetsByChannel.get(ch.id);
            if (rsList != null) {
                for (FilterConfigWriter.RuleSetData rs : rsList) {
                    ch.ruleSetNames.add(rs.name);
                }
            }
            ReactionsRule rr = reactionsRulesCache.get(ch.id);
            ch.reactionsBadge = rr != null && rr.enabled ? "⚡" + rr.describe() : null;
            filtered.add(ch);
        }

        if (textChannelCount != null) {
            textChannelCount.setText(query.isEmpty()
                    ? filtered.size() + " 个频道"
                    : filtered.size() + "/" + channelCache.size() + " 个频道");
        }
        if (textEmptyChannels != null) {
            textEmptyChannels.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        }
        channelAdapter.setExpanded(!query.isEmpty()); // 搜索即展开，清空恢复折叠
        channelAdapter.submitList(filtered);
    }

    /** 频道行点击：多选弹窗把该频道挂到/摘出各规则集（UX 复核 P1-3） */
    private void showChannelRuleSetsDialog(ChannelSummary ch) {
        if (writer == null) {
            Snackbar.make(getRoot(), "LSPosed 服务未就绪", Snackbar.LENGTH_LONG).show();
            return;
        }
        List<FilterConfigWriter.RuleSetData> allSets = writer.getRuleSets();
        if (allSets.isEmpty()) {
            Snackbar.make(getRoot(), "还没有规则集，请先在上方创建一个", Snackbar.LENGTH_LONG).show();
            return;
        }
        Map<String, Set<Long>> channelMap = writer.getRuleSetChannels();
        String[] names = new String[allSets.size()];
        final boolean[] checked = new boolean[allSets.size()];
        for (int i = 0; i < allSets.size(); i++) {
            names[i] = allSets.get(i).name;
            Set<Long> ids = channelMap.get(allSets.get(i).id);
            checked[i] = ids != null && ids.contains(ch.id);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(ch.name)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("确定", (d, which) -> {
                    for (int i = 0; i < allSets.size(); i++) {
                        if (checked[i]) {
                            writer.addChannelToRuleSet(allSets.get(i).id, ch.id);
                        } else {
                            writer.removeChannelFromRuleSet(allSets.get(i).id, ch.id);
                        }
                    }
                    refreshRuleSets();
                    rebuildChannelData();
                    applyChannelFilter(lastQuery);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═════════════════════════════════════════════
    // 表情筛选检索深度（全局默认）
    // ═════════════════════════════════════════════

    private void showSearchDepthDialog() {
        if (writer == null) {
            Snackbar.make(getRoot(), "LSPosed 服务未就绪", Snackbar.LENGTH_LONG).show();
            return;
        }
        int current = writer.getReactionsSearchDepth();
        // 末位固定为“自定义…”：非预设值（如旧版存的 15000）也落在这档
        final int customIdx = ReactionsRule.DEPTH_PRESETS.length;
        String[] labels = new String[customIdx + 1];
        int checked = customIdx;
        for (int i = 0; i < customIdx; i++) {
            labels[i] = ReactionsRule.formatDepth(ReactionsRule.DEPTH_PRESETS[i]) + " 条";
            if (ReactionsRule.DEPTH_PRESETS[i] == current) checked = i;
        }
        labels[customIdx] = current > 0 && checked == customIdx
                ? "自定义（当前 " + ReactionsRule.formatDepth(current) + " 条）" : "自定义…";
        final int[] selected = {checked};
        new MaterialAlertDialogBuilder(this)
                .setTitle("默认检索深度")
                .setSingleChoiceItems(labels, checked, (d, which) -> selected[0] = which)
                .setPositiveButton("确定", (d, which) -> {
                    if (selected[0] == customIdx) {
                        promptCustomDepth(current);
                    } else {
                        applyDepth(ReactionsRule.DEPTH_PRESETS[selected[0]]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 自定义深度输入：钳制到 [MIN_DEPTH, MAX_DEPTH] 后保存 */
    private void promptCustomDepth(int prefill) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(prefill > 0 ? String.valueOf(prefill) : "");
        input.setHint(ReactionsRule.MIN_DEPTH + " ~ " + ReactionsRule.formatDepth(ReactionsRule.MAX_DEPTH));
        new MaterialAlertDialogBuilder(this)
                .setTitle("自定义检索深度（条）")
                .setView(input)
                .setPositiveButton("确定", (d, which) -> {
                    String t = input.getText().toString().trim();
                    int v;
                    try {
                        v = Integer.parseInt(t);
                    } catch (NumberFormatException e) {
                        // 超 int 范围的纯数字按上限钳制，非数字才报错（审计 A-2）
                        v = t.matches("\\d{10,}") ? ReactionsRule.MAX_DEPTH : -1;
                    }
                    if (v <= 0) {
                        Snackbar.make(getRoot(), "请输入正整数", Snackbar.LENGTH_SHORT).show();
                        return;
                    }
                    applyDepth(ReactionsRule.clampDepth(v));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyDepth(int depth) {
        writer.setReactionsSearchDepth(depth);
        rowSearchDepth.setText(ReactionsRule.formatDepth(depth) + " 条 ›");
        Snackbar.make(getRoot(), "已保存，Telegram 内立即生效", Snackbar.LENGTH_SHORT).show();
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
                        .setMessage("确定删除「" + rs.name + "」？")
                        .setPositiveButton("删除", (d, which) -> {
                            writer.deleteRuleSet(rs.id);
                            refreshRuleSets();
                            rebuildChannelData();
                            applyChannelFilter(lastQuery);
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
    // 频道汇总 Adapter（默认折叠 CHANNEL_COLLAPSED_COUNT 条 + 显示全部/收起）
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

    class ChannelSummaryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_ITEM = 0;
        private static final int TYPE_FOOTER = 1;

        private final List<ChannelSummary> items = new ArrayList<>();
        private boolean expanded = false;

        void submitList(List<ChannelSummary> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        void setExpanded(boolean value) {
            expanded = value;
        }

        private boolean needFooter() {
            return items.size() > CHANNEL_COLLAPSED_COUNT;
        }

        private int visibleCount() {
            return (expanded || !needFooter()) ? items.size()
                    : Math.min(CHANNEL_COLLAPSED_COUNT, items.size());
        }

        @Override public int getItemCount() {
            int n = visibleCount();
            return needFooter() ? n + 1 : n;
        }

        @Override public int getItemViewType(int position) {
            return (needFooter() && position == getItemCount() - 1) ? TYPE_FOOTER : TYPE_ITEM;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_FOOTER) {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_show_all, parent, false);
                return new FooterHolder(view);
            }
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_summary, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof FooterHolder) {
                FooterHolder fh = (FooterHolder) holder;
                fh.btnShowAll.setText(expanded ? "收起" : "显示全部 " + items.size() + " 个频道");
                fh.btnShowAll.setOnClickListener(v -> {
                    expanded = !expanded;
                    notifyDataSetChanged();
                });
                return;
            }
            ChannelSummary ch = items.get(position);
            ViewHolder vh = (ViewHolder) holder;
            vh.textName.setText(ch.name);
            vh.textId.setText(String.valueOf(ch.id));

            List<String> badges = new ArrayList<>();
            if (!ch.ruleSetNames.isEmpty()) {
                badges.add("🛡 " + String.join(", ", ch.ruleSetNames));
            }
            if (ch.reactionsBadge != null) {
                badges.add(ch.reactionsBadge);
            }
            vh.textBadge.setText(badges.isEmpty() ? "点按配置 ›" : String.join(" · ", badges));

            vh.itemView.setOnClickListener(v -> showChannelRuleSetsDialog(ch));
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textName, textId, textBadge;

            ViewHolder(View itemView) {
                super(itemView);
                textName = itemView.findViewById(R.id.text_channel_name);
                textId = itemView.findViewById(R.id.text_channel_id);
                textBadge = itemView.findViewById(R.id.text_rule_set_badge);
            }
        }

        class FooterHolder extends RecyclerView.ViewHolder {
            final android.widget.Button btnShowAll;

            FooterHolder(View itemView) {
                super(itemView);
                btnShowAll = itemView.findViewById(R.id.btn_show_all);
            }
        }
    }
}
