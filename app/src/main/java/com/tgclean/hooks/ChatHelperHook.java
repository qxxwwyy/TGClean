package com.tgclean.hooks;

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
 * 1. 注入「⚡ 表情过滤」菜单项
 * 2. 首次 onResume 时一次性扫描 TG 全部频道列表，批量广播到 TGClean App
 * 3. 后续 onResume 只增量更新当前频道
 * 4. 表情过滤：在 TG 内直接为当前频道配置白/黑名单规则（弹窗 + 表情快速选择），
 *    保存后经广播由 TGClean App 写入 remote prefs，实时推送回本进程生效
 */
public class ChatHelperHook {
    private static final String TAG = "TGClean-ChatHelper";

    private static final int MENU_ID_REACTIONS = 999003;
    private static final int MENU_ID_DIALOGS_REACTIONS = 999004;
    private static final String TAG_INJECTED = "tgclean_injected";
    private static final String TAG_DIALOGS_INJECTED = "tgclean_dialogs_injected";

    // 会话列表操作模式反射字段缓存（actionModeViews 定位 ⋮ 溢出菜单宿主，
    // selectedDialogs 读当前选中会话）
    private static volatile Field cachedActionModeViewsField;
    private static volatile Field cachedSelectedDialogsField;

    // App→TG 反向通道：请求清除"已扫描"标记，下一个聊天页 onResume 重扫并
    // 批量上报（重装/清数据后频道列表为空的恢复通道，见 ensureRescanReceiver）
    private static final String ACTION_RESCAN_CHANNELS = "com.tgclean.ACTION_RESCAN_CHANNELS";
    private static volatile boolean rescanReceiverRegistered = false;

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
        try {
            hookDialogsActionMode(cl, module, config);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to setup DialogsActivity hook", t);
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
                    ensureRescanReceiver(context); // App→TG 重扫请求通道,尽早可用

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

            // 表情过滤菜单项（标题反映当前规则状态）；传 chatActivity 供保存后自动重进
            View reactionsView = (View) addSubItem.invoke(headerItem, MENU_ID_REACTIONS, 0,
                    buildReactionsMenuTitle(config, dialogId));
            final Object chatAct = chatActivity;
            reactionsView.setOnClickListener(v -> showReactionsFilterDialog(
                    context, dialogId, channelName, config, module, newTitle ->
                            updateMenuItemText(reactionsView, newTitle), chatAct));

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
    // 会话列表操作模式「⋮」溢出菜单注入 —— 进频道前配置表情过滤
    //（现代 TG 长按即进入多选操作模式，无长按弹窗菜单；溢出菜单是唯一
    //  稳定入口：DialogsActivity.createActionMode → otherItem.addSubItem，
    //  2024→master 结构未变。注入方式与频道内 ⚡ 菜单同款：替换子项点击
    //  监听，不经 TG 原生 id 分发）
    // ═════════════════════════════════════════════

    private static void hookDialogsActionMode(ClassLoader cl, XposedModule module, FilterConfig config) {
        try {
            Class<?> daClass = cl.loadClass("org.telegram.ui.DialogsActivity");
            // 按名挂钩所有 createActionMode 重载（新老版本参数表不同：
            // (String tag) / () 等，精确签名在旧版本会 NoSuchMethodException 静默失败）；
            // 注入幂等（tag 防重），多重载只会生效一次
            int hooked = 0;
            for (Method cand : daClass.getDeclaredMethods()) {
                if (!"createActionMode".equals(cand.getName())) continue;
                cand.setAccessible(true);
                module.hook(cand).intercept(chain -> {
                    Object ret = chain.proceed();
                    try {
                        injectDialogsActionModeItem(chain.getThisObject(), cl, module, config);
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "Dialogs action-mode inject failed: " + t.getMessage());
                    }
                    return ret;
                });
                hooked++;
            }
            if (hooked == 0) {
                module.log(Log.WARN, TAG, "createActionMode not found, list ⚡ entry off");
                return;
            }
            module.log(Log.INFO, TAG, "Hooked DialogsActivity.createActionMode x"
                    + hooked + " (list ⚡ entry)");
        } catch (Throwable t) {
            // 非致命：进频道内 ⚡ 菜单与 App 端长按编辑仍可用
            module.log(Log.WARN, TAG, "DialogsActivity hook failed: " + t.getMessage());
        }
    }

