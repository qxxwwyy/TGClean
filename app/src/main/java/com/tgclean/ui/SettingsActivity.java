package com.tgclean.ui;

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
 * TGClean Material You 设置界面（简化版）
 *
 * v2: 分频道过滤管理已移至 Telegram 内的 TGCleanSheet（in-app UI）。
 * 此页面仅保留全局设置作为备用入口。
 *
 * 使用 XposedService.getRemotePreferences() 写入配置到 LSPosed 框架数据库，
 * 确保 Hook 进程能通过 module.getRemotePreferences() 读取到相同的配置数据。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "TGClean-Settings";
    private static final String PREFS_NAME = "tgclean_config";

    private MaterialSwitch switchEnabled;
    private MaterialSwitch switchRegex;
    private MaterialSwitch switchReactions;
    private TextInputEditText editKeywords;
    private TextInputEditText editReactionsEmoji;
    private TextInputEditText editReactionsThreshold;
    private MaterialButton btnSave;
    private LinearLayout layoutWaiting;
    private TextView textWaiting;

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

    private void initViews() {
        switchEnabled = findViewById(R.id.switch_enabled);
        switchRegex = findViewById(R.id.switch_regex);
        switchReactions = findViewById(R.id.switch_reactions);
        editKeywords = findViewById(R.id.edit_keywords);
        editReactionsEmoji = findViewById(R.id.edit_reactions_emoji);
        editReactionsThreshold = findViewById(R.id.edit_reactions_threshold);
        btnSave = findViewById(R.id.btn_save);

        // 隐藏分频道规则和白名单（已移至 in-app UI）
        View channelSection = findViewById(R.id.edit_channel_rules);
        View channelLabel = channelSection != null ? (View) channelSection.getParent() : null;
        if (channelLabel instanceof LinearLayout) {
            ((LinearLayout) channelLabel).setVisibility(View.GONE);
        }

        View whitelistSection = findViewById(R.id.edit_whitelist);
        View whitelistLabel = whitelistSection != null ? (View) whitelistSection.getParent() : null;
        if (whitelistLabel instanceof LinearLayout) {
            ((LinearLayout) whitelistLabel).setVisibility(View.GONE);
        }

        // 隐藏粘贴ID按钮
        View pasteBtn = findViewById(R.id.btn_paste_id);
        if (pasteBtn != null) {
            pasteBtn.setVisibility(View.GONE);
        }
    }

    private void onServiceReady(XposedService service) {
        Log.i(TAG, "XposedService ready, loading config");
        runOnUiThread(() -> {
            if (layoutWaiting != null) layoutWaiting.setVisibility(View.GONE);

            try {
                var prefs = service.getRemotePreferences(PREFS_NAME);
                localEnabled = prefs.getBoolean("filter_enabled", true);
                localRegex = prefs.getBoolean("use_regex", false);
                localReactionsEnabled = prefs.getBoolean("reactions_filter_enabled", false);
                localKeywords = prefs.getString("global_keywords", "");
                localReactionsEmoji = prefs.getString("reactions_filter_emoji", "👎");
                localReactionsThreshold = prefs.getInt("reactions_filter_threshold", 10);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load remote prefs", t);
            }

            switchEnabled.setChecked(localEnabled);
            switchRegex.setChecked(localRegex);
            switchReactions.setChecked(localReactionsEnabled);
            editKeywords.setText(localKeywords);
            editReactionsEmoji.setText(localReactionsEmoji);
            editReactionsThreshold.setText(String.valueOf(localReactionsThreshold));
        });
    }

    private void setupListeners() {
        btnSave.setOnClickListener(v -> saveSettings());
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
            editor.putString("reactions_filter_emoji", editReactionsEmoji.getText().toString());

            try {
                int threshold = Integer.parseInt(editReactionsThreshold.getText().toString());
                editor.putInt("reactions_filter_threshold", threshold);
            } catch (NumberFormatException e) {
                editor.putInt("reactions_filter_threshold", 10);
            }

            editor.apply();

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
