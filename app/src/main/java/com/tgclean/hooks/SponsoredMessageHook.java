package com.tgclean.hooks;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 移除Telegram原生赞助消息（Sponsored Messages）
 *
 * 三个Hook点（参考Killergram + NoAdsTelegram，已验证有效）：
 * 1. ChatActivity.addSponsoredMessages → null
 * 2. MessagesController.getSponsoredMessages → 空列表
 * 3. MessagesController.getSponsoredMessagesCount → 0
 */
public class SponsoredMessageHook {
    private static final String TAG = "TGClean-Sponsored";
    private static int blockedCount = 0;

    public static void hook(ClassLoader cl, XposedModule module) {
        hookAddSponsoredMessages(cl, module);
        hookGetSponsoredMessages(cl, module);
        hookGetSponsoredMessagesCount(cl, module);
        hookTLDeserialize(cl, module);
    }

    private static void hookAddSponsoredMessages(ClassLoader cl, XposedModule module) {
        try {
            Class<?> chatActivityClass = cl.loadClass("org.telegram.ui.ChatActivity");
            Method addMethod = findMethod(chatActivityClass, "addSponsoredMessages");
            if (addMethod == null) {
                module.log(Log.WARN, TAG, "addSponsoredMessages not found, trying hookAllMethods");
                return;
            }
            module.hook(addMethod).intercept(chain -> {
                blockedCount++;
                if (blockedCount <= 3 || blockedCount % 10 == 0) {
                    module.log(Log.DEBUG, TAG, "Blocked addSponsoredMessages (total: " + blockedCount + ")");
                }
                return null;
            });
            module.log(Log.INFO, TAG, "Hooked addSponsoredMessages");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook addSponsoredMessages", t);
        }
    }

    private static void hookGetSponsoredMessages(ClassLoader cl, XposedModule module) {
        try {
            Class<?> mcClass = cl.loadClass("org.telegram.messenger.MessagesController");
            Method getMethod = findMethod(mcClass, "getSponsoredMessages");
            if (getMethod == null) return;
            module.hook(getMethod).intercept(chain -> {
                module.log(Log.DEBUG, TAG, "Blocked getSponsoredMessages");
                return new ArrayList<>();
            });
            module.log(Log.INFO, TAG, "Hooked getSponsoredMessages");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook getSponsoredMessages", t);
        }
    }

    private static void hookGetSponsoredMessagesCount(ClassLoader cl, XposedModule module) {
        try {
            Class<?> mcClass = cl.loadClass("org.telegram.messenger.MessagesController");
            Method getCountMethod = findMethod(mcClass, "getSponsoredMessagesCount");
            if (getCountMethod == null) return;
            module.hook(getCountMethod).intercept(chain -> 0);
            module.log(Log.INFO, TAG, "Hooked getSponsoredMessagesCount");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook getSponsoredMessagesCount", t);
        }
    }

    private static void hookTLDeserialize(ClassLoader cl, XposedModule module) {
        try {
            Class<?> sponsoredClass = cl.loadClass(
                    "org.telegram.tgnet.TLRPC$messages_SponsoredMessages");
            Method deserializeMethod = findMethod(sponsoredClass, "TLdeserialize");
            if (deserializeMethod == null) return;
            module.hook(deserializeMethod).intercept(chain -> null);
            module.log(Log.INFO, TAG, "Hooked TLdeserialize for SponsoredMessages");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "TLdeserialize hook skipped (class may not exist)", t);
        }
    }

    private static Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        // 搜索父类
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            return findMethod(superClass, name);
        }
        return null;
    }
}
