package com.tgclean.hooks;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.tgclean.config.FilterConfig;
import com.tgclean.config.ReactionsRule;

import io.github.libxposed.api.XposedModule;

/**
 * ChatActivity Hook — 菜单注入 + 频道自动发现
 *
 * 1. 注入「📋 复制聊天ID」「⚡ 表情过滤」菜单项
 * 2. 首次 onResume 时一次性扫描 TG 全部频道列表，批量广播到 TGClean App
 * 3. 后续 onResume 只增量更新当前频道
 * 4. 表情过滤：在 TG 内直接为当前频道配置白/黑名单规则（弹窗 + 表情快速选择），
 *    保存后经广播由 TGClean App 写入 remote prefs，实时推送回本进程生效
 */
public class ChatHelperHook {
    private static final String TAG = "TGClean-ChatHelper";

    private static final int MENU_ID_COPY_CHAT_ID = 999002;
    private static final int MENU_ID_REACTIONS = 999003;
    private static final String TAG_INJECTED = "tgclean_injected";

    // 表情过滤规则广播（TG 进程 → TGClean App 进程）
    private static final String ACTION_REACTIONS_RULE = "com.tgclean.ACTION_REACTIONS_RULE";
    // App 写入成功后的回执（App 进程 → TG 进程）
    private static final String ACTION_REACTIONS_RULE_SAVED = "com.tgclean.ACTION_REACTIONS_RULE_SAVED";

    // BroadcastReceiver 目标
    private static final String TG_CLEAN_PACKAGE = "com.tgclean";
    private static final String RECEIVER_CLASS = "com.tgclean.receiver.ChannelReceiver";
    private static final String ACTION = "com.tgclean.ACTION_CHANNEL_DISCOVERED";

    private static volatile Field cachedHeaderItemField;
    private static volatile Field cachedDialogIdField;
    private static volatile Field cachedCurrentAccountField;

    // 防止高频写入（同一频道 30 秒内不重复发送）
    private static volatile long lastReportedDialogId = 0;
    private static volatile long lastReportTime = 0;
    private static final long REPORT_COOLDOWN_MS = 30_000;

    // 首次全量扫描标记（per account：多账号各自全量扫描一次）
    private static final java.util.Set<Integer> scannedAccounts =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void hook(ClassLoader cl, XposedModule module, FilterConfig config) {
        try {
            hookOnResume(cl, module, config);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to setup ChatHelperHook", t);
        }
    }

