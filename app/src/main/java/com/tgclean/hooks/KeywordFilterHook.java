package com.tgclean.hooks;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.tgclean.config.FilterConfig;
import com.tgclean.filter.KeywordEngine;

import io.github.libxposed.api.XposedModule;

/**
 * 关键词/表情过滤Hook — 消息数组边界过滤（v18 重构）
 *
 * v17 及之前的方案：构造函数标记 deleted=true + updateRowsSafe 时从 messages 列表移除。
 * 该方案在 TG 12.9.2 上暴露三处失效路径：
 * 1. 历史加载（向上翻页/重进恢复位置）走 ChatActivity L21414 直接调用
 *    updateRowsInternal()，完全绕过 updateRowsSafe → 被标记的消息保留行号
 * 2. deleted=true 会被 TG 渲染层消费：ChatActivity L34646 对 deleted 消息跳过
 *    cell 重绑定 → 媒体照常显示但消息气泡空白（图文消息"框内空白"的根因）
 * 3. 表情白名单模式隐藏量大（多数消息被标记），边界路径暴露概率远高于关键词过滤
 *
 * v18 方案：在消息数组进入 ChatActivity 之前直接剔除，全部入口收敛于两个方法
 * （均为超大方法，R8 不会内联，按名 hook 稳定）：
 * - didReceivedNotification_messagesDidLoad(int,int,Object[]) — 全部加载路径
 *   （首次进入/向上翻历史/跳转/合并对话）。NotificationCenter 参数数组布局：
 *   [1]=count(int) [2]=ArrayList&lt;MessageObject&gt; [14]=mode(int)
 * - processNewMessages(ArrayList,boolean) — 实时新消息 + 赞助消息
 *
 * 通过 chain.proceed(newArgs) 传入剔除后的副本，不污染其他观察者共享的原数组。
 * 行号/未读线/messagesDict 由 TG 按"消息不存在"的语义自洽处理，无需任何清理。
 * 日期分隔行（isDateObject=true，TG 本地构造）保留，不参与过滤。
 *
 * 已知取舍：
 * - 已在屏消息不追溯（配置变更后重新进入频道生效，与之前版本一致）
 * - 白名单模式下若某批消息全部不达标则该批全部隐藏，向上翻页继续加载更早消息
 *
 * 日志tag: TGClean-Keyword
 */
public class KeywordFilterHook {
    private static final String TAG = "TGClean-Keyword";
    private static final int MAX_LOGGED_KEYS = 10000;

    /**
     * 日志去重 key：msgId 是每频道独立自增的，跨频道必然碰撞
     * （频道A的100和频道B的100是两条不同消息）。
     * 用 dialogId 高32位 + msgId 低32位 组成 key（仅用于日志限流）。
     */
    private static long comboKey(long dialogId, int msgId) {
        return (dialogId << 32) | (msgId & 0xffffffffL);
    }
    private static final Set<Long> loggedKeys = ConcurrentHashMap.newKeySet();

    // ─── 反射字段缓存 ───
    private static volatile Field fMessageOwner;    // MessageObject.messageOwner
    private static volatile Field fIsDateObject;    // MessageObject.isDateObject（日期分隔行）
    private static volatile Field fTlMessage;       // TLRPC.Message.message
    private static volatile Field fTlMessageId;     // TLRPC.Message.id
    private static volatile Field fTlDialogId;      // TLRPC.Message.dialog_id
    private static volatile Field fTlPeerId;        // TLRPC.Message.peer_id
    private static volatile Field fTlReactions;     // TLRPC.Message.reactions
    private static volatile boolean fieldsResolved = false;

    private static void resolveFields(ClassLoader cl) {
        if (fieldsResolved) return;
        synchronized (KeywordFilterHook.class) {
            if (fieldsResolved) return;
            try {
                Class<?> moClass = cl.loadClass("org.telegram.messenger.MessageObject");
                Class<?> tlMsgClass = cl.loadClass("org.telegram.tgnet.TLRPC$Message");

                fMessageOwner = moClass.getDeclaredField("messageOwner");
                fMessageOwner.setAccessible(true);

                try {
                    fIsDateObject = moClass.getDeclaredField("isDateObject");
                    fIsDateObject.setAccessible(true);
                } catch (NoSuchFieldException ignored) {}

                fTlMessage = tlMsgClass.getDeclaredField("message");
                fTlMessage.setAccessible(true);

                fTlMessageId = tlMsgClass.getDeclaredField("id");
                fTlMessageId.setAccessible(true);

                fTlDialogId = tlMsgClass.getDeclaredField("dialog_id");
                fTlDialogId.setAccessible(true);

                fTlPeerId = tlMsgClass.getDeclaredField("peer_id");
                fTlPeerId.setAccessible(true);

                fTlReactions = tlMsgClass.getDeclaredField("reactions");
                fTlReactions.setAccessible(true);

                fieldsResolved = true;
            } catch (Throwable t) {
                // fieldsResolved stays false
            }
        }
    }

