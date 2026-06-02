package com.tgclean.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.tgclean.config.FilterConfig;

import io.github.libxposed.api.XposedModule;

/**
 * TGClean In-App UI — BottomSheet 方式
 *
 * 所有过滤管理在 Telegram 内完成，使用 Telegram 原生组件（反射创建）。
 * 基于标准 BottomSheetDialog（非 BaseFragment），避免跨 ClassLoader 问题。
 *
 * 面板类型：
 * - CHANNEL: 当前频道过滤管理（默认）
 * - GLOBAL:  全局关键词设置
 * - PICKER:  频道/群组选择器
 * - REACTIONS: Reactions 过滤设置
 */
public class TGCleanSheet {

    private static final String TAG = "TGClean-Sheet";

    // Telegram ClassLoader（模块运行时获取）
    private static volatile ClassLoader tgClassLoader;
    // XposedModule 引用（用于日志 + RemotePreferences）
    private static volatile XposedModule moduleInstance;
    // 缓存 FilterConfig 防止重复创建 + listener 泄漏
    private static volatile FilterConfig cachedConfig;

    // ═══ 反射缓存的 Telegram 类（仅加载实际使用的）═══
    private static volatile Class<?> cTheme;             // Theme.getColor()
    private static volatile Class<?> cAndroidUtilities;  // AndroidUtilities.dp()
    private static volatile Class<?> cMessagesController; // MessagesController.getInstance()
    private static volatile Class<?> cDialogObject;       // DialogObject.getName/isChannel
    private static volatile Class<?> cTLRPCDialog;        // TL_dialog.id

    private static volatile boolean classesResolved = false;

    // ═══ 初始化（ModuleMain 调用）═══

    public static void init(ClassLoader cl, XposedModule module) {
        tgClassLoader = cl;
        moduleInstance = module;
        module.log(Log.INFO, TAG, "TGCleanSheet initialized");
    }

    // ═══ 公开入口 ═══

    /**
     * 显示当前频道过滤管理面板
     *
     * @param context      Activity context
     * @param dialogId     当前对话 ID
     * @param accountIdx   当前账号索引
     * @param channelName  当前频道名称（可为空，自动解析）
     */
    public static void showChannelSheet(Context context, long dialogId,
                                         int accountIdx, String channelName) {
        if (!ensureClasses()) return;
        try {
            if (channelName == null || channelName.isEmpty()) {
                channelName = resolveDialogName(dialogId, accountIdx);
            }

            new SheetBuilder(context, "channel")
                    .put("dialogId", dialogId)
                    .put("accountIdx", accountIdx)
                    .put("channelName", channelName)
                    .build();
        } catch (Throwable t) {
            logError("showChannelSheet", t);
        }
    }

    /**
     * 显示全局设置面板
     */
    public static void showGlobalSheet(Context context) {
        if (!ensureClasses()) return;
        try {
            new SheetBuilder(context, "global").build();
        } catch (Throwable t) {
            logError("showGlobalSheet", t);
        }
    }

    /**
     * 显示频道选择器面板
     */
    public static void showChannelPicker(Context context, int accountIdx,
                                           Consumer<Long> onSelected) {
        if (!ensureClasses()) return;
        try {
            new SheetBuilder(context, "picker")
                    .put("accountIdx", accountIdx)
                    .setOnChannelSelected(onSelected)
                    .build();
        } catch (Throwable t) {
            logError("showChannelPicker", t);
        }
    }

    /**
     * 显示 Reactions 过滤设置面板
     */
    public static void showReactionsSheet(Context context) {
        if (!ensureClasses()) return;
        try {
            new SheetBuilder(context, "reactions").build();
        } catch (Throwable t) {
            logError("showReactionsSheet", t);
        }
    }

    // ═══ 类加载与缓存 ═══