    private static void injectDialogsActionModeItem(Object dialogsActivity, ClassLoader cl,
                                                    XposedModule module, FilterConfig config)
            throws Exception {
        Class<?> abmiClass = cl.loadClass("org.telegram.ui.ActionBar.ActionBarMenuItem");
        Object otherItem = findOverflowHost(dialogsActivity, cl, abmiClass);
        if (otherItem == null) {
            module.log(Log.WARN, TAG, "no ActionBarMenuItem host in action mode, list ⚡ entry off");
            return;
        }
        if (TAG_DIALOGS_INJECTED.equals(View.class.cast(otherItem).getTag())) return;

        Method addSubItem = findMethodInHierarchy(abmiClass, "addSubItem",
                int.class, int.class, CharSequence.class);
        if (addSubItem == null) {
            module.log(Log.WARN, TAG, "addSubItem not found, list ⚡ entry off");
            return;
        }
        addSubItem.setAccessible(true);
        View item = (View) addSubItem.invoke(otherItem, MENU_ID_DIALOGS_REACTIONS, 0,
                "⚡ 表情过滤");
        View.class.cast(otherItem).setTag(TAG_DIALOGS_INJECTED);
        Context context = ((View) otherItem).getContext();
        // 顺手注册重扫接收器（用户长按会话列表时 App 大概率活着，
        // 是 ensureRescanReceiver 最早的可用时机之一）
        ensureRescanReceiver(context);
        item.setOnClickListener(v -> handleDialogsReactionsClick(
                dialogsActivity, context, cl, config, module));
        module.log(Log.INFO, TAG, "Injected ⚡ into dialogs action-mode overflow");
    }