    public static void hook(ClassLoader cl, XposedModule module, FilterConfig config) {
        KeywordEngine engine = new KeywordEngine(config);

        module.log(Log.INFO, TAG, "=== Filter Config Status ===");
        module.log(Log.INFO, TAG, "Enabled: " + config.isEnabled());
        module.log(Log.INFO, TAG, "UseRegex: " + config.isUseRegex());
        module.log(Log.INFO, TAG, "GlobalKeywords: " + config.getGlobalKeywords());
        module.log(Log.INFO, TAG, "Whitelist: " + config.getWhitelist());
        module.log(Log.INFO, TAG, "ReactionsRules: " + config.getReactionsChannelRules());

        resolveFields(cl);
        if (fMessageOwner == null) {
            module.log(Log.ERROR, TAG, "Critical fields not resolved! Aborting.");
            return;
        }

        hookMessagesDidLoad(cl, module, engine);
        hookProcessNewMessages(cl, module, engine);
    }

    // ═══════════════════════════════════════════════════
    // 入口1：didReceivedNotification_messagesDidLoad — 全部加载路径
    // 签名: private void (int id, int account, Object... args)
    // ═══════════════════════════════════════════════════

    private static void hookMessagesDidLoad(ClassLoader cl, XposedModule module,
                                            KeywordEngine engine) {
        try {
            Class<?> caClass = cl.loadClass("org.telegram.ui.ChatActivity");
            Method target = caClass.getDeclaredMethod(
                    "didReceivedNotification_messagesDidLoad",
                    int.class, int.class, Object[].class);
            target.setAccessible(true);

            module.hook(target).intercept(chain -> {
                try {
                    List<Object> args = chain.getArgs();
                    // varargs 直接传数组时 getArgs().get(2) 就是 NotificationCenter 的 Object[]
                    if (args.size() == 3 && args.get(2) instanceof Object[]) {
                        Object[] notifArgs = (Object[]) args.get(2);
                        if (notifArgs.length > 14 && notifArgs[2] instanceof List
                                && Integer.valueOf(0).equals(notifArgs[14])) { // MODE_DEFAULT
                            List<?> arr = (List<?>) notifArgs[2];
                            ArrayList<Object> kept = filterBatch(arr, engine, module);
                            if (kept.size() != arr.size()) {
                                Object[] newNotifArgs = notifArgs.clone();
                                // count 必须与数组同步缩减：TG 用 size!=count 判断历史是否到底
                                newNotifArgs[1] = kept.size();
                                newNotifArgs[2] = kept;
                                return chain.proceed(new Object[]{
                                        args.get(0), args.get(1), newNotifArgs});
                            }
                        }
                    }
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "messagesDidLoad filter error", t);
                }
                return chain.proceed();
            });

            module.log(Log.INFO, TAG, "=== Hooked didReceivedNotification_messagesDidLoad ===");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG,
                    "FATAL: failed to hook didReceivedNotification_messagesDidLoad — "
                            + "过滤将完全不生效! " + t.getMessage(), t);
        }
    }

    // ═══════════════════════════════════════════════════
    // 入口2：processNewMessages — 实时新消息 + 赞助消息
    // 签名: private void (ArrayList<MessageObject> arr, boolean animatedFromBottom)
    // ═══════════════════════════════════════════════════

    private static void hookProcessNewMessages(ClassLoader cl, XposedModule module,
                                               KeywordEngine engine) {
        try {
            Class<?> caClass = cl.loadClass("org.telegram.ui.ChatActivity");
            Method target = caClass.getDeclaredMethod(
                    "processNewMessages", ArrayList.class, boolean.class);
            target.setAccessible(true);

            module.hook(target).intercept(chain -> {
                try {
                    List<Object> args = chain.getArgs();
                    if (args.size() == 2 && args.get(0) instanceof List) {
                        List<?> arr = (List<?>) args.get(0);
                        ArrayList<Object> kept = filterBatch(arr, engine, module);
                        if (kept.size() != arr.size()) {
                            return chain.proceed(new Object[]{kept, args.get(1)});
                        }
                    }
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "processNewMessages filter error", t);
                }
                return chain.proceed();
            });

            module.log(Log.INFO, TAG, "=== Hooked processNewMessages ===");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG,
                    "FATAL: failed to hook processNewMessages — 实时消息过滤不生效! "
                            + t.getMessage(), t);
        }
    }

    // ═══════════════════════════════════════════════════
    // 批量过滤与单条评估
    // ═══════════════════════════════════════════════════

    private static ArrayList<Object> filterBatch(List<?> arr, KeywordEngine engine,
                                                 XposedModule module) {
        ArrayList<Object> kept = new ArrayList<>(arr.size());
        boolean enabled = engine.isEnabled();
        int dropped = 0;
        for (Object obj : arr) {
            if (obj == null || !enabled || !shouldHide(obj, engine)) {
                kept.add(obj);
            } else {
                dropped++;
                logFiltered(module, obj);
            }
        }
        if (dropped > 0) {
            module.log(Log.INFO, TAG,
                    "Batch filtered: dropped " + dropped + "/" + arr.size());
        }
        return kept;
    }

    /** 评估单条消息是否应隐藏（评估失败一律放行） */
    private static boolean shouldHide(Object messageObject, KeywordEngine engine) {
        try {
            // 日期分隔行是 TG 本地构造的 UI 结构，保留
            if (fIsDateObject != null && fIsDateObject.getBoolean(messageObject)) {
                return false;
            }
            Object owner = fMessageOwner.get(messageObject);
            if (owner == null) return false;

            long dialogId = 0;
            if (fTlDialogId != null) {
                dialogId = fTlDialogId.getLong(owner);
            }
            if (dialogId == 0) {
                dialogId = computeDialogId(owner);
            }
            if (dialogId == 0) return false;

            String text = null;
            if (fTlMessage != null) {
                Object val = fTlMessage.get(owner);
                if (val instanceof String) text = (String) val;
            }

            Object reactions = null;
            if (fTlReactions != null) {
                reactions = fTlReactions.get(owner);
            }

            return engine.shouldFilter(text, dialogId, reactions);
        } catch (Throwable t) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════

    /**
     * 从 peer_id 计算 dialogId（dialog_id 为 0 时的兜底路径）。
     *
     * ⚠️ Telegram 已完成 64 位 ID 迁移：TLRPC.Peer 的 user_id/chat_id/channel_id
     * 均为 long（Layer 228 验证）。反射必须用 getLong()，getInt() 会抛
     * IllegalArgumentException。字段查找需覆盖父类（Peer 是抽象基类，
     * TL_peerUser/TL_peerChat/TL_peerChannel 只是空子类）。
     */
    private static long computeDialogId(Object tlMessage) {
        try {
            Object peerId = fTlPeerId != null ? fTlPeerId.get(tlMessage) : null;
            if (peerId == null) return 0;

            long chatId = getLongField(peerId, "chat_id");
            if (chatId != 0) return -chatId;

            long channelId = getLongField(peerId, "channel_id");
            if (channelId != 0) return -channelId;

            return getLongField(peerId, "user_id");
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 在继承链中查找 long 字段并读取（Peer 子类不声明字段，字段全在抽象基类上） */
    private static long getLongField(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getLong(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void logFiltered(XposedModule module, Object messageObject) {
        try {
            Object owner = fMessageOwner.get(messageObject);
            if (owner == null) return;

            String text = null;
            if (fTlMessage != null) {
                Object val = fTlMessage.get(owner);
                if (val instanceof String) text = (String) val;
            }

            int msgId = 0;
            if (fTlMessageId != null) {
                msgId = fTlMessageId.getInt(owner);
            }

            long dialogId = 0;
            if (fTlDialogId != null) {
                dialogId = fTlDialogId.getLong(owner);
            }

            // 同一 (dialogId, msgId) 只打印一次（重进频道会重新加载同一批消息）
            if (msgId != 0 && !loggedKeys.add(comboKey(dialogId, msgId))) return;
            if (loggedKeys.size() > MAX_LOGGED_KEYS) {
                loggedKeys.clear();
            }

            String preview = text != null && text.length() > 60
                    ? text.substring(0, 60) + "..." : text;
            module.log(Log.INFO, TAG,
                    "FILTERED [dialog=" + dialogId + ", msgId=" + msgId + "]: " + preview);
        } catch (Throwable ignored) {}
    }
}
