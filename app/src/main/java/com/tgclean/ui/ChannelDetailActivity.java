package com.tgclean.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
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
 * 频道详情页 — 管理单个频道的过滤规则
 *
 * 功能：
 * - 查看频道 ID
 * - 关键词列表（add / delete）
 * - 白名单切换
 */
public class ChannelDetailActivity extends AppCompatActivity {

    private static final String TAG = "TGClean-ChannelDetail";
    static final String EXTRA_DIALOG_ID = "dialog_id";
    private static final String PREFS_NAME = "tgclean_config";

    private long dialogId;
    private SharedPreferences remotePrefs = null;
    private Set<String> keywords = new HashSet<>();
    private boolean isWhitelisted = false;

    private MaterialToolbar toolbar;
    private MaterialSwitch switchWhitelist;
    private RecyclerView recyclerViewKeywords;
    private KeywordAdapter keywordAdapter;
    private FloatingActionButton fabAddKeyword;
    private TextView textEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_detail);

        dialogId = getIntent().getLongExtra(EXTRA_DIALOG_ID, 0);
        if (dialogId == 0) {
            finish();
            return;
        }

        initViews();

        XposedService service = App.getService();
        if (service != null) {
            remotePrefs = service.getRemotePreferences(PREFS_NAME);
            loadData();
        } else {
            App.addServiceReadyListener(svc -> {
                runOnUiThread(() -> {
                    remotePrefs = svc.getRemotePreferences(PREFS_NAME);
                    loadData();
                });
            });
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("频道 " + dialogId);
        toolbar.setNavigationOnClickListener(v -> finish());

        switchWhitelist = findViewById(R.id.switch_whitelist);
        switchWhitelist.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (remotePrefs == null) return;
            FilterConfigWriter writer = new FilterConfigWriter(remotePrefs);
            if (isChecked) {
                writer.addToWhitelist(dialogId);
                Snackbar.make(findViewById(android.R.id.content),
                        "已加入白名单", Snackbar.LENGTH_SHORT).show();
            } else {
                writer.removeFromWhitelist(dialogId);
                Snackbar.make(findViewById(android.R.id.content),
                        "已移出白名单", Snackbar.LENGTH_SHORT).show();
            }
            isWhitelisted = isChecked;
        });

        recyclerViewKeywords = findViewById(R.id.recycler_keywords);
        recyclerViewKeywords.setLayoutManager(new LinearLayoutManager(this));
        keywordAdapter = new KeywordAdapter();
        recyclerViewKeywords.setAdapter(keywordAdapter);

        fabAddKeyword = findViewById(R.id.fab_add_keyword);
        fabAddKeyword.setOnClickListener(v -> showAddKeywordDialog());

        textEmpty = findViewById(R.id.text_empty_keywords);
    }

    private void loadData() {
        if (remotePrefs == null) return;

        try {
            // 加载关键词
            String raw = remotePrefs.getString("channel_rules", "");
            keywords = new HashSet<>();
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

            // 加载白名单状态
            String wlRaw = remotePrefs.getString("whitelist", "");
            isWhitelisted = false;
            if (wlRaw != null && wlRaw.trim().startsWith("[")) {
                JSONArray wlArr = new JSONArray(wlRaw);
                for (int i = 0; i < wlArr.length(); i++) {
                    if (wlArr.getLong(i) == dialogId) {
                        isWhitelisted = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load channel data", e);
        }

        switchWhitelist.setChecked(isWhitelisted);
        refreshKeywordList();
    }

    private void refreshKeywordList() {
        List<String> kwList = new ArrayList<>(keywords);
        keywordAdapter.submitList(kwList);

        if (textEmpty != null) {
            textEmpty.setVisibility(kwList.isEmpty() ? View.VISIBLE : View.GONE);
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

                    FilterConfigWriter writer = new FilterConfigWriter(remotePrefs);
                    writer.addChannelKeyword(dialogId, kw);
                    keywords.add(kw);
                    refreshKeywordList();
                    Snackbar.make(findViewById(android.R.id.content),
                            "已添加: " + kw, Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═════════════════════════════════════════════
    // 关键词列表 Adapter
    // ═════════════════════════════════════════════

    class KeywordAdapter extends RecyclerView.Adapter<KeywordAdapter.ViewHolder> {
        private final List<String> items = new ArrayList<>();

        void submitList(List<String> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
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
                new MaterialAlertDialogBuilder(ChannelDetailActivity.this)
                        .setTitle("删除关键词")
                        .setMessage("确定删除关键词「" + keyword + "」？")
                        .setPositiveButton("删除", (d, which) -> {
                            FilterConfigWriter writer = new FilterConfigWriter(remotePrefs);
                            writer.removeChannelKeyword(dialogId, keyword);
                            keywords.remove(keyword);
                            refreshKeywordList();
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
            TextView textKeyword;
            View btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                textKeyword = itemView.findViewById(R.id.text_keyword);
                btnDelete = itemView.findViewById(R.id.btn_delete_keyword);
            }
        }
    }
}
