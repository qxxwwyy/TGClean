package com.tgclean.hooks;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.tgclean.config.FilterConfig;
import com.tgclean.filter.KeywordEngine;

import io.github.libxposed.api.XposedModule;

/**
 * 关键词过滤Hook — 构造函数标记 + Adapter清理 双阶段方案
 *
 * 阶段1（检测）：Hook MessageObject 构造函数，关键词匹配时设置 deleted=true
 * 阶段2（清理）：Hook ChatActivityAdapter.updateRowsSafe()，在行数重算前
 *         从 ChatActivity.messages 列表中移除所有 deleted=true 的消息
 *
 * 这样 rowCount 正确反映过滤后的消息数，零占位。
 *
 * 性能优化：
 * - 使用 filteredIds Set 去重，避免同一消息被12个构造函数重载重复标记
 * - 缓存所有反射字段为 static volatile
 * - cachedThis0Field 缓存内部类 this$0 字段
 *
 * 日志tag: TGClean-Keyword
 */
public class KeywordFilterHook {
    private static final String TAG = "TGClean-Keyword";
    private static final int MAX_FILTERED_IDS = 10000;

    // ─── 去重集合：避免同一消息被多个构造函数重载重复标记 ───
    private static final Set<Integer> pendingFilterIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> loggedIds = ConcurrentHashMap.newKeySet();

    // ─── 反射字段缓存 ───
    private static volatile Field fMessageOwner;    // MessageObject.messageOwner
    private static volatile Field fDeletedField;     // MessageObject.deleted
    private static volatile Field fTlMessage;       // TLRPC.Message.message
    private static volatile Field fTlMessageId;     // TLRPC.Message.id
    private static volatile Field fTlDialogId;      // TLRPC.Message.dialog_id
    private static volatile Field fTlPeerId;        // TLRPC.Message.peer_id
    private static volatile Field fTlReactions;     // TLRPC.Message.reactions
    private static volatile Field fMessagesField;   // ChatActivity.messages (ArrayList)
    private static volatile Field fMessagesDictField; // ChatActivity.messagesDict
    private static volatile Field fMessagesByDaysField; // ChatActivity.messagesByDays
    private static volatile Field cachedThis0Field; // ChatActivityAdapter.this$0
    private static volatile boolean fieldsResolved = false;