    private static boolean ensureClasses() {
        if (classesResolved) return true;
        if (tgClassLoader == null) {
            Log.e(TAG, "ClassLoader not initialized! Call TGCleanSheet.init() first.");
            return false;
        }
        try {
            cTheme = tgClassLoader.loadClass("org.telegram.ui.ActionBar.Theme");
            cAndroidUtilities = tgClassLoader.loadClass("org.telegram.messenger.AndroidUtilities");
            cMessagesController = tgClassLoader.loadClass("org.telegram.messenger.MessagesController");
            cDialogObject = tgClassLoader.loadClass("org.telegram.messenger.DialogObject");
            cTLRPCDialog = tgClassLoader.loadClass("org.telegram.tgnet.TLRPC$TL_dialog");

            classesResolved = true;
            if (moduleInstance != null) {
                moduleInstance.log(Log.INFO, TAG, "All Telegram classes resolved successfully");
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to resolve Telegram classes", t);
            return false;
        }
    }

    // ═════════════════════════════════════════════
    // SheetBuilder — 构建 BottomSheet 内容
    // ═════════════════════════════════════════════

    private static class SheetBuilder {
        private final Context context;
        private final String sheetType;
        private final java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        private Consumer<Long> onChannelSelected;
        private Object dialogRef; // BottomSheetDialog 引用，用于 dismiss

        SheetBuilder(Context context, String sheetType) {
            this.context = context;
            this.sheetType = sheetType;
        }

        SheetBuilder put(String key, Object value) {
            params.put(key, value);
            return this;
        }

        SheetBuilder setOnChannelSelected(Consumer<Long> callback) {
            this.onChannelSelected = callback;
            return this;
        }

        /**
         * 关闭当前 sheet 并重新打开同类型面板（操作后刷新 UI）
         */
        private void reopenSheet() {
            try {
                if (dialogRef != null) {
                    Method dismiss = dialogRef.getClass().getMethod("dismiss");
                    dismiss.invoke(dialogRef);
                }
            } catch (Throwable ignored) {}

            // 延迟重新打开，避免动画冲突
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                switch (sheetType) {
                    case "channel":
                        long did = getParamLong("dialogId");
                        int accIdx = getParamInt("accountIdx");
                        String chName = getParamStr("channelName");
                        showChannelSheet(context, did, accIdx, chName);
                        break;
                    case "global":
                        showGlobalSheet(context);
                        break;
                    case "reactions":
                        showReactionsSheet(context);
                        break;
                    default:
                        break;
                }
            }, 200);
        }

        void build() {
            // 使用 Telegram 原生 BottomSheet（org.telegram.ui.ActionBar.BottomSheet）
            // 继承自 android.app.Dialog，不依赖 AppCompat，无资源冲突
            try {
                Class<?> bsClass = tgClassLoader.loadClass(
                        "org.telegram.ui.ActionBar.BottomSheet");
                Constructor<?> ctor = bsClass.getConstructor(Context.class, boolean.class);
                Object sheet = ctor.newInstance(context, false);
                dialogRef = sheet;

                // setCustomView(View)
                Method setCustomView = bsClass.getMethod("setCustomView", View.class);
                View contentView = buildContent();
                setCustomView.invoke(sheet, contentView);

                // show()
                Method show = bsClass.getMethod("show");
                show.invoke(sheet);
            } catch (Throwable t) {
                // Fallback: 使用标准 AlertDialog 展示内容
                try {
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
                    builder.setTitle("TGClean");
                    builder.setMessage("过滤设置面板加载失败，请稍后重试");
                    builder.setPositiveButton("确定", null);
                    builder.show();
                } catch (Throwable ignored) {}
                Log.e(TAG, "Failed to create BottomSheet", t);
            }
        }

        private View buildContent() {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(getThemeColor("key_windowBackgroundWhite"));
            int px = dp(context, 16);
            root.setPadding(px, dp(context, 8), px, dp(context, 8));

            switch (sheetType) {
                case "channel":
                    buildChannelSheet(root);
                    break;
                case "global":
                    buildGlobalSheet(root);
                    break;
                case "picker":
                    buildPickerSheet(root);
                    break;
                case "reactions":
                    buildReactionsSheet(root);
                    break;
            }

            return root;
        }

        // ═══════════════════════════════════════
        // CHANNEL 面板：当前频道过滤管理
        // ═══════════════════════════════════════

