package com.tgclean.hooks;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * ChatActivity 菜单注入 — "🧹 过滤设置"
 *
 * exteraGram 不使用标准 Android onCreateOptionsMenu，
 * 而是用 ActionBarMenuItem.lazilyAddSubItem() 构建菜单。
 *
 * hook 策略：
 * 1. hook ChatActivity.onResume() —— 此时 headerItem 已初始化
 * 2. 反射获取 headerItem 字段（ActionBarMenuItem）
 * 3. 用 headerItem.addSubItem() 返回 ActionBarMenuSubItem（View）
 * 4. 对返回的 subItem 设置 setTag + setOnClickListener 覆盖默认行为
 */
public class ChatHelperHook {
    private static final String TAG = "TGClean-ChatHelper";

    // 自定义菜单 ID（避免与 Telegram 内部 ID 冲突）
    private static final int MENU_ID_FILTER_SETTINGS = 999001;
    private static final int MENU_ID_COPY_CHAT_ID = 999002;

    // 标记 tag，防止重复注入
    private static final String TAG_INJECTED = "tgclean_injected";

    private static volatile Field cachedHeaderItemField;
    private static volatile Field cachedDialogIdField;
    private static volatile Field cachedCurrentAccountField;

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

            // ChatActivity 自己声明了 onResume
            Method onResume = chatActivityClass.getDeclaredMethod("onResume");
            onResume.setAccessible(true);

