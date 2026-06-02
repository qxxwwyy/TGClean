package com.tgclean.hooks;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.view.Menu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * ChatActivity 增强：菜单注入 — "🧹 过滤设置" + "📋 复制聊天ID"
 *
 * exteraGram 的 onCreateOptionsMenu 签名可能与标准Telegram不同，
 * 因此使用 hookAllMethods 策略扫描所有 onCreateOptionsMenu 重载。
 *
 * v2: 新增 TGCleanSheet 入口，自动获取 dialogId + accountIdx + channelName
 */
public class ChatHelperHook {
    private static final String TAG = "TGClean-ChatHelper";

    // 缓存反射字段
    private static volatile Field cachedDialogIdField;
    private static volatile Field cachedCurrentAccountField;

    public static void hook(ClassLoader cl, XposedModule module) {
        hookOnCreateOptionsMenu(cl, module);
    }

    private static void hookOnCreateOptionsMenu(ClassLoader cl, XposedModule module) {
        try {
            Class<?> chatActivityClass = cl.loadClass("org.telegram.ui.ChatActivity");

            // 遍历继承链查找 onCreateOptionsMenu（exteraGram 可能在父类 BaseFragment 中）
            Method target = findMethodInHierarchy(chatActivityClass, "onCreateOptionsMenu");
            if (target == null) {
                module.log(Log.WARN, TAG, "onCreateOptionsMenu not found in ChatActivity hierarchy");
                return;
            }

            Class<?>[] params = target.getParameterTypes();
            final int menuParamIndex = findMenuParamIndex(params);

            try {
                target.setAccessible(true);
                module.hook(target).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object chatActivity = chain.getThisObject();
                            java.util.List<Object> args = chain.getArgs();
                            Menu menu = (Menu) args.get(menuParamIndex);

                            long dialogId = getDialogId(chatActivity, cl);
                            if (dialogId == 0) return result;

                            Context context = getActivityContext(chatActivity);
                            if (context == null) return result;

                            int accountIdx = getCurrentAccount(chatActivity, cl);

                            // 1. TGClean 过滤设置（主入口）
                            String channelName = resolveChannelName(dialogId, accountIdx, cl);
                            menu.add(Menu.NONE, Menu.FIRST + 1, Menu.CATEGORY_SECONDARY,
                                    "🧹 过滤设置 (" + channelName + ")")
                                    .setOnMenuItemClickListener(item -> {
                                        try {
                                            // 延迟调用确保 UI 线程
                                            android.os.Handler handler = new android.os.Handler(
                                                    android.os.Looper.getMainLooper());
                                            handler.post(() -> {
                                                try {
                                                    com.tgclean.ui.TGCleanSheet.showChannelSheet(
                                                            context, dialogId, accountIdx, channelName);
                                                } catch (Throwable t) {
                                                    Log.e(TAG, "Failed to open TGCleanSheet", t);
                                                }
                                            });
                                        } catch (Throwable t) {
                                            Log.e(TAG, "Failed to schedule TGCleanSheet", t);
                                        }
                                        return true;
                                    });

                            // 2. 复制聊天ID（保留为高级功能）
                            menu.add(Menu.NONE, Menu.FIRST + 2, Menu.CATEGORY_SECONDARY,
                                    "📋 复制聊天ID (" + dialogId + ")")
                                    .setOnMenuItemClickListener(item -> {
                                        showAndCopyDialogId(context, dialogId);
                                        return true;
                                    });

                        } catch (Throwable t) {
                            module.log(Log.ERROR, TAG, "Error in onCreateOptionsMenu hook", t);
                        }
                        return result;
                    }));
                    module.log(Log.INFO, TAG, "Hooked onCreateOptionsMenu (param count: "
                            + params.length + ", menu at index " + menuParamIndex + ")");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to hook onCreateOptionsMenu: "
                        + t.getMessage());
            }

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook onCreateOptionsMenu", t);
        }
    }

    /**
     * 通过反射解析频道名称
     */
    private static String resolveChannelName(long dialogId, int accountIdx, ClassLoader cl) {
        try {
            Class<?> dialogObjClass = cl.loadClass("org.telegram.messenger.DialogObject");
            // DialogObject.getName(int currentAccount, long dialogId)
            Method getName = dialogObjClass.getMethod("getName", int.class, long.class);
            return (String) getName.invoke(null, accountIdx, dialogId);
        } catch (Throwable t) {
            return String.valueOf(dialogId);
        }
    }

    private static int findMenuParamIndex(Class<?>[] params) {
        for (int i = 0; i < params.length; i++) {
            if (Menu.class.isAssignableFrom(params[i])) return i;
        }
        return 0;
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

    /**
     * 在继承链中按方法名查找，并从所有重载中选出参数包含 Menu 的那个
     */
    private static Method findMethodInHierarchy(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (name.equals(m.getName())) {
                    for (Class<?> p : m.getParameterTypes()) {
                        if (Menu.class.isAssignableFrom(p)) return m;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static Context getActivityContext(Object activity) {
        try {
            if (activity instanceof Context) return (Context) activity;
            Method getContext = findMethod(activity.getClass(), "getContext");
            if (getContext != null) return (Context) getContext.invoke(activity);
        } catch (Throwable ignored) {
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