        private void buildChannelSheet(LinearLayout root) {
            long dialogId = getParamLong("dialogId");
            String channelName = getParamStr("channelName");

            FilterConfig config = getConfig();

            // 标题
            addTitle(root, "🧹 过滤设置");
            addSubtitle(root, channelName);

            // 分割线
            addDivider(root);

            // 白名单开关
            boolean whitelisted = config.isWhitelisted(dialogId);
            addToggleRow(root, "加入白名单（不过滤此频道）", whitelisted, (checked) -> {
                if (checked) {
                    config.addToWhitelist(dialogId);
                } else {
                    config.removeFromWhitelist(dialogId);
                }
            });

            addDivider(root);

            // 分频道关键词
            Map<Long, Set<String>> rules = config.getChannelKeywords();
            Set<String> channelKw = rules.getOrDefault(dialogId, new HashSet<>());

            addSectionHeader(root, "分频道关键词 (" + channelKw.size() + ")");

            List<String> kwList = new ArrayList<>(channelKw);
            for (int i = 0; i < kwList.size(); i++) {
                final String kw = kwList.get(i);
                boolean last = (i == kwList.size() - 1);
                addKeywordRow(root, kw, !last, () -> {
                    config.removeChannelKeyword(dialogId, kw);
                    reopenSheet();
                });
            }

            // 输入框 + 添加按钮
            addKeywordInput(root, keyword -> {
                config.addChannelKeyword(dialogId, keyword);
                reopenSheet();
            });

            addDivider(root);

            // 快捷入口
            addNavigationRow(root, "全局关键词", () -> showGlobalSheet(context));
            addNavigationRow(root, "添加频道规则", () -> {
                int accountIdx = getParamInt("accountIdx");
                showChannelPicker(context, accountIdx, selectedId -> {
                    // 选择后直接跳转到该频道的过滤面板
                    String selectedName = resolveDialogName(selectedId, accountIdx);
                    showChannelSheet(context, selectedId, accountIdx, selectedName);
                });
            });
            addNavigationRow(root, "Reactions 过滤", () -> showReactionsSheet(context));

            // 底部提示
            addInfoText(root, "全局关键词也对此频道生效");
        }

        // ═══════════════════════════════════════
        // GLOBAL 面板：全局关键词设置
        // ═══════════════════════════════════════

        private void buildGlobalSheet(LinearLayout root) {
            FilterConfig config = getConfig();

            addTitle(root, "全局设置");
            addDivider(root);

            // 总开关
            addToggleRow(root, "启用过滤", config.isEnabled(), (checked) -> {
                config.setEnabled(checked);
            });

            // 正则模式
            addToggleRow(root, "正则模式", config.isUseRegex(), (checked) -> {
                config.setUseRegex(checked);
            });

            addDivider(root);

            // 全局关键词列表
            Set<String> keywords = config.getGlobalKeywords();
            addSectionHeader(root, "全局关键词 (" + keywords.size() + ")");

            List<String> kwList = new ArrayList<>(keywords);
            for (int i = 0; i < kwList.size(); i++) {
                final String kw = kwList.get(i);
                boolean last = (i == kwList.size() - 1);
                addKeywordRow(root, kw, !last, () -> {
                    config.removeGlobalKeyword(kw);
                    reopenSheet();
                });
            }

            // 输入框
            addKeywordInput(root, keyword -> {
                config.addGlobalKeyword(keyword);
                reopenSheet();
            });

            addDivider(root);

            // 白名单管理
            Set<Long> whitelist = config.getWhitelist();
            addSectionHeader(root, "白名单 (" + whitelist.size() + ")");

            if (whitelist.isEmpty()) {
                addInfoText(root, "暂无白名单频道");
            } else {
                List<Long> wlList = new ArrayList<>(whitelist);
                for (int i = 0; i < wlList.size(); i++) {
                    Long id = wlList.get(i);
                    String name = resolveDialogName(id, 0);
                    boolean last = (i == wlList.size() - 1);
                    addDeletableRow(root, name, !last, () -> {
                        config.removeFromWhitelist(id);
                        reopenSheet();
                    });
                }
            }
        }

        // ═══════════════════════════════════════
        // PICKER 面板：频道/群组选择器
        // ═══════════════════════════════════════

