package com.tgclean.hooks;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.tgclean.config.FilterConfig;
import com.tgclean.config.ReactionsRule;
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
    private static volatile Field fTlDate;          // TLRPC.Message.date
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

                try {
                    fTlDate = tlMsgClass.getDeclaredField("date");
                    fTlDate.setAccessible(true);
                } catch (NoSuchFieldException ignored) {}

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
        for (Map.Entry<Long, ReactionsRule> e : config.getReactionsChannelRules().entrySet()) {
            module.log(Log.INFO, TAG, "RX-INIT rule dialog=" + e.getKey()
                    + " [" + e.getValue().describeWithCodepoints() + "]");
        }

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
                Object[] newNotifArgs = null;
                try {
                    List<Object> args = chain.getArgs();
                    // varargs 直接传数组时 getArgs().get(2) 就是 NotificationCenter 的 Object[]
                    if (args.size() == 3 && args.get(2) instanceof Object[]) {
                        Object[] notifArgs = (Object[]) args.get(2);
                        if (notifArgs.length > 14 && notifArgs[2] instanceof List
                                && Integer.valueOf(0).equals(notifArgs[14])) { // MODE_DEFAULT

                            Object chatActivity = chain.getThisObject();

                            // guid 门控（与 TG handler 内部语义严格一致：guid != classGuid 即忽略）：
                            // messagesDidLoad 投递给所有同频道实例，非本实例的批次不过滤、
                            // 不动锚点、不级联，避免多实例请求流交叉污染。
                            // 用标志位而非提前 return，保证 proceed 只在 try 外执行一次。
                            boolean owner = true;
                            Integer reqGuid = (Integer) notifArgs[10];
                            if (reqGuid != null) {
                                int instanceGuid = getIntFieldValue(
                                        chatActivity.getClass(), chatActivity, "classGuid");
                                owner = instanceGuid == reqGuid;
                            }

                            if (owner) {
                                List<?> arr = (List<?>) notifArgs[2];
                                ArrayList<Object> kept = filterBatch(arr, engine, module);
                                if (kept.size() != arr.size()) {
                                    newNotifArgs = notifArgs.clone();
                                    // count 必须与数组同步缩减：TG 用 size!=count 判断历史是否到底
                                    newNotifArgs[1] = kept.size();
                                    newNotifArgs[2] = kept;

                                    // 被滤掉的批次同样要推进滚动锚点，否则原生上滑
                                    // 会反复请求同一段已丢弃范围（永远加载不动）
                                    updateScrollAnchors(chatActivity, notifArgs, arr, module);
                                    maybeCascade(chatActivity, module, notifArgs,
                                            arr, kept);
                                } else {
                                    // 零过滤的健康批次同样重置级联计数（审计 F-6）：
                                    // 否则混合场景下额度只增不减，最终误判"耗尽"
                                    resetCascadeIfHealthy(chatActivity, module, arr);
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "messagesDidLoad filter error", t);
                }
                if (newNotifArgs != null) {
                    List<Object> args = chain.getArgs();
                    return chain.proceed(new Object[]{
                            args.get(0), args.get(1), newNotifArgs});
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
    // 级联加载：批次被滤空/剩余过少 → 自动请求更早历史
    //
    // 背景：TG 的向上翻页依赖已有行可滚动（loadingUpRow 进入可视区触发，
    // 且 messages 为空时 loadingUpRow=-5 连转圈行都没有）。白名单把整批
    // 消息滤空后页面显示"暂无消息"，用户无法滚动 → 历史无法继续加载 →
    // 筛选池无法扩大（结构性死锁）。
    //
    // 方案：过滤导致 kept < CASCADE_MIN_ROWS 且未到历史尽头（isEnd=false）
    // 时，镜像 TG 自身滚动加载的调用方式（ChatActivity L12375 模式：
    // waitingForLoad.add(lastLoadIndex) → loadMessages(..., lastLoadIndex++)）
    // 主动请求更早的 50 条。新批次仍走本 hook → 命中则继续级联，直到：
    // 出现足量达标消息 / 历史到底（isEnd=true）/ 达到安全上限。
    // ═══════════════════════════════════════════════════

    /** 剩余行数低于此值视为"无法滚动"（一屏约 8-12 条消息） */
    private static final int CASCADE_MIN_ROWS = 5;
    /** 级联安全上限（100 批 × 50 条 = 5000 条），防止在大型频道失控 */
    private static final int CASCADE_MAX_BATCHES = 100;
    private static final int CASCADE_BATCH_SIZE = 50;
    /** classGuid → 已级联批次数（classGuid 每 ChatActivity 实例唯一） */
    private static final ConcurrentHashMap<Integer, Integer> cascadeCount =
            new ConcurrentHashMap<>();
    /** classGuid → 是否有级联请求在途（防止与 TG 自身重试循环交叠时重复发起） */
    private static final ConcurrentHashMap<Integer, Boolean> cascadeInFlight =
            new ConcurrentHashMap<>();
    /** classGuid → 上次级联锚点（无推进的重复批次不消耗上限额度） */
    private static final ConcurrentHashMap<Integer, Integer> lastCascadeAnchor =
            new ConcurrentHashMap<>();
    /** classGuid → 连续"锚点未推进"级联发起次数（防病理性循环） */
    private static final ConcurrentHashMap<Integer, Integer> stuckFireCount =
            new ConcurrentHashMap<>();
    /** 连续无推进的级联发起上限 */
    private static final int STUCK_FIRE_LIMIT = 10;

    /** 零过滤的健康批次：真实行数充足时重置级联计数（与 maybeCascade 内的健康分支同语义） */
    private static void resetCascadeIfHealthy(Object chatActivity, XposedModule module,
                                               List<?> arr) {
        try {
            int realRows = 0;
            for (Object obj : arr) {
                if (fIsDateObject == null || !fIsDateObject.getBoolean(obj)) realRows++;
            }
            if (realRows >= CASCADE_MIN_ROWS && chatActivity != null) {
                Integer guidObj = null;
                try {
                    guidObj = getIntFieldValue(chatActivity.getClass(), chatActivity, "classGuid");
                } catch (Throwable ignored) {}
                if (guidObj != null && guidObj != 0) {
                    cascadeCount.remove(guidObj);
                    lastCascadeAnchor.remove(guidObj);
                    stuckFireCount.remove(guidObj);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void maybeCascade(Object chatActivity, XposedModule module,
                                     Object[] notifArgs, List<?> originalArr,
                                     ArrayList<Object> kept) {
        try {
            if (chatActivity == null) return;
            Integer guidObj = (Integer) notifArgs[10];
            boolean isEnd = Boolean.TRUE.equals(notifArgs[9]);

            // 任何新批次到达都意味着上一个在途请求大概率已落地，清在途标记
            cascadeInFlight.remove(guidObj);

            // 只统计真实消息行：仅剩日期分隔行的视图同样无法滚动
            int realRows = 0;
            for (Object obj : kept) {
                try {
                    if (fIsDateObject == null || !fIsDateObject.getBoolean(obj)) realRows++;
                } catch (Throwable ignored) {}
            }
            if (realRows >= CASCADE_MIN_ROWS) {
                cascadeCount.remove(guidObj); // 内容健康，重置计数
                lastCascadeAnchor.remove(guidObj);
                stuckFireCount.remove(guidObj);
                return;
            }
            if (isEnd) return; // 历史已到底，确实没有达标消息

            int batchMin = oldestPositiveMessageId(originalArr);
            if (batchMin <= 0) return;

            // 下降前沿（frontier）：本实例已到达的最老消息 id，只降不升。
            // TG 在 messagesByDays 为空时会退化为 max_id=0 反复重取最新窗口
            // （其批次 min 高于前沿），级联必须始终从前沿继续请求更早历史，
            // 而不是被 TG 的重复批次带偏回最新端。
            int guid = guidObj;
            Integer prevFrontier = lastCascadeAnchor.put(guid, batchMin);
            int frontier = Math.min(batchMin, prevFrontier == null ? batchMin : prevFrontier);
            lastCascadeAnchor.put(guid, frontier);
            boolean advanced = prevFrontier == null || frontier < prevFrontier;

            int n;
            if (advanced) {
                // 前沿推进 = 正常下降链，计入额度
                n = cascadeCount.merge(guid, 1, Integer::sum);
                if (cascadeCount.size() > 100) cascadeCount.clear(); // 防泄漏
                if (n > CASCADE_MAX_BATCHES) {
                    if (n == CASCADE_MAX_BATCHES + 1) {
                        module.log(Log.WARN, TAG, "Cascade cap reached ("
                                + CASCADE_MAX_BATCHES + " batches ≈ "
                                + (CASCADE_MAX_BATCHES * CASCADE_BATCH_SIZE)
                                + " msgs) for dialog=" + notifArgs[0] + ", giving up");
                    }
                    return;
                }
                stuckFireCount.remove(guid);
            } else {
                // 前沿未推进 = TG 空视图重复批次。仍从前沿发起（继续下降），
                // 不消耗额度；连续多次无推进则放弃（防病理性循环）。
                // 额度耗尽后此通道同样关闭（硬上限，审计 N-1）。
                if (cascadeCount.getOrDefault(guid, 0) > CASCADE_MAX_BATCHES) return;
                int stuck = stuckFireCount.merge(guid, 1, Integer::sum);
                if (stuck > STUCK_FIRE_LIMIT) {
                    if (stuck == STUCK_FIRE_LIMIT + 1) {
                        module.log(Log.WARN, TAG, "Cascade stuck-fire limit reached ("
                                + STUCK_FIRE_LIMIT + ") for dialog=" + notifArgs[0]);
                    }
                    return;
                }
                n = cascadeCount.getOrDefault(guid, 0);
            }
            // 已有在途级联（尚未收到响应批次）时不重复发起
            if (cascadeInFlight.putIfAbsent(guid, Boolean.TRUE) != null) return;

            long dialogId = (Long) notifArgs[0];
            int mode = (Integer) notifArgs[14];
            module.log(Log.INFO, TAG, "Cascade #" + n + (advanced ? "" : "(stuck)")
                    + ": only " + realRows + " real rows survived, auto-loading "
                    + CASCADE_BATCH_SIZE + " older messages (dialog=" + dialogId
                    + ", older<" + frontier + ")");

            // post 到主线程末尾执行，避免在 NotificationCenter 派发循环内重入
            final int anchor = frontier;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    triggerCascadeLoad(chatActivity, module, dialogId, anchor, mode));
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "maybeCascade error: " + t.getMessage());
        }
    }

    /**
     * 反射调用 MessagesController.loadMessages 请求更早历史。
     * 参数镜像 ChatActivity 自身的滚动加载调用（load_type=0 向更早方向）。
     */
    private static void triggerCascadeLoad(Object chatActivity, XposedModule module,
                                           long dialogId, int oldestId, int mode) {
        try {
            Class<?> caClass = chatActivity.getClass();
            long mergeDialogId = getLongFieldValue(caClass, chatActivity, "mergeDialogId");
            int classGuid = getIntFieldValue(caClass, chatActivity, "classGuid");
            long threadMessageId = getLongFieldValue(caClass, chatActivity, "threadMessageId");
            int replyMaxReadId = getIntFieldValue(caClass, chatActivity, "replyMaxReadId");
            boolean isTopic = getBooleanFieldValue(caClass, chatActivity, "isTopic");
            int currentAccount = getIntFieldValue(caClass, chatActivity, "currentAccount");

            // lastLoadIndex：读值 → 登记 waitingForLoad → 传值 → 回写+1（与 TG 自身一致）
            Field loadIndexField = findFieldInHierarchy(caClass, "lastLoadIndex");
            if (loadIndexField == null) return;
            loadIndexField.setAccessible(true);
            int loadIndex = loadIndexField.getInt(chatActivity);

            Field waitingField = findFieldInHierarchy(caClass, "waitingForLoad");
            if (waitingField != null) {
                // ⚠️ 必须先 setAccessible（private 字段，漏掉会 IllegalAccessException
                // 导致整个级联加载静默中止 — v21 实测教训）
                waitingField.setAccessible(true);
                Object waitingList = waitingField.get(chatActivity);
                if (waitingList instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) waitingList;
                    list.add(loadIndex);
                }
            }
            loadIndexField.setInt(chatActivity, loadIndex + 1);

            ClassLoader cl = caClass.getClassLoader();
            Class<?> mcClass = cl.loadClass("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, currentAccount);
            Method load = mcClass.getMethod("loadMessages",
                    long.class, long.class, boolean.class, int.class, int.class, int.class,
                    boolean.class, int.class, int.class, int.class, int.class, int.class,
                    long.class, int.class, int.class, boolean.class);
            try {
                load.invoke(mc, dialogId, mergeDialogId, false, CASCADE_BATCH_SIZE, oldestId,
                        0, true, 0, classGuid, 0, 0, mode, threadMessageId, replyMaxReadId,
                        loadIndex, isTopic);
            } catch (Throwable t) {
                // invoke 失败则撤销 waitingForLoad 登记，避免留下永不匹配的 stale token
                try {
                    if (waitingField != null) {
                        Object waitingList = waitingField.get(chatActivity);
                        if (waitingList instanceof List) {
                            ((List<?>) waitingList).remove(Integer.valueOf(loadIndex));
                        }
                    }
                } catch (Throwable ignored) {}
                throw t;
            }

            module.log(Log.INFO, TAG, "Cascade load ok: count=" + CASCADE_BATCH_SIZE
                    + " older<" + oldestId + " loadIndex=" + loadIndex);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Cascade load failed: " + t.getMessage());
        }
    }

    /**
     * 推进 TG 原生上滑加载的滚动锚点。
     *
     * TG 在 messagesDidLoad 的添加循环里用 min() 更新 maxMessageId/minDate，
     * 被过滤的消息不进循环 → 锚点停在"最老的存活消息"→ 上滑反复请求同一段
     * 已被丢弃的范围（表现为转圈后无事发生）。这里把整批（含被滤消息）的
     * 最老 id/日期计入锚点，使每次上滑都请求真正更早的历史。
     */
    private static void updateScrollAnchors(Object chatActivity, Object[] notifArgs,
                                             List<?> originalArr, XposedModule module) {
        try {
            if (chatActivity == null) return;
            Class<?> caClass = chatActivity.getClass();

            // ⚠️ 数组下标语义（对照 TG 源码 L20561）：主对话=0，合并对话=1。
            // args[11] 是单调递增的请求 token（lastLoadIndex++），不是下标 —
            // 早期版本误用导致锚点推进从第二批起全部失效（子代理审计 F-2）。
            long batchDialogId = (Long) notifArgs[0];
            long ownDialogId = getLongFieldValue(caClass, chatActivity, "dialog_id");
            int idx = batchDialogId == ownDialogId ? 0 : 1;

            int oldestId = 0;
            int oldestDate = 0;
            for (Object obj : originalArr) {
                try {
                    Object owner = fMessageOwner.get(obj);
                    if (owner == null) continue;
                    int id = fTlMessageId != null ? fTlMessageId.getInt(owner) : 0;
                    if (id > 0 && (oldestId == 0 || id < oldestId)) oldestId = id;
                    if (fTlDate != null) {
                        int date = fTlDate.getInt(owner);
                        if (date > 0 && (oldestDate == 0 || date < oldestDate)) oldestDate = date;
                    }
                } catch (Throwable ignored) {}
            }
            if (oldestId <= 0) return;

            Field maxField = findFieldInHierarchy(caClass, "maxMessageId");
            if (maxField != null) {
                maxField.setAccessible(true);
                int[] ids = (int[]) maxField.get(chatActivity);
                if (ids != null && idx < ids.length) {
                    ids[idx] = Math.min(ids[idx], oldestId);
                }
            }
            if (oldestDate > 0) {
                Field minDateField = findFieldInHierarchy(caClass, "minDate");
                if (minDateField != null) {
                    minDateField.setAccessible(true);
                    int[] dates = (int[]) minDateField.get(chatActivity);
                    if (dates != null && idx < dates.length) {
                        dates[idx] = dates[idx] == 0
                                ? oldestDate : Math.min(dates[idx], oldestDate);
                    }
                }
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "updateScrollAnchors error: " + t.getMessage());
        }
    }

    /** 批次中最小的正消息 id（跳过日期对象 id=0 与赞助消息负 id），作为加载更早历史的锚点 */
    private static int oldestPositiveMessageId(List<?> arr) {
        int min = 0;
        for (Object obj : arr) {
            try {
                Object owner = fMessageOwner.get(obj);
                if (owner == null || fTlMessageId == null) continue;
                int id = fTlMessageId.getInt(owner);
                if (id > 0 && (min == 0 || id < min)) min = id;
            } catch (Throwable ignored) {}
        }
        return min;
    }

    /** 沿继承链查找字段（ChatActivity 大量字段声明在自身/父类 BaseFragment 上） */
    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static long getLongFieldValue(Class<?> clazz, Object obj, String name) {
        try {
            Field f = findFieldInHierarchy(clazz, name);
            if (f != null) { f.setAccessible(true); return f.getLong(obj); }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int getIntFieldValue(Class<?> clazz, Object obj, String name) {
        try {
            Field f = findFieldInHierarchy(clazz, name);
            if (f != null) { f.setAccessible(true); return f.getInt(obj); }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static boolean getBooleanFieldValue(Class<?> clazz, Object obj, String name) {
        try {
            Field f = findFieldInHierarchy(clazz, name);
            if (f != null) { f.setAccessible(true); return f.getBoolean(obj); }
        } catch (Throwable ignored) {}
        return false;
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
                Object[] newArgs = null;
                try {
                    List<Object> args = chain.getArgs();
                    if (args.size() == 2 && args.get(0) instanceof List) {
                        List<?> arr = (List<?>) args.get(0);
                        ArrayList<Object> kept = filterBatch(arr, engine, module);
                        if (kept.size() != arr.size()) {
                            newArgs = new Object[]{kept, args.get(1)};
                        }
                    }
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "processNewMessages filter error", t);
                }
                if (newArgs != null) {
                    return chain.proceed(newArgs);
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
            if (obj == null || !enabled || !shouldHide(obj, engine, module)) {
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

    /** RX-DEBUG 调试预算：防止滚动加载时刷屏，进程内最多打印 40 条明细 */
    private static final java.util.concurrent.atomic.AtomicInteger rxDebugBudget =
            new java.util.concurrent.atomic.AtomicInteger(40);

    /** 评估单条消息是否应隐藏（评估失败一律放行） */
    private static boolean shouldHide(Object messageObject, KeywordEngine engine,
                                      XposedModule module) {
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

            boolean hide = engine.shouldFilter(text, dialogId, reactions);

            // 表情规则激活时输出调试明细（限量），用于定位计数读取/表情匹配问题
            ReactionsRule rule = engine.getActiveRule(dialogId);
            if (rule != null) {
                int budget = rxDebugBudget.getAndDecrement();
                if (budget > 0) {
                    int msgId = fTlMessageId != null ? fTlMessageId.getInt(owner) : 0;
                    module.log(Log.INFO, TAG, "RX-DEBUG dialog=" + dialogId
                            + " msg#" + msgId
                            + " rule=[" + rule.describeWithCodepoints() + "]"
                            + " " + KeywordEngine.debugReactions(reactions)
                            + " => hide=" + hide);
                } else if (budget == 0) {
                    module.log(Log.INFO, TAG,
                            "RX-DEBUG budget exhausted, further detail suppressed");
                }
            }

            return hide;
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
