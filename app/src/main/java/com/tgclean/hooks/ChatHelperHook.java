package com.tgclean.hooks;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.view.Menu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * ChatActivity 增强：复制聊天ID功能
 *
 * exteraGram 的 onCreateOptionsMenu 签名可能与标准Telegram不同，
 * 因此使用 hookAllMethods 策略扫描所有 onCreateOptionsMenu 重载。
 */
public class ChatHelperHook {
    private static final String TAG = "TGClean-ChatHelper";

    // 缓存反射字段
    private static volatile Field cachedDialogIdField;

    public static void hook(ClassLoader cl, XposedModule module) {
        hookOnCreateOptionsMenu(cl, module);
    }

    private static void hookOnCreateOptionsMenu(ClassLoader cl, XposedModule module) {
        try {
            Class<?> chatActivityClass = cl.loadClass("org.telegram.ui.ChatActivity");

            // 扫描所有 onCreateOptionsMenu 重载（适配exteraGram签名差异）
            Method[] methods = chatActivityClass.getDeclaredMethods();
            int hookedCount = 0;
            for (Method m : methods) {
                if (!"onCreateOptionsMenu".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                // 找参数中含 Menu 类的重载
                boolean hasMenu = false;
                for (Class<?> p : params) {
                    if (Menu.class.isAssignableFrom(p)) {
                        hasMenu = true;
                        break;
                    }
                }
                if (!hasMenu) continue;

                // 找Menu参数的位置
                final int menuParamIndex = findMenuParamIndex(params);

                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object chatActivity = chain.getThisObject();
                            java.util.List<Object> args = chain.getArgs();
                            Menu menu = (Menu) args.get(menuParamIndex);

                            long dialogId = getDialogId(chatActivity, cl);
                            if (dialogId == 0) return result;

                            Context context = getActivityContext(chatActivity);
                            if (context == null) return result;

                            menu.add(Menu.NONE, Menu.FIRST, Menu.CATEGORY_SECONDARY,
                                    "📋 复制聊天ID (" + dialogId + ")")
                                    .setOnMenuItemClickListener(item -> {
                                        showAndCopyDialogId(context, dialogId);
                                        return true;
                                    });
                        } catch (Throwable t) {
                            module.log(Log.ERROR, TAG, "Error in onCreateOptionsMenu hook", t);
                        }
                        return result;
                    });
                    hookedCount++;
                    module.log(Log.INFO, TAG, "Hooked onCreateOptionsMenu (param count: "
                            + params.length + ", menu at index " + menuParamIndex + ")");
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "Failed to hook onCreateOptionsMenu variant: "
                            + t.getMessage());
                }
            }

            if (hookedCount == 0) {
                module.log(Log.WARN, TAG, "onCreateOptionsMenu not found in ChatActivity");
            }

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook onCreateOptionsMenu", t);
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

        new AlertDialog.Builder(context)
                .setTitle("TGClean")
                .setMessage("聊天ID已复制到剪贴板：\n" + dialogId + "\n\n"
                        + "可在 TGClean 设置 → 分频道规则中粘贴使用。")
                .setPositiveButton("确定", null)
                .show();
    }

    private static long getDialogId(Object chatActivity, ClassLoader cl) {
        try {
            // 方法1: 直接读 dialogId 字段（使用缓存）
            if (cachedDialogIdField != null) {
                try {
                    return cachedDialogIdField.getLong(chatActivity);
                } catch (IllegalAccessException ignored) {
                    cachedDialogIdField = null;
                }
            }

            Field dialogIdField = findFieldInHierarchy(
                    chatActivity.getClass(), "dialogId");
            if (dialogIdField != null) {
                dialogIdField.setAccessible(true);
                cachedDialogIdField = dialogIdField;
                long id = dialogIdField.getLong(chatActivity);
                if (id != 0) return id;
            }

            // 方法2: getCurrentChat() → chat.id → -chatId
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