    /**
     * 定位操作模式「⋮」溢出菜单宿主（ActionBarMenuItem）：
     * 优先 actionModeViews（TG 原生字段，唯一 ActionBarMenuItem）；
     * 版本容错回退：扫描其它 List 字段找 ActionBarMenuItem 实例。
     */
    private static Object findOverflowHost(Object dialogsActivity, ClassLoader cl,
                                           Class<?> abmiClass) throws Exception {
        if (cachedActionModeViewsField == null) {
            Field f = findFieldInHierarchy(dialogsActivity.getClass(), "actionModeViews");
            if (f != null) {
                f.setAccessible(true);
                cachedActionModeViewsField = f;
            }
        }
        if (cachedActionModeViewsField != null) {
            Object hit = firstMenuItemIn(cachedActionModeViewsField.get(dialogsActivity), abmiClass);
            if (hit != null) return hit;
        }
        for (Field f : dialogsActivity.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object hit = firstMenuItemIn(f.get(dialogsActivity), abmiClass);
                if (hit != null) return hit;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object firstMenuItemIn(Object listObj, Class<?> abmiClass) {
        if (!(listObj instanceof List)) return null;
        for (Object v : (List<?>) listObj) {
            if (abmiClass.isInstance(v)) return v;
        }
        return null;
    }

    /**
     * 注册 App→TG 重扫请求接收器（进程内一次）：
     * App 端频道列表空(重装/清数据/MIUI 拦截首批广播)时点"重新扫描"，
     * 此处清除 scannedAccounts，用户进任意聊天页即触发批量重扫上报——
     * 此时 App 在前台活着，TG→App 广播不再受 MIUI 自启动拦截。
     */
    private static void ensureRescanReceiver(Context context) {
        if (rescanReceiverRegistered) return;
        try {
            Context appContext = context.getApplicationContext();
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    scannedAccounts.clear();
                    Log.i(TAG, "Rescan requested from App, will re-scan on next chat open");
                }
            };
            android.content.IntentFilter filter =
                    new android.content.IntentFilter(ACTION_RESCAN_CHANNELS);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter);
            }
            rescanReceiverRegistered = true;
            Log.i(TAG, "Rescan request receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "Rescan receiver registration failed: " + t.getMessage());
        }
    }

    private static void handleDialogsReactionsClick(Object dialogsActivity, Context context,
                                                    ClassLoader cl, FilterConfig config,
                                                    XposedModule module) {
        try {
            long dialogId = singleSelectedDialog(dialogsActivity);
            if (dialogId == -1L) {
                Toast.makeText(context, "TGClean：请只选择一个会话再设置", Toast.LENGTH_SHORT).show();
                return;
            }
            if (dialogId == 0) {
                Toast.makeText(context, "TGClean：未识别到所选会话", Toast.LENGTH_SHORT).show();
                return;
            }
            if (dialogId > 0) { // 正数 = 私聊用户；表情过滤只对频道/群组有意义
                Toast.makeText(context, "TGClean：表情过滤仅支持频道/群组", Toast.LENGTH_SHORT).show();
                return;
            }
            int accountIdx = getCurrentAccount(dialogsActivity, cl);
            String name = resolveChannelName(dialogId, accountIdx, cl);
            showReactionsFilterDialog(context, dialogId, name, config, module, null, null);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Dialogs ⚡ click failed: " + t.getMessage());
        }
    }

    /** 操作模式选中集合：恰一个 → 其 id；多个 → -1；异常/空 → 0 */
    private static long singleSelectedDialog(Object dialogsActivity) {
        try {
            if (cachedSelectedDialogsField == null) {
                cachedSelectedDialogsField = findFieldInHierarchy(
                        dialogsActivity.getClass(), "selectedDialogs");
                if (cachedSelectedDialogsField == null) return 0;
                cachedSelectedDialogsField.setAccessible(true);
            }
            Object sel = cachedSelectedDialogsField.get(dialogsActivity);
            if (!(sel instanceof List) || ((List<?>) sel).isEmpty()) return 0;
            if (((List<?>) sel).size() > 1) return -1L;
            Object v = ((List<?>) sel).get(0);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Number) return ((Number) v).longValue();
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 保存生效后自动"重进"频道：构造同频道的 ChatActivity 并 presentFragment；
     * TG 前进导航自带 need_remove_previous_same_chat_activity（默认 true），
     * 会移除旧的同频道实例——等效用户退出重进，新实例的消息加载重新经过
     * 滤边界。反射失败/呈现被拒时回退提示手动重进。
     */
    private static void reopenChannel(Object chatActivity, long dialogId) {
        Context toastCtx = null;
        try {
            ClassLoader cl = chatActivity.getClass().getClassLoader();
            toastCtx = getActivityContext(chatActivity, cl); // 先取上下文，任何后续失败都能兜底提示
            Class<?> caClass = cl.loadClass("org.telegram.ui.ChatActivity");
            Class<?> bfClass = cl.loadClass("org.telegram.ui.ActionBar.BaseFragment");
            android.os.Bundle args = new android.os.Bundle();
            args.putLong("chat_id", -dialogId); // 频道 dialogId 为负，chat_id 取反
            Object fresh = caClass.getConstructor(android.os.Bundle.class).newInstance(args);
            Object result = null;
            Method present = findMethodInHierarchy(chatActivity.getClass(),
                    "presentFragment", bfClass);
            if (present != null) {
                present.setAccessible(true);
                result = present.invoke(chatActivity, fresh);
            } else {
                // 回退：宿主 LaunchActivity.presentFragment(BaseFragment)
                try {
                    Object host = chatActivity.getClass().getMethod("getParentActivity")
                            .invoke(chatActivity);
                    if (host != null) {
                        present = findMethodInHierarchy(host.getClass(),
                                "presentFragment", bfClass);
                        if (present != null) {
                            present.setAccessible(true);
                            result = present.invoke(host, fresh);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (Boolean.TRUE.equals(result)) return;
        } catch (Throwable ignored) {
        }
        if (toastCtx != null) {
            Toast.makeText(toastCtx, "TGClean：自动重进未成功，请手动重新进入频道生效",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ═════════════════════════════════════════════
    // 表情过滤配置弹窗（TG 进程内，仅 framework 控件，避免 ClassLoader 冲突）
    // ═════════════════════════════════════════════

    private static final String[] QUICK_EMOJIS = {"👍", "👎", "❤️", "🔥", "🥰", "😂", "🤩", "💯"};

    private static void showReactionsFilterDialog(Context context, long dialogId,
                                                  String channelName, FilterConfig config,
                                                  XposedModule module,
                                                  java.util.function.Consumer<String> titleUpdater,
                                                  Object relaunchActivity) {
        ReactionsRule current = config.getReactionsChannelRules().get(dialogId);

        // 内容高度钳制：framework AlertDialog 给自定义视图的测量上限是整个可用屏高
        // （不替标题/按钮栏留量），内容一高弹窗就顶满屏幕，底部按钮栏（保存/取消）
        // 被裁出可视区——小屏/大字体/不同分辨率下复现（2026-09 用户实测）。
        // 钳到 2/3 屏高并预留标题+消息行+按钮栏后，内容区自身滚动，按钮栏恒可见。
        ScrollView scroll = new ClampedScrollView(context, maxDialogBodyHeight(context));
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

        // ── 目标表情（≥ 阈值，可多选合计）──
        root.addView(sectionLabel(context, "目标表情（可多选，计数为合计）"));
        EditText editEmoji = new EditText(context);
        editEmoji.setHint("表情（可多个，空格分隔；点下方快速选择）");
        LinearLayout toggleRow = emojiToggleRow(context, editEmoji);
        root.addView(wrapHScroll(context, toggleRow));
        root.addView(editEmoji);
        // 手动编辑后失焦时同步快速选择行的高亮状态
        editEmoji.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) refreshToggleRow(toggleRow, editEmoji);
        });
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
        // 末位固定“自定义…”：非预设深度（如旧版存的 15000/30000）落在该档
        final int customIdx = ReactionsRule.DEPTH_PRESETS.length + 1;
        final String[] depthChoices = new String[ReactionsRule.DEPTH_PRESETS.length + 2];
        depthChoices[0] = "跟随全局默认（当前 " + ReactionsRule.formatDepth(globalDepth) + " 条）";
        for (int i = 0; i < ReactionsRule.DEPTH_PRESETS.length; i++) {
            depthChoices[i + 1] = ReactionsRule.formatDepth(ReactionsRule.DEPTH_PRESETS[i]) + " 条";
        }
        depthChoices[customIdx] = "自定义…";
        // 预填：规则深度匹配预设则选中对应项，非预设正值落自定义档
        int presetIdx = 0;
        int prefillCustom = 0;
        if (current != null && current.maxDepth > 0) {
            for (int i = 0; i < ReactionsRule.DEPTH_PRESETS.length; i++) {
                if (ReactionsRule.DEPTH_PRESETS[i] == current.maxDepth) {
                    presetIdx = i + 1;
                    break;
                }
            }
            if (presetIdx == 0) {
                presetIdx = customIdx;
                prefillCustom = current.maxDepth;
                depthChoices[customIdx] = "自定义（" + ReactionsRule.formatDepth(current.maxDepth) + " 条）";
            }
        }
        final int[] selectedDepth = {presetIdx}; // 初始即预填档（审计 A-3）
        final int[] customDepth = {prefillCustom}; // 自定义档的值（条）
        // 程序化 setSelection 的首个 onItemSelected 回调是布局后异步派发的，
        // 同步置标志挡不住——改用"吞掉首个回调"：首回调只记录档位不弹输入框
        // （否则预填自定义档时弹窗一打开就自动弹输入框，审计 A-1）
        final boolean[] spinnerReady = {false};
        android.widget.Spinner depthSpinner = new android.widget.Spinner(context);
        android.widget.ArrayAdapter<String> depthAdapter = new android.widget.ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, depthChoices);
        depthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        depthSpinner.setAdapter(depthAdapter);
        depthSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (!spinnerReady[0]) { // 首个回调 = 程序预填
                    spinnerReady[0] = true;
                    selectedDepth[0] = pos;
                    return;
                }
                selectedDepth[0] = pos;
                if (pos == customIdx) promptCustomDepth(context, depthChoices, customIdx,
                        customDepth, depthAdapter);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        depthSpinner.setSelection(presetIdx);
        root.addView(depthSpinner);

        TextView hint = new TextView(context);
        hint.setText("提示：建议用快速选择以确保表情编码匹配（生效时机见保存后的提示）");
        hint.setTextSize(12);
        hint.setPadding(0, dp(context, 10), 0, 0);
        root.addView(hint);

        // ── 保存后自动重进（仅频道内入口：有频道实例可重进；列表入口传 null）──
        final CheckBox checkRelaunch;
        if (relaunchActivity != null && dialogId < 0) {
            checkRelaunch = new CheckBox(context);
            checkRelaunch.setText("保存后自动重进频道立即生效（推荐）");
            checkRelaunch.setChecked(true);
            root.addView(checkRelaunch);
        } else {
            checkRelaunch = null;
        }

        // ── 预填当前规则 ──
        if (current != null) {
            checkEnabled.setChecked(current.enabled);
            if (current.whitelistMode) radioWhite.setChecked(true);
            else radioBlack.setChecked(true);
            editEmoji.setText(current.hasEmojiSet()
                    ? String.join(" ", current.emojiSetList())
                    : (current.emoji != null ? current.emoji : ""));
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
                java.util.List<String> targets = parseEmojiTokens(editEmoji.getText().toString());
                String emoji2 = editEmoji2.isEnabled()
                        ? editEmoji2.getText().toString().trim() : "";
                int minCount = parseIntOr(editMin.getText().toString(), -1);
                int maxCount = parseIntOr(editMax.getText().toString(), -1);

                if (enabled) {
                    // 校验失败定位到具体输入框（setError），不再用笼统 Toast（UX 复核 P1-6）
                    if (targets.isEmpty()) {
                        editEmoji.setError("请选择或输入目标表情");
                        editEmoji.requestFocus();
                        return;
                    }
                    if (targets.size() > ReactionsRule.MAX_EMOJI_SET) {
                        editEmoji.setError("最多 " + ReactionsRule.MAX_EMOJI_SET + " 个表情");
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
                    if (whitelist && !emoji2.isEmpty() && targets.contains(emoji2)) {
                        editEmoji2.setError("负面表情不能与目标表情重复");
                        editEmoji2.requestFocus();
                        return;
                    }
                }
                // 单表情走旧字段（emoji），多表情写 emoji_set 求和集合；
                // emoji 恒填首个目标，兼容"新 App 数据被旧 hook 进程读取"的短暂窗口
                String emoji = targets.isEmpty() ? "" : targets.get(0);
                String emojiSet = targets.size() > 1 ? String.join(" ", targets) : "";

                int maxDepth;
                if (selectedDepth[0] == customIdx) {
                    if (customDepth[0] <= 0) {
                        Toast.makeText(context,
                                "请先在“自定义…”中输入检索深度", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    maxDepth = customDepth[0];
                } else if (selectedDepth[0] > 0) {
                    maxDepth = ReactionsRule.DEPTH_PRESETS[selectedDepth[0] - 1];
                } else {
                    maxDepth = 0; // 0 = 跟随全局默认
                }

                // 令牌随写请求带回 App 端校验（防伪造，审计 M-1）；
                // nonce 供回执防伪 + 菜单标题在回执确认后才更新（UX 复核 P1-8）
                String token = config.getPairingToken();
                String nonce = java.util.UUID.randomUUID().toString();
                sendReactionsRuleBroadcast(context, dialogId, enabled, whitelist, emoji,
                        emojiSet, minCount, emoji2, maxCount, maxDepth, token, nonce);

                pendingTitleDialogId = dialogId;
                pendingTitleNonce = nonce;
                String targetLabel = String.join("+", targets);
                pendingTitleText = enabled
                        ? "⚡ 表情过滤：" + describeRule(whitelist, targetLabel, minCount, emoji2, maxCount)
                        : "⚡ 表情过滤（未启用）";
                pendingTitleUpdater = titleUpdater;

                // 回执确认后自动重进（仅频道内入口 & 规则启用 & 用户未取消勾选）
                pendingRelaunchActivity = (checkRelaunch != null && checkRelaunch.isChecked()
                        && enabled && relaunchActivity != null)
                        ? new java.lang.ref.WeakReference<>(relaunchActivity) : null;
                pendingRelaunchDialogId = dialogId;

                module.log(Log.INFO, TAG, "Reactions rule save sent: dialog=" + dialogId
                        + " enabled=" + enabled + " whitelist=" + whitelist
                        + " target=" + targetLabel + "≥" + minCount
                        + " emoji2=" + emoji2 + "≤" + maxCount
                        + " depth=" + (maxDepth > 0 ? ReactionsRule.formatDepth(maxDepth) : "default"));
                dialog.dismiss();
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "Save reactions rule failed: " + t.getMessage());
            }
        });
    }

    /** 菜单标题形态的规则描述：目标标签形如 "❤️+👍"（多表情合计）或 "❤️" */
    private static String describeRule(boolean whitelist, String targetLabel, int minCount,
                                       String emoji2, int maxCount) {
        StringBuilder sb = new StringBuilder(targetLabel).append("≥").append(minCount);
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
    /** 回执确认后自动重进的频道实例（弱引用防泄漏）与目标频道 */
    private static volatile java.lang.ref.WeakReference<Object> pendingRelaunchActivity;
    private static volatile long pendingRelaunchDialogId;

    private static void sendReactionsRuleBroadcast(Context context, long dialogId,
                                                   boolean enabled, boolean whitelist,
                                                   String emoji, String emojiSet, int minCount,
                                                   String emoji2, int maxCount, int maxDepth,
                                                   String token, String nonce) {
        Intent intent = new Intent(ACTION_REACTIONS_RULE);
        intent.setComponent(new ComponentName(TG_CLEAN_PACKAGE, RECEIVER_CLASS));
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("dialog_id", dialogId);
        intent.putExtra("enabled", enabled);
        intent.putExtra("whitelist", whitelist);
        intent.putExtra("emoji", emoji);
        intent.putExtra("emoji_set", emojiSet == null ? "" : emojiSet); // 多表情合计（空 = 单 emoji）
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
        pendingRelaunchActivity = null;
        pendingRelaunchDialogId = 0;
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
                // 回执确认 → 写入已落地。若本次保存要求自动重进（频道内入口 +
                // 规则启用 + 勾选未取消），立即重进频道让过滤对首轮加载生效
                java.lang.ref.WeakReference<Object> rlRef = pendingRelaunchActivity;
                long rlDialogId = pendingRelaunchDialogId;
                pendingRelaunchActivity = null;
                Object relaunchAct = rlRef == null ? null : rlRef.get();
                long receiptDialog = i == null ? 0 : i.getLongExtra("dialog_id", 0);
                if (relaunchAct != null && receiptDialog == rlDialogId) {
                    Toast.makeText(appContext, "TGClean：规则已保存，正在重新进入频道生效…",
                            Toast.LENGTH_SHORT).show();
                    main.post(() -> reopenChannel(relaunchAct, rlDialogId));
                } else {
                    Toast.makeText(appContext, "TGClean：规则已保存，重新进入频道后生效",
                            Toast.LENGTH_SHORT).show();
                }
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

    /** 深度自定义档输入：钳制到 [MIN_DEPTH, MAX_DEPTH] 后回填 Spinner 档位标签 */
    private static void promptCustomDepth(Context context, String[] depthChoices, int customIdx,
                                          int[] customDepth, android.widget.ArrayAdapter<String> adapter) {
        EditText input = new EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (customDepth[0] > 0) input.setText(String.valueOf(customDepth[0]));
        input.setHint(ReactionsRule.MIN_DEPTH + " ~ " + ReactionsRule.formatDepth(ReactionsRule.MAX_DEPTH));
        new android.app.AlertDialog.Builder(context)
                .setTitle("自定义检索深度（条）")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String t = input.getText().toString().trim();
                    int v;
                    try {
                        v = Integer.parseInt(t);
                    } catch (NumberFormatException e) {
                        // 超 int 范围的纯数字按上限钳制，非数字才报错（审计 A-2）
                        v = t.matches("\\d{10,}") ? ReactionsRule.MAX_DEPTH : -1;
                    }
                    if (v <= 0) {
                        Toast.makeText(context, "请输入正整数", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    customDepth[0] = ReactionsRule.clampDepth(v);
                    depthChoices[customIdx] = "自定义（"
                            + ReactionsRule.formatDepth(customDepth[0]) + " 条）";
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
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

    /** 拆分空格/逗号分隔的表情 token（去重保序、过滤超长项），供多选合计输入 */
    private static java.util.List<String> parseEmojiTokens(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) return out;
        for (String t : s.trim().split("[\\s,，]+")) {
            if (t.isEmpty()) continue;
            if (t.codePointCount(0, t.length()) > 16) continue;
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * 目标表情多选行：点击在输入框的空格分隔集合中切换选中（选中高亮、
     * 未选半透明），附"全清"按钮；选中态文本形如 "❤️ 👍"。
     */
    private static LinearLayout emojiToggleRow(Context context, EditText target) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String emoji : QUICK_EMOJIS) {
            android.widget.Button btn = new android.widget.Button(context);
            btn.setText(emoji);
            btn.setAllCaps(false);
            btn.setMinWidth(dp(context, 44));
            btn.setPadding(0, 0, 0, 0);
            btn.setTag(emoji);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(context, 4));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                java.util.List<String> tokens = parseEmojiTokens(target.getText().toString());
                if (tokens.contains(emoji)) {
                    tokens.remove(emoji);
                } else {
                    if (tokens.size() >= ReactionsRule.MAX_EMOJI_SET) {
                        Toast.makeText(context,
                                "最多选 " + ReactionsRule.MAX_EMOJI_SET + " 个表情",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    tokens.add(emoji);
                }
                target.setText(String.join(" ", tokens));
                target.setSelection(target.getText().length());
                refreshToggleRow(row, target);
            });
            row.addView(btn);
        }
        android.widget.Button clear = new android.widget.Button(context);
        clear.setText("✖ 全清");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            target.setText("");
            refreshToggleRow(row, target);
        });
        row.addView(clear);
        refreshToggleRow(row, target);
        return row;
    }

    /** 按输入框当前 token 集合同步快速选择行的选中高亮 */
    private static void refreshToggleRow(LinearLayout row, EditText target) {
        java.util.List<String> tokens = parseEmojiTokens(target.getText().toString());
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getTag() instanceof String) {
                c.setAlpha(tokens.contains((String) c.getTag()) ? 1f : 0.45f);
            }
        }
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

    // 弹窗内容区最多占屏高比例，与「屏高 − 预留(标题+消息行+按钮栏)」取小
    private static final float DIALOG_BODY_MAX_SCREEN_RATIO = 2f / 3f;

    private static int maxDialogBodyHeight(Context context) {
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        // 2/3 屏高与「屏高 − 预留 170dp」取小：纯 2/3 比例在横屏/分屏小窗
        // （屏高 320~420dp）下，1/3 余量（约 110~140dp）盖不住约 130dp 的
        // 标题+按钮栏，按钮栏仍会被裁 10~35dp
        int cap = Math.min((int) (screenH * DIALOG_BODY_MAX_SCREEN_RATIO),
                screenH - dp(context, 170));
        return Math.max(dp(context, 120), cap);
    }

    /**
     * 高度钳制 ScrollView：AOSP 的 AlertController 给自定义视图 AT_MOST(整个可用
     * 屏高) 的规格（不为标题/按钮栏留量，LinearLayout 也不会收缩子项），内容一高
     * 按钮栏即被裁；部分 OEM/旧版主题下是 wrap_content 链（UNSPECIFIED）。两种
     * 规格一律收窄为 AT_MOST(max)；父容器给出更小的限定高度（EXACTLY 或更紧的
     * AT_MOST，如键盘弹起、分屏）时原样让位。内容不足上限时按内容自然高度显示
     * （AT_MOST 语义），小弹窗不受影响；ScrollView 以 UNSPECIFIED 测量子项，
     * 钳制后内部滚动不受影响。
     */
    private static final class ClampedScrollView extends ScrollView {
        private final int maxHeightPx;

        ClampedScrollView(Context context, int maxHeightPx) {
            super(context);
            this.maxHeightPx = maxHeightPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = View.MeasureSpec.getMode(heightMeasureSpec);
            if (mode == View.MeasureSpec.UNSPECIFIED
                    || (mode == View.MeasureSpec.AT_MOST
                    && View.MeasureSpec.getSize(heightMeasureSpec) > maxHeightPx)) {
                heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                        maxHeightPx, View.MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
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