        private void buildPickerSheet(LinearLayout root) {
            int accountIdx = getParamInt("accountIdx");
            FilterConfig config = getConfig();
            Map<Long, Set<String>> existingRules = config.getChannelKeywords();

            addTitle(root, "选择频道");
            addDivider(root);

            // 搜索框
            EditText searchEdit = new EditText(context);
            searchEdit.setHint("搜索频道或群组...");
            searchEdit.setBackgroundResource(android.R.drawable.edit_text);
            searchEdit.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            searchEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            searchEdit.setTextColor(getThemeColor("key_windowBackgroundWhiteBlackText"));
            searchEdit.setHintTextColor(getThemeColor("key_windowBackgroundWhiteHintText"));
            root.addView(searchEdit, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            addDivider(root);

            // 频道列表容器（可动态更新）
            LinearLayout listContainer = new LinearLayout(context);
            listContainer.setOrientation(LinearLayout.VERTICAL);
            root.addView(listContainer, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // 获取对话列表并填充
            List<DialogEntry> dialogs = loadDialogs(accountIdx);
            FilterConfig finalConfig = config;

            Runnable refreshList = () -> populateChannelList(
                    listContainer, dialogs, searchEdit.getText().toString(),
                    existingRules, finalConfig, accountIdx);

            // 初始填充
            refreshList.run();

            // 搜索监听
            searchEdit.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    refreshList.run();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        // ═══════════════════════════════════════
        // REACTIONS 面板
        // ═══════════════════════════════════════

        private void buildReactionsSheet(LinearLayout root) {
            FilterConfig config = getConfig();

            addTitle(root, "Reactions 过滤");
            addInfoText(root, "当消息收到指定 emoji 反应且数量超过阈值时，自动过滤该消息");
            addDivider(root);

            addToggleRow(root, "启用 Reactions 过滤",
                    config.isReactionsFilterEnabled(), (checked) -> {
                config.setReactionsFilterEnabled(checked);
            });

            addDivider(root);

            addSectionHeader(root, "过滤设置");

            // Emoji 输入
            addLabeledInput(root, "触发 Emoji", config.getReactionsFilterEmoji(), value -> {
                config.setReactionsFilterEmoji(value);
            });

            // 阈值输入
            addLabeledInput(root, "阈值（最少多少个反应）",
                    String.valueOf(config.getReactionsFilterThreshold()), value -> {
                try {
                    config.setReactionsFilterThreshold(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {}
            });
        }

        // ═══════════════════════════════════════
        // 频道列表填充（Picker 内部使用）
        // ═══════════════════════════════════════

        private void populateChannelList(LinearLayout container,
                                            List<DialogEntry> dialogs,
                                            String query,
                                            Map<Long, Set<String>> existingRules,
                                            FilterConfig config,
                                            int accountIdx) {
            container.removeAllViews();

            String lowerQuery = query.toLowerCase();
            boolean hasResult = false;

            for (DialogEntry entry : dialogs) {
                if (!lowerQuery.isEmpty() && !entry.name.toLowerCase().contains(lowerQuery)) {
                    continue;
                }

                hasResult = true;
                boolean hasRules = existingRules.containsKey(entry.dialogId);
                String badge = hasRules ? " ✓ 已配置" : "";

                // 根据类型显示不同颜色标记
                String prefix;
                if (entry.isChannel) {
                    prefix = "📢 ";
                } else if (entry.isGroup) {
                    prefix = "👥 ";
                } else {
                    prefix = "👤 ";
                }

                addClickableRow(container,
                        prefix + entry.name + badge,
                        () -> {
                            // 关闭当前 picker 并回调
                            try {
                                if (dialogRef != null) {
                                    Method dismiss = dialogRef.getClass().getMethod("dismiss");
                                    dismiss.invoke(dialogRef);
                                }
                            } catch (Throwable ignored) {}
                            if (onChannelSelected != null) {
                                onChannelSelected.accept(entry.dialogId);
                            }
                        },
                        false);

                // 如果已配置，显示关键词数量
                if (hasRules) {
                    Set<String> kw = existingRules.get(entry.dialogId);
                    addInfoText(container, "    " + kw.size() + " 条关键词规则");
                }
            }

            if (!hasResult) {
                addInfoText(container, "没有找到匹配的频道或群组");
            }
        }

        // ═══════════════════════════════════════
        // 工具方法
        // ═══════════════════════════════════════

        private FilterConfig getConfig() {
            if (cachedConfig == null) {
                cachedConfig = new FilterConfig(moduleInstance);
            }
            return cachedConfig;
        }

        private long getParamLong(String key) {
            Object v = params.get(key);
            return v instanceof Long ? (Long) v : 0;
        }

        private int getParamInt(String key) {
            Object v = params.get(key);
            return v instanceof Integer ? (Integer) v : 0;
        }

        private String getParamStr(String key) {
            Object v = params.get(key);
            return v instanceof String ? (String) v : "";
        }
    }

    // ═════════════════════════════════════════════
    // UI 组件工厂 — 纯 Android View（不依赖 Telegram Cell）
    // ═════════════════════════════════════════════
    //
    // 设计决策：使用标准 Android View 而非 Telegram Cell 反射。
    // 原因：
    // 1. TextCheckCell/TextCell 的 setText 需要反射操作内部 View，fragile
    // 2. 标准 View 在 BottomSheetDialog 中布局可控
    // 3. 使用 Telegram Theme 颜色保持视觉一致

    private static int getThemeColor(String key) {
        try {
            if (cTheme == null) return 0xFF000000;
            Method getColor = cTheme.getMethod("getColor", String.class);
            return (int) getColor.invoke(null, key);
        } catch (Throwable t) {
            // Fallback 色值（深色/浅色主题通用）
            switch (key) {
                case "key_windowBackgroundWhite": return 0xFFFFFFFF;
                case "key_windowBackgroundWhiteBlackText": return 0xFF000000;
                case "key_windowBackgroundWhiteGrayText4":
                case "key_windowBackgroundWhiteHintText": return 0xFF808080;
                case "key_windowBackgroundWhiteGrayText2": return 0xFF999999;
                case "key_windowBackgroundWhiteBlueText": return 0xFF2196F3;
                case "key_divider": return 0xFFE0E0E0;
                case "key_listSelector": return 0x10FFFFFF;
                default: return 0xFF000000;
            }
        }
    }

    private static int dp(Context ctx, float value) {
        try {
            if (cAndroidUtilities != null) {
                Method dpMethod = cAndroidUtilities.getMethod("dp", float.class);
                return (int) dpMethod.invoke(null, value);
            }
        } catch (Throwable ignored) {}
        // Fallback
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    // ─── 标题 ───

    private static void addTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        tv.setTextColor(getThemeColor("key_windowBackgroundWhiteBlackText"));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(parent.getContext(), 4), dp(parent.getContext(), 8),
                dp(parent.getContext(), 4), dp(parent.getContext(), 4));
        parent.addView(tv);
    }

    private static void addSubtitle(LinearLayout parent, String text) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(getThemeColor("key_windowBackgroundWhiteGrayText4"));
        tv.setPadding(dp(parent.getContext(), 4), 0,
                dp(parent.getContext(), 4), dp(parent.getContext(), 8));
        parent.addView(tv);
    }

    // ─── 分组标题 ───

    private static void addSectionHeader(LinearLayout parent, String text) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(getThemeColor("key_windowBackgroundWhiteBlueText"));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(parent.getContext(), 4), dp(parent.getContext(), 12),
                dp(parent.getContext(), 4), dp(parent.getContext(), 4));
        parent.addView(tv);
    }