    private static void hookOnResume(ClassLoader cl, XposedModule module, FilterConfig config) {
        try {
            Class<?> chatActivityClass = cl.loadClass("org.telegram.ui.ChatActivity");
            Method onResume = chatActivityClass.getDeclaredMethod("onResume");
            onResume.setAccessible(true);

            module.hook(onResume).intercept(chain -> {
                chain.proceed();
                try {
                    Object chatActivity = chain.getThisObject();
                    ClassLoader tgCl = chatActivity.getClass().getClassLoader();
                    Context context = getActivityContext(chatActivity, tgCl);
                    if (context == null) return null;

                    long dialogId = getDialogId(chatActivity, tgCl);
                    if (dialogId == 0) return null;

                    int accountIdx = getCurrentAccount(chatActivity, tgCl);

                    // 首次触发：一次性扫描 TG 全部频道
                    // ⚠️ 置位必须在扫描成功后才做：冷启动直接恢复聊天页时，
                    // dialogsChannelsOnly 可能还没加载（loadDialogs 晚于首个 onResume），
                    // 无条件置 true 会导致扫描永久跳过。改为成功路径内置位（返回 boolean）。
                    if (!scannedAccounts.contains(accountIdx)) {
                        if (scanAllChannels(context, accountIdx, tgCl, module)) {
                            scannedAccounts.add(accountIdx);
                        }
                    }

                    // 增量上报当前频道
                    String channelName = resolveChannelName(dialogId, accountIdx, tgCl);
                    reportChannelViaBroadcast(context, dialogId, channelName);

                    // 注入菜单
                    injectIfNeeded(chatActivity, tgCl, module, config);
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "Error in onResume hook", t);
                }
                return null;
            });

            module.log(Log.INFO, TAG, "Hooked ChatActivity.onResume (menu + channel discovery)");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook onResume", t);
        }
    }

    /**
     * 一次性扫描 Telegram 全部频道/超级群组列表，批量发送到 TGClean App
     *
     * 优先走 MessagesController.getAllDialogs() + DialogObject.isChannel() 过滤
     * （同时覆盖广播频道和 megagroup，且不依赖 sortDialogs 时机）；
     * 失败时回退读取 dialogsChannelsOnly 字段（仅广播频道，megagroup 在
     * dialogsGroupsOnly 中）。每个 Dialog.id 用 DialogObject.getName() 取名。
     * 全部频道打包成 JSON 放进单个广播的 extras，避免主线程逐个
     * sendBroadcast（每个都是一次 binder IPC，几百频道会卡 UI）。
     *
     * @return true=扫描完成（列表非空且已广播），false=列表未加载，下次 onResume 重试
     */
    private static boolean scanAllChannels(Context context, int accountIdx,
                                        ClassLoader cl, XposedModule module) {
        try {
            // 获取 MessagesController 单例
            Class<?> mcClass = cl.loadClass("org.telegram.messenger.MessagesController");
            Method getInstance = mcClass.getMethod("getInstance", int.class);
            Object mc = getInstance.invoke(null, accountIdx);

            List<?> channels = null;

            // 路径1：getAllDialogs() + isChannel（频道 + megagroup）
            try {
                Class<?> dialogClass = cl.loadClass("org.telegram.tgnet.TLRPC$Dialog");
                Method getAll = mcClass.getMethod("getAllDialogs");
                Method isChannel = cl.loadClass("org.telegram.messenger.DialogObject")
                        .getMethod("isChannel", dialogClass);
                List<?> all = (List<?>) getAll.invoke(mc);
                if (all != null && !all.isEmpty()) {
                    List<Object> filtered = new ArrayList<>();
                    for (Object d : all) {
                        if (d != null && Boolean.TRUE.equals(isChannel.invoke(null, d))) {
                            filtered.add(d);
                        }
                    }
                    if (!filtered.isEmpty()) channels = filtered;
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "getAllDialogs path failed: " + t.getMessage());
            }

            // 路径2：回退 dialogsChannelsOnly（仅广播频道）
            if (channels == null) {
                Field channelsField = findFieldInHierarchy(mcClass, "dialogsChannelsOnly");
                if (channelsField == null) {
                    module.log(Log.WARN, TAG, "dialogsChannelsOnly field not found");
                    return false;
                }
                channelsField.setAccessible(true);
                channels = (List<?>) channelsField.get(mc);
            }

            if (channels == null || channels.isEmpty()) {
                module.log(Log.INFO, TAG, "channel list empty, will retry on next onResume");
                return false;
            }

            // 加载 DialogObject
            Class<?> dialogObjClass = cl.loadClass("org.telegram.messenger.DialogObject");
            Method getName = dialogObjClass.getMethod("getName", int.class, long.class);

            long now = System.currentTimeMillis();
            org.json.JSONArray batch = new org.json.JSONArray();

            for (Object dialog : channels) {
                try {
                    // TLRPC.Dialog.id 是 long 类型
                    Field idField = findFieldInHierarchy(dialog.getClass(), "id");
                    if (idField == null) continue;
                    idField.setAccessible(true);
                    long dId = idField.getLong(dialog);

                    if (dId == 0) continue;

                    // 获取频道名
                    String name;
                    try {
                        name = (String) getName.invoke(null, accountIdx, dId);
                    } catch (Throwable t) {
                        name = String.valueOf(dId);
                    }

                    org.json.JSONObject ch = new org.json.JSONObject();
                    ch.put("id", dId);
                    ch.put("name", name != null ? name : String.valueOf(dId));
                    ch.put("last_seen", now);
                    batch.put(ch);
                } catch (Throwable t) {
                    // 跳过异常的单个频道
                }
            }

            // 单个批量广播代替 N 次单发
            Intent intent = new Intent(ACTION);
            intent.setComponent(new ComponentName(TG_CLEAN_PACKAGE, RECEIVER_CLASS));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); // 更新安装后 App stopped 态仍可送达
            intent.putExtra("batch_json", batch.toString());
            context.sendBroadcast(intent);

            module.log(Log.INFO, TAG, "Batch scan complete: " + batch.length() + " channels sent (1 broadcast)");
            return true;

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to scan all channels: " + t.getMessage());
            return false;
        }
    }

    /**
     * 通过 component-explicit broadcast 发送频道信息到 TGClean App
     */
    private static void reportChannelViaBroadcast(Context context, long dialogId, String name) {
        long now = System.currentTimeMillis();

        // 同一频道 30 秒内不重复发送
        if (dialogId == lastReportedDialogId && (now - lastReportTime) < REPORT_COOLDOWN_MS) {
            return;
        }

        try {
            Intent intent = new Intent(ACTION);
            intent.setComponent(new ComponentName(TG_CLEAN_PACKAGE, RECEIVER_CLASS));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); // 更新安装后 App stopped 态仍可送达
            intent.putExtra("dialog_id", dialogId);
            intent.putExtra("name", name != null ? name : String.valueOf(dialogId));
            intent.putExtra("last_seen", now);

            context.sendBroadcast(intent);

            lastReportedDialogId = dialogId;
            lastReportTime = now;

            Log.i(TAG, "Broadcast channel sent: " + dialogId + " (" + name + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to send channel broadcast: " + t.getMessage());
        }
    }

    /**
     * 检查是否已注入，未注入则注入菜单项
     */
    private static void injectIfNeeded(Object chatActivity, ClassLoader cl,
                                       XposedModule module, FilterConfig config) {
        try {
            Object headerItem = getHeaderItem(chatActivity);
            if (headerItem == null) return;

            if (TAG_INJECTED.equals(View.class.cast(headerItem).getTag())) return;

            Context context = getActivityContext(chatActivity, cl);
            if (context == null) return;

            long dialogId = getDialogId(chatActivity, cl);
            if (dialogId == 0) return;

            // 通过全部校验后才打注入标记：早期版本先打标记后校验，
            // 某次 onResume 恰逢 context/dialogId 暂不可用时该 headerItem
            // 永久失去注入机会（发布前性能审计 P2-4）
            View.class.cast(headerItem).setTag(TAG_INJECTED);

            int accountIdx = getCurrentAccount(chatActivity, cl);
            String channelName = resolveChannelName(dialogId, accountIdx, cl);

            Class<?> headerItemClass = headerItem.getClass();
            Method addSubItem = findMethodInHierarchy(headerItemClass, "addSubItem",
                    int.class, int.class, CharSequence.class);
            if (addSubItem == null) {
                module.log(Log.WARN, TAG, "addSubItem(int,int,CharSequence) not found");
                View.class.cast(headerItem).setTag(null);
                return;
            }
            addSubItem.setAccessible(true);

            Object copyItem = addSubItem.invoke(headerItem, MENU_ID_COPY_CHAT_ID, 0,
                    "📋 复制聊天ID (" + dialogId + ")");
            View copyView = (View) copyItem;
            copyView.setOnClickListener(v -> showAndCopyDialogId(context, dialogId, channelName));

            // 表情过滤菜单项（标题反映当前规则状态）
            View reactionsView = (View) addSubItem.invoke(headerItem, MENU_ID_REACTIONS, 0,
                    buildReactionsMenuTitle(config, dialogId));
            reactionsView.setOnClickListener(v -> showReactionsFilterDialog(
                    context, dialogId, channelName, config, module, newTitle ->
                            updateMenuItemText(reactionsView, newTitle)));

            module.log(Log.INFO, TAG, "Injected menus (dialogId=" + dialogId
                    + ", channel=" + channelName + ")");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "injectIfNeeded error: " + t.getMessage());
        }
    }

    /** 菜单标题：⚡ 表情过滤：❤️≥10·👍≤20·只显 / ⚡ 表情过滤（未启用） */
    private static String buildReactionsMenuTitle(FilterConfig config, long dialogId) {
        ReactionsRule rule = config.getReactionsChannelRules().get(dialogId);
        if (rule != null && rule.enabled) {
            return "⚡ 表情过滤：" + rule.describe();
        }
        return "⚡ 表情过滤（未启用）";
    }

    /** 反射调用 ActionBarMenuSubItem.setTextAndIcon(CharSequence, int) 更新菜单文字 */
    private static void updateMenuItemText(View menuItem, CharSequence text) {
        try {
            Method m = findMethodInHierarchy(menuItem.getClass(), "setTextAndIcon",
                    CharSequence.class, int.class);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(menuItem, text, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    // ═════════════════════════════════════════════
    // 表情过滤配置弹窗（TG 进程内，仅 framework 控件，避免 ClassLoader 冲突）
    // ═════════════════════════════════════════════

    private static final String[] QUICK_EMOJIS = {"👍", "👎", "❤️", "🔥", "🥰", "😂", "🤩", "💯"};

    private static void showReactionsFilterDialog(Context context, long dialogId,
                                                  String channelName, FilterConfig config,
                                                  XposedModule module,
                                                  java.util.function.Consumer<String> titleUpdater) {
        ReactionsRule current = config.getReactionsChannelRules().get(dialogId);

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        root.setPadding(pad, dp(context, 6), pad, dp(context, 4));
        scroll.addView(root);

        CheckBox checkEnabled = new CheckBox(context);
        checkEnabled.setText("启用本频道表情过滤");
        root.addView(checkEnabled);

        TextView modeLabel = new TextView(context);
        modeLabel.setText("过滤模式");
        modeLabel.setPadding(0, dp(context, 8), 0, 0);
        root.addView(modeLabel);

        RadioGroup modeGroup = new RadioGroup(context);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton radioWhite = new RadioButton(context);
        radioWhite.setText("白名单：只显示达标消息（找高价值资源）");
        RadioButton radioBlack = new RadioButton(context);
        radioBlack.setText("黑名单：隐藏达标消息（如踩多了就隐藏）");
        modeGroup.addView(radioWhite);
        modeGroup.addView(radioBlack);
        root.addView(modeGroup);

        // ── 目标表情（≥ 阈值）──
        root.addView(sectionLabel(context, "目标表情（数量达到）"));
        EditText editEmoji = new EditText(context);
        editEmoji.setHint("表情（点下方快速选择，或手动输入）");
        root.addView(wrapHScroll(context,
                emojiQuickRow(context, emoji -> editEmoji.setText(emoji), null)));
        root.addView(editEmoji);
        EditText editMin = new EditText(context);
        editMin.setHint("数量 ≥（如 10）");
        editMin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(editMin);

        // ── 负面表情（≤ 上限，仅白名单）──
        TextView label2 = sectionLabel(context, "负面表情上限（仅白名单模式，可选）");
        root.addView(label2);
        EditText editEmoji2 = new EditText(context);
        editEmoji2.setHint("留空 = 不启用（如：踩超过 20 个则排除）");
        LinearLayout row2 = emojiQuickRow(context, emoji -> editEmoji2.setText(emoji), "✖ 清除");
        root.addView(wrapHScroll(context, row2));
        root.addView(editEmoji2);
        EditText editMax = new EditText(context);
        editMax.setHint("数量 ≤（如 20）");
        editMax.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(editMax);

        // ── 检索深度（级联自动向前翻找范围）──
        // 单行 Spinner：6 个纵向单选曾把弹窗撑到按钮栏被挤出可视区
        // （用户实测"保存按钮没了"），下拉选择一行搞定
        root.addView(sectionLabel(context, "检索深度（筛选后剩太少时，自动向前翻找的范围）"));
        int globalDepth = config.getReactionsSearchDepth();
        final String[] depthChoices = new String[ReactionsRule.DEPTH_PRESETS.length + 1];
        depthChoices[0] = "跟随全局默认（当前 " + ReactionsRule.formatDepth(globalDepth) + " 条）";
        for (int i = 0; i < ReactionsRule.DEPTH_PRESETS.length; i++) {
            depthChoices[i + 1] = ReactionsRule.formatDepth(ReactionsRule.DEPTH_PRESETS[i]) + " 条";
        }
        final int[] selectedDepth = {0}; // 下标 0 = 跟随默认 → maxDepth 0
        android.widget.Spinner depthSpinner = new android.widget.Spinner(context);
        android.widget.ArrayAdapter<String> depthAdapter = new android.widget.ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, depthChoices);
        depthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        depthSpinner.setAdapter(depthAdapter);
        depthSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                selectedDepth[0] = pos;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        int presetIdx = 0; // 预填：规则深度匹配预设则选中对应项，否则跟随默认
        if (current != null) {
            for (int i = 0; i < ReactionsRule.DEPTH_PRESETS.length; i++) {
                if (ReactionsRule.DEPTH_PRESETS[i] == current.maxDepth) {
                    presetIdx = i + 1;
                    break;
                }
            }
        }
        depthSpinner.setSelection(presetIdx);
        root.addView(depthSpinner);

        TextView hint = new TextView(context);
        hint.setText("提示：建议用快速选择以确保表情编码匹配（生效时机见保存后的提示）");
        hint.setTextSize(12);
        hint.setPadding(0, dp(context, 10), 0, 0);
        root.addView(hint);

        // ── 预填当前规则 ──
        if (current != null) {
            checkEnabled.setChecked(current.enabled);
            if (current.whitelistMode) radioWhite.setChecked(true);
            else radioBlack.setChecked(true);
            editEmoji.setText(current.emoji != null ? current.emoji : "");
            editMin.setText(String.valueOf(current.minCount));
            editEmoji2.setText(current.emoji2 != null ? current.emoji2 : "");
            editMax.setText(String.valueOf(current.maxCount));
        } else {
            radioWhite.setChecked(true);
            editMin.setText("10");
            editMax.setText("20");
        }

        // 模式切换时启停负面表情区
        final View[] section2 = {label2, editEmoji2, editMax, row2};
        Runnable updateSection2 = () -> {
            boolean white = radioWhite.isChecked();
            for (View v : section2) v.setAlpha(white ? 1f : 0.4f);
            editEmoji2.setEnabled(white);
            editMax.setEnabled(white);
            for (int i = 0; i < row2.getChildCount(); i++) {
                row2.getChildAt(i).setEnabled(white);
            }
        };
        modeGroup.setOnCheckedChangeListener((group, id) -> updateSection2.run());
        updateSection2.run();

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(context)
                .setTitle("⚡ 表情过滤")
                .setMessage(channelName + "  (" + dialogId + ")")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        // 覆盖默认点击行为：校验失败不关闭
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                boolean enabled = checkEnabled.isChecked();
                boolean whitelist = radioWhite.isChecked();
                String emoji = editEmoji.getText().toString().trim();
                String emoji2 = editEmoji2.isEnabled()
                        ? editEmoji2.getText().toString().trim() : "";
                int minCount = parseIntOr(editMin.getText().toString(), -1);
                int maxCount = parseIntOr(editMax.getText().toString(), -1);

                if (enabled) {
                    // 校验失败定位到具体输入框（setError），不再用笼统 Toast（UX 复核 P1-6）
                    if (emoji.isEmpty()) {
                        editEmoji.setError("请选择或输入目标表情");
                        editEmoji.requestFocus();
                        return;
                    }
                    if (minCount < 0) {
                        editMin.setError("请输入 ≥ 0 的数字");
                        editMin.requestFocus();
                        return;
                    }
                    if (whitelist && !emoji2.isEmpty() && maxCount < 0) {
                        editMax.setError("请输入 ≥ 0 的数字");
                        editMax.requestFocus();
                        return;
                    }
                    if (emoji2.equals(emoji)) {
                        editEmoji2.setError("两个表情不能相同");
                        editEmoji2.requestFocus();
                        return;
                    }
                }

                int maxDepth = selectedDepth[0] > 0
                        ? ReactionsRule.DEPTH_PRESETS[selectedDepth[0] - 1] : 0; // 0 = 跟随全局默认

                // 令牌随写请求带回 App 端校验（防伪造，审计 M-1）；
                // nonce 供回执防伪 + 菜单标题在回执确认后才更新（UX 复核 P1-8）
                String token = config.getPairingToken();
                String nonce = java.util.UUID.randomUUID().toString();
                sendReactionsRuleBroadcast(context, dialogId, enabled, whitelist, emoji,
                        minCount, emoji2, maxCount, maxDepth, token, nonce);

                pendingTitleDialogId = dialogId;
                pendingTitleNonce = nonce;
                pendingTitleText = enabled
                        ? "⚡ 表情过滤：" + describeRule(whitelist, emoji, minCount, emoji2, maxCount)
                        : "⚡ 表情过滤（未启用）";
                pendingTitleUpdater = titleUpdater;

                module.log(Log.INFO, TAG, "Reactions rule save sent: dialog=" + dialogId
                        + " enabled=" + enabled + " whitelist=" + whitelist
                        + " emoji=" + emoji + "≥" + minCount
                        + " emoji2=" + emoji2 + "≤" + maxCount
                        + " depth=" + (maxDepth > 0 ? ReactionsRule.formatDepth(maxDepth) : "default"));
                dialog.dismiss();
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "Save reactions rule failed: " + t.getMessage());
            }
        });
    }

    private static String describeRule(boolean whitelist, String emoji, int minCount,
                                       String emoji2, int maxCount) {
        StringBuilder sb = new StringBuilder(emoji).append("≥").append(minCount);
        if (whitelist && emoji2 != null && !emoji2.isEmpty()) {
            sb.append("·").append(emoji2).append("≤").append(maxCount);
        }
        sb.append(whitelist ? "·只显" : "·隐藏");
        return sb.toString();
    }

    /**
     * 保存请求三级通道（hook 端 remote prefs 只读，必须由 App 进程写入）：
     * 1. 显式广播（+FLAG_INCLUDE_STOPPED_PACKAGES）— App 在运行时瞬时完成
     * 2. 2.5s 无回执 → 透明拉起 App 的 WriteConfigActivity 兜底写入
     *    （MIUI 自启动管理拦截广播拉起进程，但前台应用 startActivity 不受限）
     * 3. 再 6s 无回执 → 提示用户手动打开 App
     * App 写入成功后回发 ACTION_REACTIONS_RULE_SAVED 回执（含 nonce 防伪）。
     */
    private static volatile android.content.BroadcastReceiver pendingConfirmReceiver;
    private static volatile Runnable pendingConfirmTimeout;
    /** 本次保存的回执 nonce / 目标频道 / 待确认菜单标题（回执确认后才更新） */
    private static volatile String pendingSaveNonce;
    private static volatile long pendingTitleDialogId;
    private static volatile String pendingTitleNonce;
    private static volatile String pendingTitleText;
    private static volatile java.util.function.Consumer<String> pendingTitleUpdater;

    private static void sendReactionsRuleBroadcast(Context context, long dialogId,
                                                   boolean enabled, boolean whitelist,
                                                   String emoji, int minCount,
                                                   String emoji2, int maxCount, int maxDepth,
                                                   String token, String nonce) {
        Intent intent = new Intent(ACTION_REACTIONS_RULE);
        intent.setComponent(new ComponentName(TG_CLEAN_PACKAGE, RECEIVER_CLASS));
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("dialog_id", dialogId);
        intent.putExtra("enabled", enabled);
        intent.putExtra("whitelist", whitelist);
        intent.putExtra("emoji", emoji);
        intent.putExtra("min_count", minCount);
        intent.putExtra("emoji2", emoji2 == null ? "" : emoji2);
        intent.putExtra("max_count", maxCount);
        intent.putExtra("max_depth", maxDepth); // 0 = 跟随全局默认
        intent.putExtra("token", token == null ? "" : token);
        intent.putExtra("nonce", nonce);
        pendingSaveNonce = nonce;

        Context appContext = context.getApplicationContext();
        registerSaveConfirmation(appContext, 2500, () -> {
            // 广播通道未达（App 进程被 ROM 拦截拉起）→ 透明 Activity 兜底
            try {
                Intent fallback = new Intent();
                fallback.setComponent(new ComponentName(TG_CLEAN_PACKAGE,
                        "com.tgclean.ui.WriteConfigActivity"));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fallback.putExtras(intent);
                appContext.startActivity(fallback);
                registerSaveConfirmation(appContext, 6000, () -> {
                    clearPendingTitle();
                    Toast.makeText(appContext,
                            "TGClean：保存未确认，请打开一次 TGClean 应用后重试",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                clearPendingTitle();
                Toast.makeText(appContext,
                        "TGClean：保存失败，请打开一次 TGClean 应用后重试",
                        Toast.LENGTH_LONG).show();
            }
        });
        context.sendBroadcast(intent);
    }

    private static void clearPendingTitle() {
        pendingTitleDialogId = 0;
        pendingTitleNonce = null;
        pendingTitleText = null;
        pendingTitleUpdater = null;
    }

    /**
     * 注册保存回执（单飞：新保存覆盖旧回执等待）。
     * 收到回执（nonce 匹配）→ 真正确认写入后才更新菜单标题并提示；
     * 超时 → 执行 onTimeout（兜底链的下一级），标题保持旧值（如实反映未保存）。
     */
    private static void registerSaveConfirmation(Context appContext, long timeoutMs,
                                                 Runnable onTimeout) {
        // 清理上一轮
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if (pendingConfirmTimeout != null) {
            main.removeCallbacks(pendingConfirmTimeout);
        }
        if (pendingConfirmReceiver != null) {
            try { appContext.unregisterReceiver(pendingConfirmReceiver); } catch (Throwable ignored) {}
        }

        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                if (pendingConfirmReceiver != this) return; // 旧轮回执迟到，忽略（审计 B-1：匿名类内不可自引用局部变量 receiver）
                String nonce = i == null ? null : i.getStringExtra("nonce");
                if (pendingSaveNonce == null || !pendingSaveNonce.equals(nonce)) return; // 回执防伪
                if (pendingConfirmTimeout != null) main.removeCallbacks(pendingConfirmTimeout);
                try { appContext.unregisterReceiver(this); } catch (Throwable ignored) {}
                pendingConfirmReceiver = null;
                Toast.makeText(appContext, "TGClean：规则已保存，重新进入频道后生效",
                        Toast.LENGTH_LONG).show();
                // 写入已确认落地，此刻才更新菜单标题（UX 复核 P1-8）
                if (pendingTitleUpdater != null && pendingTitleNonce != null
                        && pendingTitleNonce.equals(nonce)
                        && i != null && i.getLongExtra("dialog_id", 0) == pendingTitleDialogId) {
                    java.util.function.Consumer<String> updater = pendingTitleUpdater;
                    String text = pendingTitleText;
                    main.post(() -> {
                        if (updater != null && text != null) updater.accept(text);
                    });
                }
                clearPendingTitle();
            }
        };
        Runnable timeout = () -> {
            try { appContext.unregisterReceiver(receiver); } catch (Throwable ignored) {}
            pendingConfirmReceiver = null;
            onTimeout.run();
        };
        pendingConfirmReceiver = receiver;
        pendingConfirmTimeout = timeout;

        android.content.IntentFilter filter =
                new android.content.IntentFilter(ACTION_REACTIONS_RULE_SAVED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
        main.postDelayed(timeout, timeoutMs);
    }

    private static TextView sectionLabel(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setPadding(0, dp(context, 10), 0, dp(context, 4));
        return tv;
    }

    /** 表情快速选择行：点击即写入目标输入框；可选附加"清除"按钮 */
    private static LinearLayout emojiQuickRow(Context context,
                                              java.util.function.Consumer<String> onPick,
                                              String extraButton) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String emoji : QUICK_EMOJIS) {
            android.widget.Button btn = new android.widget.Button(context);
            btn.setText(emoji);
            btn.setAllCaps(false);
            btn.setMinWidth(dp(context, 44));
            btn.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(context, 4));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> onPick.accept(emoji));
            row.addView(btn);
        }
        if (extraButton != null) {
            android.widget.Button clear = new android.widget.Button(context);
            clear.setText(extraButton);
            clear.setAllCaps(false);
            clear.setOnClickListener(v -> onPick.accept(""));
            row.addView(clear);
        }
        return row;
    }

    /** 横向滚动容器，防止表情按钮行在窄屏溢出 */
    private static android.widget.HorizontalScrollView wrapHScroll(Context context, View child) {
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(context);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(child);
        return hsv;
    }

    private static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int dp(Context context, int value) {
        return (int) (context.getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private static Object getHeaderItem(Object chatActivity) {
        try {
            if (cachedHeaderItemField != null) {
                try { return cachedHeaderItemField.get(chatActivity); }
                catch (IllegalAccessException ignored) { cachedHeaderItemField = null; }
            }
            Field field = findFieldInHierarchy(chatActivity.getClass(), "headerItem");
            if (field != null) {
                field.setAccessible(true);
                cachedHeaderItemField = field;
                return field.get(chatActivity);
            }
        } catch (Throwable t) { /* ignore */ }
        return null;
    }

    private static volatile Method cachedGetName; // DialogObject.getName(int,long)

    private static String resolveChannelName(long dialogId, int accountIdx, ClassLoader cl) {
        try {
            if (cachedGetName == null) {
                Class<?> dialogObjClass = cl.loadClass("org.telegram.messenger.DialogObject");
                cachedGetName = dialogObjClass.getMethod("getName", int.class, long.class);
            }
            return (String) cachedGetName.invoke(null, accountIdx, dialogId);
        } catch (Throwable t) {
            cachedGetName = null;
            return String.valueOf(dialogId);
        }
    }

    private static void showAndCopyDialogId(Context context, long dialogId, String channelName) {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("TGClean Chat ID", String.valueOf(dialogId));
            clipboard.setPrimaryClip(clip);
        } catch (Throwable ignored) {}

        new android.app.AlertDialog.Builder(context)
                .setTitle("TGClean")
                .setMessage("聊天ID已复制到剪贴板：\n" + dialogId
                        + "\n\n频道：" + channelName
                        + "\n\n在 TGClean App 的规则集详情中按名称勾选此频道，即可对该频道应用关键词过滤")
                .setPositiveButton("确定", null)
                .show();
    }

    private static long getDialogId(Object chatActivity, ClassLoader cl) {
        try {
            if (cachedDialogIdField != null) {
                try { return cachedDialogIdField.getLong(chatActivity); }
                catch (IllegalAccessException ignored) { cachedDialogIdField = null; }
            }
            Field dialogIdField = findFieldInHierarchy(chatActivity.getClass(), "dialog_id");
            if (dialogIdField != null) {
                dialogIdField.setAccessible(true);
                cachedDialogIdField = dialogIdField;
                long id = dialogIdField.getLong(chatActivity);
                if (id != 0) return id;
            }
            Method getCurrentChat = findMethod(chatActivity.getClass(), "getCurrentChat");
            if (getCurrentChat != null) {
                Object chat = getCurrentChat.invoke(chatActivity);
                if (chat != null) {
                    // TLRPC.Chat.id 已迁移为 long（Layer 228）
                    try {
                        Field idField = findFieldInHierarchy(chat.getClass(), "id");
                        if (idField != null) {
                            idField.setAccessible(true);
                            long chatId = idField.getLong(chat);
                            if (chatId != 0) return -chatId;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) { /* ignore */ }
        return 0;
    }

    private static int getCurrentAccount(Object chatActivity, ClassLoader cl) {
        try {
            if (cachedCurrentAccountField != null) {
                try { return cachedCurrentAccountField.getInt(chatActivity); }
                catch (IllegalAccessException ignored) { cachedCurrentAccountField = null; }
            }
            Field field = findFieldInHierarchy(chatActivity.getClass(), "currentAccount");
            if (field != null) {
                field.setAccessible(true);
                cachedCurrentAccountField = field;
                return field.getInt(chatActivity);
            }
        } catch (Throwable t) { /* ignore */ }
        return 0;
    }

    private static volatile Method cachedGetContext; // BaseFragment.getContext()

    private static Context getActivityContext(Object activity, ClassLoader cl) {
        try {
            if (activity instanceof Context) return (Context) activity;
            if (cachedGetContext == null) {
                cachedGetContext = findMethod(activity.getClass(), "getContext");
            }
            return (Context) cachedGetContext.invoke(activity);
        } catch (Throwable t) {
            cachedGetContext = null;
        }
        return null;
    }

    // ═════════════════════════════════════════════
    // Reflection helpers
    // ═════════════════════════════════════════════

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        while (clazz != null && clazz != Object.class) {
            try { return clazz.getDeclaredMethod(name, paramTypes); }
            catch (NoSuchMethodException e) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try { return clazz.getDeclaredMethod(name, paramTypes); }
        catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class)
                return findMethod(superClass, name, paramTypes);
        }
        return null;
    }
}