            module.hook(onResume).intercept(chain -> {
                chain.proceed();
                try {
                    Object chatActivity = chain.getThisObject();
                    injectIfNeeded(chatActivity, cl, module);
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "Error injecting menu in onResume", t);
                }
                return null;
            });

            module.log(Log.INFO, TAG, "Hooked ChatActivity.onResume for menu injection");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook onResume", t);
        }
    }

    /**
     * 检查是否已注入，未注入则注入菜单项
     */
    private static void injectIfNeeded(Object chatActivity, ClassLoader cl, XposedModule module) {
        try {
            Object headerItem = getHeaderItem(chatActivity);
            if (headerItem == null) return;

            // 用 tag 防止重复注入
            if (TAG_INJECTED.equals(View.class.cast(headerItem).getTag())) {
                return;
            }
            View.class.cast(headerItem).setTag(TAG_INJECTED);

            Context context = getActivityContext(chatActivity, cl);
            if (context == null) return;

            long dialogId = getDialogId(chatActivity, cl);
            if (dialogId == 0) return;

            int accountIdx = getCurrentAccount(chatActivity, cl);
            String channelName = resolveChannelName(dialogId, accountIdx, cl);

            // 调用 headerItem.addSubItem(id, icon, text) → 返回 ActionBarMenuSubItem
            Class<?> headerItemClass = headerItem.getClass();
            Method addSubItem = findMethodInHierarchy(headerItemClass, "addSubItem",
                    int.class, int.class, CharSequence.class);
            if (addSubItem == null) {
                module.log(Log.WARN, TAG, "addSubItem(int,int,CharSequence) not found");
                View.class.cast(headerItem).setTag(null); // 重置，下次重试
                return;
            }
            addSubItem.setAccessible(true);

            // 1. 过滤设置
            Object filterItem = addSubItem.invoke(headerItem, MENU_ID_FILTER_SETTINGS, 0,
                    "🧹 过滤设置 (" + channelName + ")");
            View filterView = (View) filterItem;
            filterView.setOnClickListener(v -> {
                // 延迟调用确保 UI 线程
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        com.tgclean.ui.TGCleanSheet.showChannelSheet(
                                context, dialogId, accountIdx, channelName);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to open TGCleanSheet", t);
                    }
                });
            });

            // 2. 复制聊天 ID
            Object copyItem = addSubItem.invoke(headerItem, MENU_ID_COPY_CHAT_ID, 0,
                    "📋 复制聊天ID (" + dialogId + ")");
            View copyView = (View) copyItem;
            copyView.setOnClickListener(v -> showAndCopyDialogId(context, dialogId));

            module.log(Log.INFO, TAG, "Injected menu items (dialogId=" + dialogId
                    + ", channel=" + channelName + ")");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "injectIfNeeded error: " + t.getMessage());
        }
    }

    /**
     * 通过反射获取 ChatActivity.headerItem 字段
     */
    private static Object getHeaderItem(Object chatActivity) {
        try {
            if (cachedHeaderItemField != null) {
                try {
                    return cachedHeaderItemField.get(chatActivity);
                } catch (IllegalAccessException ignored) {
                    cachedHeaderItemField = null;
                }
            }

            Field field = findFieldInHierarchy(chatActivity.getClass(), "headerItem");
            if (field != null) {
                field.setAccessible(true);
                cachedHeaderItemField = field;
                return field.get(chatActivity);
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    /**
     * 通过反射解析频道名称
     */
    private static String resolveChannelName(long dialogId, int accountIdx, ClassLoader cl) {
        try {
            Class<?> dialogObjClass = cl.loadClass("org.telegram.messenger.DialogObject");
            Method getName = dialogObjClass.getMethod("getName", int.class, long.class);
            return (String) getName.invoke(null, accountIdx, dialogId);
        } catch (Throwable t) {
            return String.valueOf(dialogId);
        }
    }

    private static void showAndCopyDialogId(Context context, long dialogId) {
        ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("TGClean Chat ID",
                String.valueOf(dialogId));
        clipboard.setPrimaryClip(clip);

        new android.app.AlertDialog.Builder(context)
                .setTitle("TGClean")
                .setMessage("聊天ID已复制到剪贴板：\n" + dialogId)
                .setPositiveButton("确定", null)
                .show();
    }

    private static long getDialogId(Object chatActivity, ClassLoader cl) {
        try {
            if (cachedDialogIdField != null) {
                try {
                    return cachedDialogIdField.getLong(chatActivity);
                } catch (IllegalAccessException ignored) {
                    cachedDialogIdField = null;
                }
            }

            Field dialogIdField = findFieldInHierarchy(
                    chatActivity.getClass(), "dialog_id");
            if (dialogIdField != null) {
                dialogIdField.setAccessible(true);
                cachedDialogIdField = dialogIdField;
                long id = dialogIdField.getLong(chatActivity);
                if (id != 0) return id;
            }

            // Fallback: getCurrentChat() → chat.id → -chatId
            Method getCurrentChat = findMethod(chatActivity.getClass(), "getCurrentChat");
            if (getCurrentChat != null) {
                Object chat = getCurrentChat.invoke(chatActivity);
                if (chat != null) {
                    try {
                        Field idField = chat.getClass().getDeclaredField("id");
                        idField.setAccessible(true);
                        int chatId = idField.getInt(chat);
                        if (chatId != 0) {
                            return -chatId;
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return 0;
    }

    private static int getCurrentAccount(Object chatActivity, ClassLoader cl) {
        try {
            if (cachedCurrentAccountField != null) {
                try {
                    return cachedCurrentAccountField.getInt(chatActivity);
                } catch (IllegalAccessException ignored) {
                    cachedCurrentAccountField = null;
                }
            }

            Field field = findFieldInHierarchy(chatActivity.getClass(), "currentAccount");
            if (field != null) {
                field.setAccessible(true);
                cachedCurrentAccountField = field;
                return field.getInt(chatActivity);
            }
        } catch (Throwable t) {
            // ignore
        }
        return 0;
    }

    private static Context getActivityContext(Object activity, ClassLoader cl) {
        try {
            if (activity instanceof Context) return (Context) activity;
            Method getContext = findMethod(activity.getClass(), "getContext");
            if (getContext != null) return (Context) getContext.invoke(activity);
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ═════════════════════════════════════════════
    // Reflection helpers
    // ═════════════════════════════════════════════

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                return findMethod(superClass, name, paramTypes);
            }
        }
        return null;
    }
}