    private static void resolveFields(ClassLoader cl) {
        if (fieldsResolved) return;
        synchronized (KeywordFilterHook.class) {
            if (fieldsResolved) return;
            try {
                Class<?> moClass = cl.loadClass("org.telegram.messenger.MessageObject");
                Class<?> tlMsgClass = cl.loadClass("org.telegram.tgnet.TLRPC$Message");
                Class<?> caClass = cl.loadClass("org.telegram.ui.ChatActivity");

                fMessageOwner = moClass.getDeclaredField("messageOwner");
                fMessageOwner.setAccessible(true);

                fDeletedField = moClass.getDeclaredField("deleted");
                fDeletedField.setAccessible(true);

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

                // ChatActivity 字段
                try {
                    fMessagesField = caClass.getDeclaredField("messages");
                    fMessagesField.setAccessible(true);
                } catch (NoSuchFieldException ignored) {}

                try {
                    fMessagesDictField = caClass.getDeclaredField("messagesDict");
                    fMessagesDictField.setAccessible(true);
                } catch (NoSuchFieldException ignored) {}

                try {
                    fMessagesByDaysField = caClass.getDeclaredField("messagesByDays");
                    fMessagesByDaysField.setAccessible(true);
                } catch (NoSuchFieldException ignored) {}

                // ChatActivityAdapter.this$0
                try {
                    Class<?> adapterClass = cl.loadClass("org.telegram.ui.ChatActivity$ChatActivityAdapter");
                    cachedThis0Field = adapterClass.getDeclaredField("this$0");
                    cachedThis0Field.setAccessible(true);
                } catch (ClassNotFoundException | NoSuchFieldException ignored) {}

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
        module.log(Log.INFO, TAG, "ChannelRules: " + config.getChannelKeywords());

        resolveFields(cl);
        if (fMessageOwner == null || fDeletedField == null) {
            module.log(Log.ERROR, TAG, "Critical fields not resolved! Aborting.");
            return;
        }

        // 阶段1：构造函数标记
        hookMessageObjectConstructors(cl, module, engine);

        // 阶段2：Adapter清理
        hookAdapterUpdateRows(cl, module);
    }

    // ═══════════════════════════════════════════════════
    // 阶段1：构造函数Hook — 标记 deleted=true
    // ═══════════════════════════════════════════════════

    private static void hookMessageObjectConstructors(ClassLoader cl, XposedModule module,
                                                       KeywordEngine engine) {
        try {
            Class<?> messageObjectClass = cl.loadClass("org.telegram.messenger.MessageObject");
            Class<?> tlMsgClass = cl.loadClass("org.telegram.tgnet.TLRPC$Message");

            Constructor<?>[] constructors = messageObjectClass.getDeclaredConstructors();
            module.log(Log.INFO, TAG, "=== Scanning MessageObject constructors ("
                    + constructors.length + " total) ===");

            int hookedCount = 0;
            for (Constructor<?> ctor : constructors) {
                Class<?>[] params = ctor.getParameterTypes();
                boolean hasTlMessage = false;
                for (Class<?> p : params) {
                    if (p == tlMsgClass) {
                        hasTlMessage = true;
                        break;
                    }
                }
                if (!hasTlMessage) continue;

                StringBuilder sb = new StringBuilder("FOUND ctor(");
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")");
                module.log(Log.INFO, TAG, sb.toString());

                try {
                    ctor.setAccessible(true);
                    module.hook(ctor).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            if (!engine.isEnabled()) return result;
                            Object thisObj = chain.getThisObject();
                            if (thisObj == null) return result;

                            // 已标记过，跳过（去重）
                            if (fDeletedField.getBoolean(thisObj)) return result;

                            Object owner = fMessageOwner.get(thisObj);
                            if (owner == null) return result;

                            // 获取msgId用于去重
                            int msgId = 0;
                            if (fTlMessageId != null) {
                                msgId = fTlMessageId.getInt(owner);
                            }

                            // 如果这个msgId已经在pending集合中，说明其他构造函数已标记，跳过
                            if (msgId != 0 && pendingFilterIds.contains(msgId)) return result;

                            String text = null;
                            if (fTlMessage != null) {
                                Object val = fTlMessage.get(owner);
                                if (val instanceof String) text = (String) val;
                            }
                            if (text == null || text.isEmpty()) return result;

                            long dialogId = 0;
                            if (fTlDialogId != null) {
                                dialogId = fTlDialogId.getLong(owner);
                            }
                            if (dialogId == 0) {
                                dialogId = computeDialogId(owner);
                            }

                            Object reactions = null;
                            if (fTlReactions != null) {
                                reactions = fTlReactions.get(owner);
                            }

                            if (engine.shouldFilter(text, dialogId, reactions)) {
                                fDeletedField.setBoolean(thisObj, true);
                                // 加入pending集合，防止其他构造函数重复处理
                                if (msgId != 0) {
                                    pendingFilterIds.add(msgId);
                                    // 防止内存泄漏
                                    if (pendingFilterIds.size() > MAX_FILTERED_IDS) {
                                        pendingFilterIds.clear();
                                    }
                                }
                                // 去重日志：同一msgId只打印一次
                                if (msgId != 0 && loggedIds.add(msgId)) {
                                    if (loggedIds.size() > MAX_FILTERED_IDS) {
                                        loggedIds.clear();
                                    }
                                    logFiltered(module, owner);
                                }
                            }
                        } catch (Throwable t) {
                            // 静默失败
                        }
                        return result;
                    });
                    hookedCount++;
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "Failed to hook ctor: " + t.getMessage());
                }
            }

            module.log(Log.INFO, TAG, "=== Phase 1: hooked " + hookedCount + " constructors ===");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook MessageObject constructors", t);
        }
    }

    // ═══════════════════════════════════════════════════
    // 阶段2：Adapter清理 — 从messages列表移除deleted消息
    // ═══════════════════════════════════════════════════

    private static void hookAdapterUpdateRows(ClassLoader cl, XposedModule module) {
        try {
            // ChatActivityAdapter 是 ChatActivity 的内部类
            Class<?> adapterClass = cl.loadClass("org.telegram.ui.ChatActivity$ChatActivityAdapter");

            // 查找 updateRowsSafe 方法（它是 public 的）
            Method targetMethod = null;
            for (Method m : adapterClass.getDeclaredMethods()) {
                if ("updateRowsSafe".equals(m.getName())) {
                    targetMethod = m;
                    break;
                }
            }
            if (targetMethod == null) {
                module.log(Log.ERROR, TAG, "updateRowsSafe not found in ChatActivityAdapter");
                return;
            }

            targetMethod.setAccessible(true);
            module.hook(targetMethod).intercept(chain -> {
                try {
                    Object adapter = chain.getThisObject();

                    // 使用缓存的 this$0 字段
                    Object chatActivity = null;
                    if (cachedThis0Field != null) {
                        chatActivity = cachedThis0Field.get(adapter);
                    } else {
                        Field thisField = adapter.getClass().getDeclaredField("this$0");
                        thisField.setAccessible(true);
                        chatActivity = thisField.get(adapter);
                    }

                    if (chatActivity != null && fMessagesField != null) {
                        @SuppressWarnings("unchecked")
                        List<Object> messages = (List<Object>) fMessagesField.get(chatActivity);
                        if (messages != null && !messages.isEmpty()) {
                            int removed = 0;
                            Iterator<Object> it = messages.iterator();
                            while (it.hasNext()) {
                                Object msgObj = it.next();
                                try {
                                    if (fDeletedField.getBoolean(msgObj)) {
                                        // 同步清理 messagesDict
                                        if (fMessagesDictField != null) {
                                            try {
                                                Object msgOwner = fMessageOwner.get(msgObj);
                                                if (msgOwner != null && fTlMessageId != null) {
                                                    int msgId = fTlMessageId.getInt(msgOwner);
                                                    Object dicts = fMessagesDictField.get(chatActivity);
                                                    if (dicts instanceof Object[]) {
                                                        for (Object dict : (Object[]) dicts) {
                                                            if (dict != null) {
                                                                try {
                                                                    Method removeMethod = dict.getClass()
                                                                            .getMethod("remove", Object.class);
                                                                    removeMethod.invoke(dict, msgId);
                                                                } catch (Throwable ignored) {}
                                                            }
                                                        }
                                                    }
                                                    // 从pending集合中移除已清理的msgId
                                                    pendingFilterIds.remove(msgId);
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                        it.remove();
                                        removed++;
                                    }
                                } catch (Throwable ignored) {}
                            }
                            if (removed > 0) {
                                module.log(Log.INFO, TAG,
                                        "Phase 2: removed " + removed + " deleted messages");
                            }
                        }
                    }
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "Phase 2 error", t);
                }
                return chain.proceed();
            });

            module.log(Log.INFO, TAG, "=== Phase 2: hooked updateRowsSafe ===");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook ChatActivityAdapter", t);
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════

    private static long computeDialogId(Object tlMessage) {
        try {
            Object peerId = fTlPeerId != null ? fTlPeerId.get(tlMessage) : null;
            if (peerId == null) return 0;

            try {
                Field f = peerId.getClass().getDeclaredField("channel_id");
                f.setAccessible(true);
                int id = f.getInt(peerId);
                if (id != 0) return -id;
            } catch (NoSuchFieldException ignored) {}

            try {
                Field f = peerId.getClass().getDeclaredField("chat_id");
                f.setAccessible(true);
                int id = f.getInt(peerId);
                if (id != 0) return -id;
            } catch (NoSuchFieldException ignored) {}

            try {
                Field f = peerId.getClass().getDeclaredField("user_id");
                f.setAccessible(true);
                return f.getInt(peerId);
            } catch (NoSuchFieldException ignored) {}
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void logFiltered(XposedModule module, Object tlMessage) {
        try {
            String text = null;
            if (fTlMessage != null) {
                Object val = fTlMessage.get(tlMessage);
                if (val instanceof String) text = (String) val;
            }

            int msgId = 0;
            if (fTlMessageId != null) {
                msgId = fTlMessageId.getInt(tlMessage);
            }

            long dialogId = 0;
            if (fTlDialogId != null) {
                dialogId = fTlDialogId.getLong(tlMessage);
            }

            String preview = text != null && text.length() > 60
                    ? text.substring(0, 60) + "..." : text;
            module.log(Log.INFO, TAG,
                    "FILTERED [dialog=" + dialogId + ", msgId=" + msgId + "]: " + preview);
        } catch (Throwable ignored) {}
    }
}
