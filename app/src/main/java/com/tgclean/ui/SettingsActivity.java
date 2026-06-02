package com.tgclean.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.tgclean.App;
import com.tgclean.R;

import io.github.libxposed.service.XposedService;

/**
 * TGClean Material You 设置界面
 *
 * 使用 XposedService.getRemotePreferences() 写入配置到 LSPosed 框架数据库，
 * 而非 APP 本地 SharedPreferences，确保 Hook 进程能通过
 * module.getRemotePreferences() 读取到相同的配置数据。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "TGClean-Settings";
    private static final String PREFS_NAME = "tgclean_config";

    private MaterialSwitch switchEnabled;
    private MaterialSwitch switchRegex;
    private MaterialSwitch switchReactions;
    private TextInputEditText editKeywords;
    private TextInputEditText editWhitelist;
    private TextInputEditText editReactionsEmoji;
    private TextInputEditText editReactionsThreshold;
    private TextInputEditText editChannelRules;
    private MaterialButton btnSave;
    private MaterialButton btnPasteId;
    private LinearLayout layoutWaiting;
    private TextView textWaiting;

    // 本地缓存，用于 UI 初始化
    private boolean localEnabled = true;
    private boolean localRegex = false;
    private boolean localReactionsEnabled = false;
    private String localKeywords = "";
    private String localWhitelist = "";
    private String localChannelRules = "";
    private String localReactionsEmoji = "👎";
    private int localReactionsThreshold = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        setupListeners();

        // 等待 XposedService 就绪后加载配置
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

    private void initViews() {
        switchEnabled = findViewById(R.id.switch_enabled);
        switchRegex = findViewById(R.id.switch_regex);
        switchReactions = findViewById(R.id.switch_reactions);
        editKeywords = findViewById(R.id.edit_keywords);
        editWhitelist = findViewById(R.id.edit_whitelist);
        editReactionsEmoji = findViewById(R.id.edit_reactions_emoji);
        editReactionsThreshold = findViewById(R.id.edit_reactions_threshold);
        editChannelRules = findViewById(R.id.edit_channel_rules);
        btnSave = findViewById(R.id.btn_save);
        btnPasteId = findViewById(R.id.btn_paste_id);
    }

    private void onServiceReady(XposedService service) {
        Log.i(TAG, "XposedService ready, loading config");
        runOnUiThread(() -> {
            // 隐藏等待提示
            if (layoutWaiting != null) layoutWaiting.setVisibility(View.GONE);

            // 从 RemotePreferences 加载现有配置
            try {
                var prefs = service.getRemotePreferences(PREFS_NAME);
                localEnabled = prefs.getBoolean("filter_enabled", true);
                localRegex = prefs.getBoolean("use_regex", false);
                localReactionsEnabled = prefs.getBoolean("reactions_filter_enabled", false);
                localKeywords = prefs.getString("global_keywords", "");
                localWhitelist = prefs.getString("whitelist", "");
                localChannelRules = prefs.getString("channel_rules", "");
                localReactionsEmoji = prefs.getString("reactions_filter_emoji", "👎");
                localReactionsThreshold = prefs.getInt("reactions_filter_threshold", 10);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load remote prefs", t);
            }

            // 回填到 UI
            switchEnabled.setChecked(localEnabled);
            switchRegex.setChecked(localRegex);
            switchReactions.setChecked(localReactionsEnabled);
            editKeywords.setText(localKeywords);
            editWhitelist.setText(localWhitelist);
            editChannelRules.setText(localChannelRules);
            editReactionsEmoji.setText(localReactionsEmoji);
            editReactionsThreshold.setText(String.valueOf(localReactionsThreshold));
        });
    }

    private void setupListeners() {
        btnSave.setOnClickListener(v -> saveSettings());
        btnPasteId.setOnClickListener(v -> pasteChatIdFromClipboard());
    }

    /**
     * 从剪贴板读取聊天ID，追加到分频道规则文本框
     */
    private void pasteChatIdFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Snackbar.make(findViewById(android.R.id.content),
                    "剪贴板为空", Snackbar.LENGTH_SHORT).show();
            return;
        }

        CharSequence clipText = clip.getItemAt(0).getText();
        if (clipText == null) {
            Snackbar.make(findViewById(android.R.id.content),
                    "剪贴板中没有文字内容", Snackbar.LENGTH_SHORT).show();
            return;
        }
        String id = clipText.toString().trim().replaceAll("[^0-9\\-]", "");
        if (id.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content),
                    "剪贴板中未找到有效的聊天ID", Snackbar.LENGTH_SHORT).show();
            return;
        }

        String current = editChannelRules.getText().toString().trim();
        String newRule = id + ":";
        if (current.isEmpty()) {
            editChannelRules.setText(newRule);
        } else {
            editChannelRules.setText(current + "\n" + newRule);
        }
        editChannelRules.setSelection(editChannelRules.getText().length());

        Snackbar.make(findViewById(android.R.id.content),
                "已导入ID " + id + "，请在冒号后输入关键词",
                Snackbar.LENGTH_LONG).show();
    }

    private void saveSettings() {
        XposedService service = App.getService();
        if (service == null) {
            Snackbar.make(findViewById(android.R.id.content),
                    "XposedService 未就绪，请稍后重试",
                    Snackbar.LENGTH_LONG).show();
            return;
        }

        try {
            var prefs = service.getRemotePreferences(PREFS_NAME);
            var editor = prefs.edit();

            editor.putBoolean("filter_enabled", switchEnabled.isChecked());
            editor.putBoolean("use_regex", switchRegex.isChecked());
            editor.putBoolean("reactions_filter_enabled", switchReactions.isChecked());
            editor.putString("global_keywords", editKeywords.getText().toString());
            editor.putString("whitelist", editWhitelist.getText().toString());
            editor.putString("channel_rules", editChannelRules.getText().toString());
            editor.putString("reactions_filter_emoji", editReactionsEmoji.getText().toString());

            try {
                int threshold = Integer.parseInt(editReactionsThreshold.getText().toString());
                editor.putInt("reactions_filter_threshold", threshold);
            } catch (NumberFormatException e) {
                editor.putInt("reactions_filter_threshold", 10);
            }

            editor.apply();

            Log.i(TAG, "Config saved via RemotePreferences: keywords=" + editKeywords.getText().toString());

            Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.config_saved),
                    Snackbar.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save config", t);
            Snackbar.make(findViewById(android.R.id.content),
                    "保存失败: " + t.getMessage(),
                    Snackbar.LENGTH_LONG).show();
        }
    }
}
