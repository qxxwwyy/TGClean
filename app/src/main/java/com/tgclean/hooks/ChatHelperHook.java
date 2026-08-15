package com.tgclean.hooks;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * ChatActivity Hook — 菜单注入 + 频道自动发现
 *
 * 1. 注入「📋 复制聊天ID」菜单项
 * 2. 首次 onResume 时一次性扫描 TG 全部频道列表，批量广播到 TGClean App
 * 3. 后续 onResume 只增量更新当前频道
 */
public class ChatHelperHook {
    private static final String TAG = "TGClean-ChatHelper";

    private static final int MENU_ID_COPY_CHAT_ID = 999002;
    private static final String TAG_INJECTED = "tgclean_injected";

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

    // 首次全量扫描标记（per account）
    private static volatile boolean scannedAllChannels = false;

    public static void hook(ClassLoader cl, XposedModule module) {
        try {
            hookOnResume(cl, module);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to setup ChatHelperHook", t);
        }
    }

    private static void hookOnResume(ClassLoader cl, XposedModule module) {
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
                    if (!scannedAllChannels) {
                        if (scanAllChannels(context, accountIdx, tgCl, module)) {
                            scannedAllChannels = true;
                        }
                    }

                    // 增量上报当前频道
                    String channelName = resolveChannelName(dialogId, accountIdx, tgCl);
                    reportChannelViaBroadcast(context, dialogId, channelName);

                    // 注入菜单
                    injectIfNeeded(chatActivity, tgCl, module);
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
    private static void injectIfNeeded(Object chatActivity, ClassLoader cl, XposedModule module) {
        try {
            Object headerItem = getHeaderItem(chatActivity);
            if (headerItem == null) return;

            if (TAG_INJECTED.equals(View.class.cast(headerItem).getTag())) return;
            View.class.cast(headerItem).setTag(TAG_INJECTED);

            Context context = getActivityContext(chatActivity, cl);
            if (context == null) return;

            long dialogId = getDialogId(chatActivity, cl);
            if (dialogId == 0) return;

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

            module.log(Log.INFO, TAG, "Injected copy-chat-ID menu (dialogId=" + dialogId
                    + ", channel=" + channelName + ")");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "injectIfNeeded error: " + t.getMessage());
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

    private static String resolveChannelName(long dialogId, int accountIdx, ClassLoader cl) {
        try {
            Class<?> dialogObjClass = cl.loadClass("org.telegram.messenger.DialogObject");
            Method getName = dialogObjClass.getMethod("getName", int.class, long.class);
            return (String) getName.invoke(null, accountIdx, dialogId);
        } catch (Throwable t) {
            return String.valueOf(dialogId);
        }
    }

    private static void showAndCopyDialogId(Context context, long dialogId, String channelName) {
        ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("TGClean Chat ID", String.valueOf(dialogId));
        clipboard.setPrimaryClip(clip);

        new android.app.AlertDialog.Builder(context)
                .setTitle("TGClean")
                .setMessage("聊天ID已复制到剪贴板：\n" + dialogId
                        + "\n\n频道：" + channelName
                        + "\n\n打开 TGClean App 粘贴此ID来配置过滤规则")
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

    private static Context getActivityContext(Object activity, ClassLoader cl) {
        try {
            if (activity instanceof Context) return (Context) activity;
            Method getContext = findMethod(activity.getClass(), "getContext");
            if (getContext != null) return (Context) getContext.invoke(activity);
        } catch (Throwable ignored) {}
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