    // ─── 信息文本 ───

    private static void addInfoText(LinearLayout parent, String text) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTextColor(getThemeColor("key_windowBackgroundWhiteGrayText4"));
        tv.setPadding(dp(parent.getContext(), 4), dp(parent.getContext(), 6),
                dp(parent.getContext(), 4), dp(parent.getContext(), 6));
        parent.addView(tv);
    }

    // ─── 分割线 ───

    private static void addDivider(LinearLayout parent) {
        View divider = new View(parent.getContext());
        divider.setBackgroundColor(getThemeColor("key_divider"));
        int h = 1;
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        int margin = dp(parent.getContext(), 8);
        ((LinearLayout.LayoutParams) divider.getLayoutParams()).topMargin = margin / 2;
        ((LinearLayout.LayoutParams) divider.getLayoutParams()).bottomMargin = margin / 2;
        parent.addView(divider);
    }

    // ─── 可点击行 ───

    private static void addClickableRow(LinearLayout parent, String text,
                                         Runnable onClick, boolean showDivider) {
        FrameLayout row = new FrameLayout(parent.getContext());
        row.setPadding(dp(parent.getContext(), 8), dp(parent.getContext(), 12),
                dp(parent.getContext(), 8), dp(parent.getContext(), 12));
        row.setBackgroundColor(getThemeColor("key_listSelector"));

        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTextColor(getThemeColor("key_windowBackgroundWhiteBlackText"));
        row.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        // 右箭头
        TextView arrow = new TextView(parent.getContext());
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        arrow.setTextColor(getThemeColor("key_windowBackgroundWhiteGrayText4"));
        row.addView(arrow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END));

        row.setOnClickListener(v -> onClick.run());

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (showDivider) addDivider(parent);
    }

    private static void addNavigationRow(LinearLayout parent, String text, Runnable onClick) {
        addClickableRow(parent, text, onClick, false);
        addDivider(parent);
    }

    // ─── 可删除行 ───

    private static void addDeletableRow(LinearLayout parent, String text,
                                         boolean showDivider, Runnable onDelete) {
        FrameLayout row = new FrameLayout(parent.getContext());
        row.setPadding(dp(parent.getContext(), 8), dp(parent.getContext(), 10),
                dp(parent.getContext(), 8), dp(parent.getContext(), 10));

        TextView tv = new TextView(parent.getContext());
        tv.setText("📌 " + text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(getThemeColor("key_windowBackgroundWhiteBlackText"));
        row.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        // 删除按钮
        TextView delBtn = new TextView(parent.getContext());
        delBtn.setText("✕");
        delBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        delBtn.setTextColor(getThemeColor("key_text_RedRegular") != 0
                ? getThemeColor("key_text_RedRegular") : 0xFFFF4444);
        delBtn.setPadding(dp(parent.getContext(), 8), dp(parent.getContext(), 4),
                dp(parent.getContext(), 8), dp(parent.getContext(), 4));
        delBtn.setOnClickListener(v -> onDelete.run());
        row.addView(delBtn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END));

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (showDivider) {
            View thinDivider = new View(parent.getContext());
            thinDivider.setBackgroundColor(getThemeColor("key_divider"));
            thinDivider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            int m = dp(parent.getContext(), 40);
            ((LinearLayout.LayoutParams) thinDivider.getLayoutParams()).leftMargin = m;
            parent.addView(thinDivider);
        }
    }

    // ─── 关键词行（带删除）───

    private static void addKeywordRow(LinearLayout parent, String keyword,
                                       boolean showDivider, Runnable onDelete) {
        addDeletableRow(parent, keyword, showDivider, onDelete);
    }

    // ─── Toggle 行 ───

    private static void addToggleRow(LinearLayout parent, String text,
                                      boolean checked, Consumer<Boolean> onChange) {
        FrameLayout row = new FrameLayout(parent.getContext());
        row.setPadding(dp(parent.getContext(), 8), dp(parent.getContext(), 12),
                dp(parent.getContext(), 8), dp(parent.getContext(), 12));

        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTextColor(getThemeColor("key_windowBackgroundWhiteBlackText"));
        row.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        // Switch
        android.widget.Switch sw = new android.widget.Switch(parent.getContext());
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));
        row.addView(sw, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END));

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    // ─── 关键词输入行 ───

    private static void addKeywordInput(LinearLayout parent, Consumer<String> onAdd) {
        Context ctx = parent.getContext();
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));

        EditText edit = new EditText(ctx);
        edit.setHint("输入关键词...");
        edit.setBackgroundResource(android.R.drawable.edit_text);
        edit.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        edit.setSingleLine(true);
        edit.setTextColor(getThemeColor("key_windowBackgroundWhiteBlackText"));
        edit.setHintTextColor(getThemeColor("key_windowBackgroundWhiteHintText"));
        inputRow.addView(edit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView addBtn = new TextView(ctx);
        addBtn.setText("添加");
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        addBtn.setTextColor(0xFFFFFFFF);
        addBtn.setBackgroundColor(getThemeColor("key_windowBackgroundWhiteBlueText") != 0
                ? getThemeColor("key_windowBackgroundWhiteBlueText") : 0xFF2196F3);
        addBtn.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setOnClickListener(v -> {
            String kw = edit.getText().toString().trim();
            if (!kw.isEmpty()) {
                onAdd.accept(kw);
                edit.setText("");
            }
        });
        inputRow.addView(addBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(inputRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    // ─── 带标签的输入行 ───

    private static void addLabeledInput(LinearLayout parent, String label,
                                         String initialValue, Consumer<String> onSave) {
        Context ctx = parent.getContext();
        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        lbl.setTextColor(getThemeColor("key_windowBackgroundWhiteGrayText4"));
        lbl.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 4));
        parent.addView(lbl);

        EditText edit = new EditText(ctx);
        edit.setText(initialValue);
        edit.setBackgroundResource(android.R.drawable.edit_text);
        edit.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        edit.setSingleLine(true);
        edit.setTextColor(getThemeColor("key_windowBackgroundWhiteBlackText"));

        edit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String value = edit.getText().toString().trim();
                if (!value.isEmpty()) {
                    onSave.accept(value);
                }
            }
        });

        parent.addView(edit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    // ═════════════════════════════════════════════
    // 数据层：对话列表获取
    // ═════════════════════════════════════════════

    private static class DialogEntry {
        long dialogId;
        String name;
        boolean isChannel;
        boolean isGroup;
    }

    /**
     * 从 MessagesController 获取所有对话列表
     */
    private static List<DialogEntry> loadDialogs(int accountIdx) {
        List<DialogEntry> result = new ArrayList<>();
        try {
            Object mc = cMessagesController.getMethod("getInstance", int.class)
                    .invoke(null, accountIdx);

            // getAllDialogs() 返回 ArrayList<TLRPC.TL_dialog>
            Method getAllDialogs = cMessagesController.getMethod("getAllDialogs");
            Object dialogs = getAllDialogs.invoke(mc);

            if (dialogs instanceof List) {
                for (Object dialog : (List<?>) dialogs) {
                    try {
                        long dialogId = getDialogId(dialog);
                        if (dialogId == 0) continue;

                        // 只显示频道和群组，不显示私聊
                        if (dialogId > 0) continue;

                        String name = resolveDialogName(dialogId, accountIdx);
                        if (name == null || name.isEmpty()) continue;

                        boolean isChannel = isChannel(dialogId, accountIdx);
                        boolean isGroup = !isChannel && dialogId < 0;

                        DialogEntry entry = new DialogEntry();
                        entry.dialogId = dialogId;
                        entry.name = name;
                        entry.isChannel = isChannel;
                        entry.isGroup = isGroup;
                        result.add(entry);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load dialogs", t);
        }

        // 按名称排序
        Collections.sort(result, Comparator.comparing(e -> e.name));
        return result;
    }

    private static long getDialogId(Object dialog) throws Exception {
        try {
            Field f = dialog.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return f.getLong(dialog);
        } catch (NoSuchFieldException e) {
            return 0;
        }
    }

    private static boolean isChannel(long dialogId, int accountIdx) {
        try {
            Method isChannelMethod = cDialogObject.getMethod("isChannel", long.class);
            return (boolean) isChannelMethod.invoke(null, dialogId);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解析对话 ID 为名称
     */
    static String resolveDialogName(long dialogId, int accountIdx) {
        try {
            // DialogObject.getName(int currentAccount, long dialogId)
            Method getName = cDialogObject.getMethod("getName", int.class, long.class);
            return (String) getName.invoke(null, accountIdx, dialogId);
        } catch (Throwable t) {
            try {
                // Fallback: DialogObject.getName(long dialogId)
                Method getName = cDialogObject.getMethod("getName", long.class);
                return (String) getName.invoke(null, dialogId);
            } catch (Throwable t2) {
                return String.valueOf(dialogId);
            }
        }
    }

    // ═════════════════════════════════════════════
    // 日志
    // ═════════════════════════════════════════════

    private static void logError(String method, Throwable t) {
        Log.e(TAG, "Error in " + method, t);
        if (moduleInstance != null) {
            moduleInstance.log(Log.ERROR, TAG, "Error in " + method + ": " + t.getMessage());
        }
    }
}
