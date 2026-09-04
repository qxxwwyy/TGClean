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
    private static volatile Method fGetContext;     // BaseFragment.getContext()（Toast/徽标用）
    private static volatile Method fGetParentActivity; // BaseFragment.getParentActivity()（僵尸链检测）
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
        // 关键词/白名单可能含隐私内容，仅调试日志开启时输出（发布前审计 M-3）
        if (config.isDebugLog()) {
            module.log(Log.INFO, TAG, "GlobalKeywords: " + config.getGlobalKeywords());
            module.log(Log.INFO, TAG, "Whitelist: " + config.getWhitelist());
        }
        for (Map.Entry<Long, ReactionsRule> e : config.getReactionsChannelRules().entrySet()) {
            module.log(Log.INFO, TAG, "RX-INIT rule dialog=" + e.getKey()
                    + " [" + e.getValue().describeWithCodepoints() + "]");
        }

        resolveFields(cl);
        if (fMessageOwner == null) {
            module.log(Log.ERROR, TAG, "Critical fields not resolved! Aborting.");
            return;
        }

        hookMessagesDidLoad(cl, module, engine, config);
        hookProcessNewMessages(cl, module, engine, config);
        hookFragmentDestroy(cl, module);
    }

    // ═══════════════════════════════════════════════════
    // 入口1：didReceivedNotification_messagesDidLoad — 全部加载路径
    // 签名: private void (int id, int account, Object... args)
    // ═══════════════════════════════════════════════════

    private static void hookMessagesDidLoad(ClassLoader cl, XposedModule module,
                                            KeywordEngine engine, FilterConfig config) {
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
                                if (instanceGuid == 0) warnGuidReflectionFailure(module);
                            }

                            if (owner) {
                                List<?> arr = (List<?>) notifArgs[2];
                                boolean debugLog = config.isDebugLog();
                                ArrayList<Object> kept = filterBatch(arr, engine, module, debugLog);
                                if (kept.size() != arr.size()) {
                                    newNotifArgs = notifArgs.clone();
                                    // count 必须与数组同步缩减：TG 用 size!=count 判断历史是否到底
                                    newNotifArgs[1] = kept.size();
                                    newNotifArgs[2] = kept;

                                    // 被滤掉的批次同样要推进滚动锚点，否则原生上滑
                                    // 会反复请求同一段已丢弃范围（永远加载不动）
                                    updateScrollAnchors(chatActivity, notifArgs, arr, module);
                                    maybeCascade(chatActivity, module, config, engine,
                                            notifArgs, arr, kept);
                                } else {
                                    // 零过滤的健康批次同样重置级联额度（审计 F-6）：
                                    // 否则混合场景下额度只增不减，最终误判"耗尽"
                                    resetCascadeIfHealthy(chatActivity, arr);
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
    // v29 控制流（高阈值整批滤空场景实测驱动重写）：
    // - 触发：仅当"前沿推进"——本批最老 id 深于本实例见过的最老 id。
    //   推进 = 上一个在途请求已落地，此时才发起下一批（消耗额度）。
    // - 忽略：非推进批次（TG 空视图重取的最新窗口 / 重复请求的回包）
    //   一律不发起、不计数。v28 在此路径上"照常发起 + stuck 计数"，
    //   结果重复回包自我复制：130ms 内同锚点 11 个在途请求，重复回包
    //   把 stuck 计数 10 连击烧断通道——前沿明明每 600ms 推进一次却必死。
    // - 单飞：任一时刻至多一个在途级联请求（putIfAbsent 时间戳）。
    // - 看门狗：在途请求 CASCADE_STALE_MS 内无推进响应视为丢失，重发
    //   一次（预算 WATCHDOG_MAX_REFIRES 次）。丢包恢复不再依赖计数。
    // - 锚点 oldestSeenAnchor = 本实例见过的最老消息 id，只降不升
    //   （merge min），健康批次同样并入——重置会让健康期后 TG 最新窗口
    //   重取以 prevFrontier==null 重新"推进"，把已扫描范围重扫一遍。
    // - 额度（cascadeCount）实例生命周期内单调：健康批次只休眠链、不清
    //   计数（v2.0.2：清零导致交替健康/滤空批次反复授满额度、徽章进度
    //   归零重启，用户观感即"从头再扫"）。
    // - 终点：历史到底（isEnd）或额度耗尽时 Toast 告知用户一次，
    //   不再沉默显示"暂无消息"让用户猜。
    // ═══════════════════════════════════════════════════

    /** 剩余行数低于此值视为"无法滚动"（一屏约 8-12 条消息） */
    private static final int CASCADE_MIN_ROWS = 5;
    /** 单批拉取条数（MessagesStorage LIMIT 上限 100，直接取满）。
     *  单链额度不设常量：由检索深度动态计算（每频道规则 maxDepth 覆盖全局
     *  默认 reactions_search_depth，见 maybeCascade）。额度在实例生命周期内
     *  单调递增不重置——健康批次清零会让交替出现的健康/滤空批次把额度
     *  反复授满（v2.0.2 日志确证：计数归零 + 预算重授 = 用户看到的
     *  "从头再扫"） */
    private static final int CASCADE_BATCH_SIZE = 100;
    /** 在途请求无推进响应视为丢失的时限（网络回源单程可达数秒，过短会误判重发） */
    private static final long CASCADE_STALE_MS = 4000;
    /** 看门狗连续重发上限。曾为 40（160s）：压栈僵尸实例的请求永远得不到
     *  推进响应，只能烧满预算自灭，期间叠加成请求风暴（v2.0.2 前夜实测
     *  9 条并发僵尸链）；真实丢包恢复 2-3 次重发足够，8 次已很宽裕 */
    private static final int WATCHDOG_MAX_REFIRES = 8;

    /** classGuid → 本链已推进批次数（额度；classGuid 每 ChatActivity 实例唯一） */
    private static final ConcurrentHashMap<Integer, Integer> cascadeCount =
            new ConcurrentHashMap<>();
    /** classGuid → 在途级联的发起时间戳（elapsedRealtime），单飞标记 */
    private static final ConcurrentHashMap<Integer, Long> cascadeInFlightAt =
            new ConcurrentHashMap<>();
    /** classGuid → 本实例见过的最老消息 id（下降前沿，只降不升，健康不重置） */
    private static final ConcurrentHashMap<Integer, Integer> oldestSeenAnchor =
            new ConcurrentHashMap<>();
    /** classGuid → 看门狗已重发次数 */
    private static final ConcurrentHashMap<Integer, Integer> watchdogFires =
            new ConcurrentHashMap<>();
    /** classGuid → 已发过终点提示（到底/额度耗尽只 Toast 一次） */
    private static final Set<Integer> terminalNotified =
            ConcurrentHashMap.newKeySet();
    /** classGuid → 级联期间累计达标行数（进度徽标显示用） */
    private static final ConcurrentHashMap<Integer, Integer> cascadeFound =
            new ConcurrentHashMap<>();
    /** 最近一个推进级联的 ChatActivity 实例（弱引用）。压栈旧实例的请求
     *  得不到推进响应（僵尸链形态），看门狗据此让位终止 */
    private static volatile java.lang.ref.WeakReference<Object> cascadeActiveActivity;

    /** 把批次的最老消息 id 并入锚点（merge min，只降不升） */
    private static void noteSeenAnchor(int guid, List<?> arr) {
        int batchMin = oldestPositiveMessageId(arr);
        if (batchMin <= 0) return;
        oldestSeenAnchor.merge(guid, batchMin, Math::min);
        if (oldestSeenAnchor.size() > 200) oldestSeenAnchor.clear();
    }

    /** classGuid 反射失败（恒 0）会让 guid 门控把整条过滤静默禁用——限频 WARN 供排障 */
    private static volatile long lastGuidWarnAt;
    private static void warnGuidReflectionFailure(XposedModule module) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastGuidWarnAt < 60_000) return;
        lastGuidWarnAt = now;
        module.log(Log.WARN, TAG, "classGuid reflection returned 0 — filtering gated off"
                + " (owner check fails), possible TG version drift");
    }

    /**
     * 零过滤的健康批次（与 maybeCascade 内的健康分支同语义）：
     * 解除单飞、续期看门狗、撤下检索徽标、批次范围并入锚点。
     * 额度/达标累计/终点提示一律保留——v2.0.2 之前这里全清，
     * 交替出现的健康/滤空批次把额度反复授满、徽章进度反复归零
     * （用户看到的"从头再扫"）。
     * 锚点只 merge min 不重置的原因：它记录"本实例见过的最老消息"，
     * 重置会让 TG 空视图随后重取的最新窗口以 prev==null 身份重新推进，
     * 把已扫描的整段历史重扫一遍。
     */
    private static void resetCascadeIfHealthy(Object chatActivity, List<?> arr) {
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
                    cascadeInFlightAt.remove(guidObj);
                    watchdogFires.remove(guidObj);
                    // 健康批次到达 → 链休眠。级联已开始过的实例改挂"暂停"汇总徽标
                    // （已筛/达标，10s 自动消失）：只有推进批次会重建徽标，交替出现的
                    // 健康/滤空流下"撤下→数秒后重建"的闪断正是"时灵时不灵"观感的
                    // 来源之一；未开始级联的实例维持原样撤徽标。后台实例（另一频道
                    // 持有徽标）不得动前台徽标。
                    if (badgeOwnedBy(chatActivity)) {
                        if (cascadeCount.containsKey(guidObj)) {
                            pauseCascadeBadge(chatActivity, guidObj);
                        } else {
                            removeCascadeBadge();
                        }
                    }
                    noteSeenAnchor(guidObj, arr);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void maybeCascade(Object chatActivity, XposedModule module,
                                     FilterConfig config, KeywordEngine engine,
                                     Object[] notifArgs, List<?> originalArr,
                                     ArrayList<Object> kept) {
        try {
            if (chatActivity == null) return;
            Integer guidObj = (Integer) notifArgs[10];
            if (guidObj == null || guidObj == 0) return;
            boolean isEnd = Boolean.TRUE.equals(notifArgs[9]);

            // 只统计真实消息行：仅剩日期分隔行的视图同样无法滚动
            int realRows = 0;
            for (Object obj : kept) {
                try {
                    if (fIsDateObject == null || !fIsDateObject.getBoolean(obj)) realRows++;
                } catch (Throwable ignored) {}
            }
            if (realRows >= CASCADE_MIN_ROWS) {
                // 内容健康 → 链休眠，但额度/达标累计/终点提示保留：
                // 用户上滑的原生回包常混有健康批次，全清会让下一个全滤空批次
                // 以 #1 重启、额度重新授满、徽章进度归零（v2.0.2 日志确证的
                // "500/深度 → 100/深度 从头再扫"）。锚点同样并入本批范围。
                // 徽标处理与 resetCascadeIfHealthy 同款：暂停汇总而非闪断撤下。
                cascadeInFlightAt.remove(guidObj);
                watchdogFires.remove(guidObj);
                if (badgeOwnedBy(chatActivity)) {
                    if (cascadeCount.containsKey(guidObj)) {
                        pauseCascadeBadge(chatActivity, guidObj);
                    } else {
                        removeCascadeBadge();
                    }
                }
                noteSeenAnchor(guidObj, originalArr);
                if (config.isDebugLog()) {
                    module.log(Log.INFO, TAG, "Cascade paused: healthy batch "
                            + realRows + " rows (dialog=" + notifArgs[0] + ")");
                }
                return;
            }
            if (isEnd) { // 历史已到底，确实没有达标消息
                // 解除单飞并停用看门狗：已排定的 watchdog 读到 cur==null 自然退出。
                // 否则终点后仍会对同一锚点徒劳重发直至预算耗尽（审计 v29-A1）
                cascadeInFlightAt.remove(guidObj);
                watchdogFires.remove(guidObj);
                if (badgeOwnedBy(chatActivity)) removeCascadeBadge();
                notifyTerminalOnce(guidObj, chatActivity, module, notifArgs,
                        "已筛选至频道开头，未发现达标消息");
                return;
            }

            int batchMin = oldestPositiveMessageId(originalArr);
            if (batchMin <= 0) return;

            int guid = guidObj;
            // 先读旧值判推进，再原子 min-merge（主线程单写者，读-并窗口无害）
            Integer prev = oldestSeenAnchor.get(guid);
            int frontier = oldestSeenAnchor.merge(guid, batchMin, Math::min);
            if (prev != null && frontier >= prev) {
                // 未推进：TG 空视图重取的最新窗口 / 重复请求的回包。
                // 直接忽略——不发起、不计数（v28 的 stuck 螺旋死因，
                // 丢包恢复由看门狗负责，不靠这里的计数）。
                return;
            }

            // 前沿推进 = 上一个在途请求已落地，解除单飞标记；
            // 通道存活证明 → 看门狗预算滑动续期，慢链环不累计烧预算（审计 v29-A3）
            cascadeInFlightAt.remove(guid);
            watchdogFires.remove(guid);
            int n = cascadeCount.merge(guid, 1, Integer::sum);
            if (cascadeCount.size() > 200) cascadeCount.clear(); // 防泄漏（guid 生命周期=实例）
            if (oldestSeenAnchor.size() > 200) oldestSeenAnchor.clear();

            // 检索深度：频道启用表情规则且设了深度 → 该频道一切级联都按它
            // （含关键词过滤触发的级联，"这个频道挖多深"是频道级属性）；
            // 否则用全局默认（App 设置页 reactions_search_depth）
            long dialogId = (Long) notifArgs[0];
            ReactionsRule depthRule = engine.getActiveRule(dialogId);
            int depth = depthRule != null && depthRule.maxDepth > 0
                    ? depthRule.maxDepth : config.getReactionsSearchDepth();
            int maxBatches = Math.max(1, (depth + CASCADE_BATCH_SIZE - 1) / CASCADE_BATCH_SIZE);
            if (n > maxBatches) {
                if (badgeOwnedBy(chatActivity)) removeCascadeBadge();
                notifyTerminalOnce(guidObj, chatActivity, module, notifArgs,
                        "已自动筛选约" + ReactionsRule.formatDepth(depth)
                                + "条历史仍未发现足够达标消息，已停止自动加载"
                                + "（深度可在频道内 ⚡表情过滤 或 TGClean App 设置中调大）");
                return;
            }
            if (n % 10 == 0) {
                module.log(Log.INFO, TAG, "Cascade progress #" + n + "/" + maxBatches
                        + " (dialog=" + dialogId + ", frontier=" + frontier + ")");
            }
            // 达标行累计 + 进度徽标：让用户看见"还在搜、搜到哪了"。
            // 后台实例（另一频道持有徽标身份）只累计数据不刷前台徽标
            int found = cascadeFound.merge(guid, realRows, Integer::sum);
            if (cascadeFound.size() > 200) cascadeFound.clear(); // 防泄漏
            if (badgeOwnedBy(chatActivity)) {
                updateCascadeBadge(chatActivity, "🔍 TGClean 检索中 · 已筛约 "
                        + ReactionsRule.formatDepth(n * CASCADE_BATCH_SIZE) + "/"
                        + ReactionsRule.formatDepth(maxBatches * CASCADE_BATCH_SIZE)
                        + (found > 0 ? " · 达标 " + found + " 条" : ""), true);
            }
            // 推进且即将开火才算"现任"（terminal 链不占用身份）；压栈旧实例
            // 的看门狗据此软让位（审计 v2.0.2 B-1：写在这里避免额度耗尽的
            // 实例抢占现任、误停前台链的看门狗）
            cascadeActiveActivity = new java.lang.ref.WeakReference<>(chatActivity);
            fireCascade(chatActivity, module, dialogId, (Integer) notifArgs[14],
                    guid, frontier, n, realRows);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "maybeCascade error: " + t.getMessage());
        }
    }

    /** 发起一次级联加载（单飞：putIfAbsent 时间戳，任一时刻至多一个在途请求） */
    private static void fireCascade(Object chatActivity, XposedModule module,
                                    long dialogId, int mode, int guid,
                                    int anchor, int seq, int realRows) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cascadeInFlightAt.putIfAbsent(guid, now) != null) return;
        module.log(Log.INFO, TAG, "Cascade #" + seq + ": only " + realRows
                + " real rows survived, auto-loading " + CASCADE_BATCH_SIZE
                + " older messages (dialog=" + dialogId + ", older<" + anchor + ")");
        // post 到主线程末尾执行，避免在 NotificationCenter 派发循环内重入；
        // 确定性失败（反射签名失效等）由调用方清链，不烧看门狗预算（性能审计 P2-6）
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int anc = anchor;
        h.post(() -> {
            if (!triggerCascadeLoad(chatActivity, module, dialogId, anc, mode)) {
                clearCascadeState(guid);
            }
        });
        h.postDelayed(() -> cascadeWatchdog(chatActivity, module, dialogId, mode, guid, now),
                CASCADE_STALE_MS + 250);
    }

    /**
     * 看门狗：CASCADE_STALE_MS 内没有推进响应则判定在途请求丢失，重发一次。
     * 这是唯一的丢包恢复路径（替代 v28 的 stuck 计数——那个会把正常的
     * 重复回包/TG 重取批次误判为病态循环并烧断通道）。
     */
    private static void cascadeWatchdog(Object chatActivity, XposedModule module,
                                        long dialogId, int mode, int guid, long myTs) {
        try {
            Long cur = cascadeInFlightAt.get(guid);
            if (cur == null || cur != myTs) return; // 已被推进响应解除/被新一代取代
            if (!isFragmentAlive(chatActivity)) {
                // 实例已销毁（onFragmentDestroy 钩子缺失/失效时的兜底）：
                // 观察者已注销，响应永远无法推进锚点，立即终止僵尸链
                clearCascadeState(guid);
                module.log(Log.INFO, TAG, "Cascade chain dropped (fragment detached)"
                        + " dialog=" + dialogId);
                return;
            }
            Integer curGuid = getIntFieldValue(chatActivity.getClass(), chatActivity, "classGuid");
            if (curGuid == null || curGuid != guid) {
                // 话题切换/resetForReload 会在存活实例上重新生成 classGuid：
                // 旧 guid 的回包被实例 guid 门拒收（永不推进），按孤儿链清理
                clearCascadeState(guid);
                module.log(Log.INFO, TAG, "Cascade chain dropped (guid regenerated)"
                        + " dialog=" + dialogId);
                return;
            }
            Object active = cascadeActiveActivity == null ? null : cascadeActiveActivity.get();
            if (active != null && active != chatActivity) {
                // 另一 ChatActivity 实例已开火级联（用户切到别的频道）：本实例已被
                // 压栈，其请求大概率不再得到推进响应（实测僵尸链形态）。软让位：
                // 只解除单飞+停看门狗（不再 re-post 即停摆），额度/锚点/达标/终点
                // 全保留——返回本频道后下一个推进批次无损续链。硬清会让快速
                // A→B→A 往返复现"进度归零从头再扫"（审计 v2.0.2 B-1）；真正的
                // 僵尸（永无推进）由预算 8 次封顶自灭，治理目标不受损。
                cascadeInFlightAt.remove(guid);
                watchdogFires.remove(guid);
                module.log(Log.INFO, TAG, "Cascade chain yielded (superseded by"
                        + " foreground chat) dialog=" + dialogId + ", state preserved");
                return;
            }
            Integer anchorObj = oldestSeenAnchor.get(guid);
            if (anchorObj == null || anchorObj <= 0) {
                // 锚点只会在防泄漏 clear 中被清空（健康重置不清锚点，但会清
                // inFlightAt 使 watchdog 在上一道时间戳检查就退出）——此处链路
                // 降级为纯响应驱动，不再续链
                cascadeInFlightAt.remove(guid);
                return;
            }
            int wd = watchdogFires.merge(guid, 1, Integer::sum);
            if (wd > WATCHDOG_MAX_REFIRES) {
                cascadeInFlightAt.remove(guid);
                if (badgeOwnedBy(chatActivity)) removeCascadeBadge();
                module.log(Log.WARN, TAG, "Cascade watchdog gave up (no descending response"
                        + " for " + (WATCHDOG_MAX_REFIRES * CASCADE_STALE_MS / 1000)
                        + "s) dialog=" + dialogId);
                // 弱网终点也须告知用户（v2.0.2 前这里只撤徽标静默收场，
                // 用户无从分辨"检索完了"还是"断了"）；terminalNotified 去重
                if (terminalNotified.add(guid)) {
                    Integer n = cascadeCount.get(guid);
                    Integer found = cascadeFound.get(guid);
                    String summary = (n != null
                            ? "已筛约 " + ReactionsRule.formatDepth(n * CASCADE_BATCH_SIZE) : "")
                            + (found != null && found > 0 ? " · 达标 " + found + " 条" : "");
                    toastFromFragment(chatActivity, "TGClean：网络较慢，已暂停自动深挖"
                            + (summary.isEmpty() ? "" : "（" + summary + "）")
                            + "，网络恢复后重进频道可继续");
                }
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            // 条件替换：仅当仍是本代时间戳时续期，避免覆写并发新代（审计 v29-A2）
            if (!cascadeInFlightAt.replace(guid, myTs, now)) return;
            module.log(Log.INFO, TAG, "Cascade watchdog refire #" + wd
                    + " (dialog=" + dialogId + ", older<" + anchorObj + ")");
            updateCascadeBadge(chatActivity, "🔍 TGClean 检索中 · 网络较慢，第 "
                    + wd + " 次重试…", true);
            final int anc = anchorObj;
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> {
                if (!triggerCascadeLoad(chatActivity, module, dialogId, anc, mode)) {
                    clearCascadeState(guid);
                }
            });
            h.postDelayed(() -> cascadeWatchdog(chatActivity, module, dialogId, mode, guid, now),
                    CASCADE_STALE_MS + 250);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Cascade watchdog error: " + t.getMessage());
        }
    }

    /** 终点提示（到底/额度耗尽）：徽标撤下 + 日志 + Toast 各一次，用户不再面对沉默的"暂无消息"。
     *  Toast 不设归属门（后台链到终点也应告知）；徽标撤下设门防误撤前台徽标。 */
    private static void notifyTerminalOnce(Integer guid, Object chatActivity,
                                           XposedModule module, Object[] notifArgs,
                                           String message) {
        if (badgeOwnedBy(chatActivity)) removeCascadeBadge();
        if (!terminalNotified.add(guid)) return;
        module.log(Log.WARN, TAG, "Cascade terminal (dialog=" + notifArgs[0] + "): " + message);
        toastFromFragment(chatActivity, "TGClean：" + message);
    }

    /** 经 BaseFragment.getContext() 取上下文，主线程 Toast（不依赖回调线程） */
    private static void toastFromFragment(Object chatActivity, String message) {
        try {
            if (fGetContext == null) {
                fGetContext = chatActivity.getClass().getMethod("getContext");
            }
            Object ctx = fGetContext.invoke(chatActivity);
            if (ctx instanceof android.content.Context) {
                final android.content.Context c = (android.content.Context) ctx;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        android.widget.Toast.makeText(c, message,
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ChatActivity.onFragmentDestroy → 清理本实例全部级联状态。
     *
     * v29 教训（v29 实测日志确证）：看门狗链持有 Handler 自续引用，实例销毁
     * 后 NotificationCenter 观察者已注销、响应永远无法推进锚点，链条却仍每
     * CASCADE_STALE_MS 重发 loadMessages——同频道积了 10+ 条僵尸链并发空转
     * 最长 170s（80 次 load / 21 次真实推进），DB/网络请求风暴拖慢后续真实
     * 级联，是"有时能搜到有时不能"的直接诱因。
     */
    private static void hookFragmentDestroy(ClassLoader cl, XposedModule module) {
        try {
            Class<?> caClass = cl.loadClass("org.telegram.ui.ChatActivity");
            Method target = caClass.getDeclaredMethod("onFragmentDestroy");
            target.setAccessible(true);
            module.hook(target).intercept(chain -> {
                chain.proceed();
                try {
                    Object chatActivity = chain.getThisObject();
                    Integer guid = getIntFieldValue(
                            chatActivity.getClass(), chatActivity, "classGuid");
                    if (guid != null && guid != 0) {
                        clearCascadeState(guid);
                        restoreTgProgress(); // 压制解除兜底（removeCascadeBadge 主路径之外）
                    }
                } catch (Throwable ignored) {}
                return null;
            });
            module.log(Log.INFO, TAG, "=== Hooked onFragmentDestroy (cascade cleanup) ===");
        } catch (Throwable t) {
            // 非致命：看门狗的 getParentActivity 兜底检测仍能终止僵尸链
            module.log(Log.WARN, TAG,
                    "onFragmentDestroy hook failed: " + t.getMessage());
        }
    }

    /** BaseFragment.getParentActivity() 为 null 即实例已脱离/销毁（TG 自身的存活判定惯例） */
    private static boolean isFragmentAlive(Object chatActivity) {
        try {
            if (fGetParentActivity == null) {
                fGetParentActivity = chatActivity.getClass().getMethod("getParentActivity");
            }
            return fGetParentActivity.invoke(chatActivity) != null;
        } catch (Throwable t) {
            return true; // 反射失败按存活处理：宁可不清理也不误杀活链
        }
    }

    /** 清掉某实例的全部级联状态（销毁/僵尸/孤儿链共用） */
    private static void clearCascadeState(Integer guid) {
        cascadeCount.remove(guid);
        cascadeInFlightAt.remove(guid);
        oldestSeenAnchor.remove(guid);
        watchdogFires.remove(guid);
        terminalNotified.remove(guid);
        cascadeFound.remove(guid);
    }

    // ═══════════════════════════════════════════════════
    // 检索进度徽标：悬浮在聊天页底部居中（framework 控件，仅主线程触摸）
    //
    // 背景：高阈值深挖时页面长期"暂无消息"，用户无从分辨是卡住了
    // 还是在后台检索（v29 用户反馈）。徽标实时显示检索进度/达标数/
    // 网络重试状态，恢复正常（暂停汇总）或到达终点（Toast 已另行
    // 告知）即撤下；10s 无更新自动消失兜底（防销毁钩子失效后残留）。
    // 位置：底部居中——顶部易与 TG 标题/菜单区视觉重叠，且用户视线
    // 停留在消息区中部偏下；不拦截触摸（非 clickable）。
    // 注：TG 所有 ChatActivity 共宿主一个 LaunchActivity，徽标挂在
    // android.R.id.content 上天然全局唯一，前台频道即语义归属者
    //（badgeOwnedBy 守卫：后台实例不得动前台徽标）。
    // ═══════════════════════════════════════════════════

    private static android.widget.TextView cascadeBadge;
    private static android.view.ViewGroup badgeHost;
    private static Runnable badgeAutoHide;

    /** ChatActivity.progressView（TG 空视图加载时的全屏转圈层，用户反馈方向误导） */
    private static volatile Field fProgressView;
    /** 压制过 progressView 的实例（弱引用：恢复时不阻止 GC） */
    private static java.lang.ref.WeakReference<Object> cascadeProgressOwner;

    /**
     * 级联检索期间压制 TG 自身的加载圈：历史消息从顶部方向进入，底部转圈
     * 会让用户误以为在等新消息。进度反馈统一由顶部徽标承担。
     */
    private static void suppressTgProgress(Object chatActivity) {
        try {
            if (fProgressView == null) {
                Field f = findFieldInHierarchy(chatActivity.getClass(), "progressView");
                if (f == null) return;
                f.setAccessible(true);
                fProgressView = f;
            }
            Object pv = fProgressView.get(chatActivity);
            if (pv instanceof android.view.View) {
                ((android.view.View) pv).setVisibility(android.view.View.GONE);
            }
            cascadeProgressOwner = new java.lang.ref.WeakReference<>(chatActivity);
        } catch (Throwable ignored) {}
    }

    /** 恢复 TG 加载圈到构造默认态（INVISIBLE；TG 需要时会自行置 VISIBLE） */
    private static void restoreTgProgress() {
        java.lang.ref.WeakReference<Object> ref = cascadeProgressOwner;
        cascadeProgressOwner = null;
        if (ref == null) return;
        Object ca = ref.get();
        if (ca == null || fProgressView == null) return;
        try {
            Object pv = fProgressView.get(ca);
            if (pv instanceof android.view.View) {
                ((android.view.View) pv).setVisibility(android.view.View.INVISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 徽标是否归属当前实例（无主或本实例持有）。
     * 多实例并存时（A→B 切频道）只有前台实例可刷/撤徽标，
     * 后台实例的健康批次/终点不得误动前台徽标。
     */
    private static boolean badgeOwnedBy(Object chatActivity) {
        Object active = cascadeActiveActivity == null ? null : cascadeActiveActivity.get();
        return active == null || active == chatActivity;
    }

    /** 链休眠（健康批次到达）时的进度汇总徽标：让用户知道已筛到哪、达标多少 */
    private static void pauseCascadeBadge(Object chatActivity, int guid) {
        Integer n = cascadeCount.get(guid);
        if (n == null) return;
        Integer found = cascadeFound.get(guid);
        updateCascadeBadge(chatActivity, "✓ TGClean · 已筛约 "
                + ReactionsRule.formatDepth(n * CASCADE_BATCH_SIZE)
                + (found != null && found > 0 ? " · 达标 " + found + " 条" : "")
                + " · 内容已够，暂停深挖", true);
    }

    private static void updateCascadeBadge(Object chatActivity, String text, boolean autoHide) {
        try {
            if (fGetContext == null) {
                fGetContext = chatActivity.getClass().getMethod("getContext");
            }
            Object ctxObj = fGetContext.invoke(chatActivity);
            if (!(ctxObj instanceof android.app.Activity)) return;
            android.view.ViewGroup content =
                    ((android.app.Activity) ctxObj).findViewById(android.R.id.content);
            if (content == null) return;
            suppressTgProgress(chatActivity);

            if (cascadeBadge == null || badgeHost != content) {
                removeCascadeBadge();
                android.widget.TextView badge = new android.widget.TextView(content.getContext());
                badge.setTextSize(13);
                badge.setTextColor(0xFFFFFFFF);
                float density = content.getResources().getDisplayMetrics().density;
                int padH = (int) (12 * density + 0.5f);
                int padV = (int) (6 * density + 0.5f);
                badge.setPadding(padH, padV, padH, padV);
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xCC2B2B2B); // 半透明深色，明暗主题下均可读
                bg.setCornerRadius(16 * density);
                badge.setBackground(bg);
                // 底部居中：顶部与 TG 标题/菜单区重叠且远离视线落点；
                // 徽标非 clickable，不拦截消息区触摸
                android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
                lp.bottomMargin = (int) (80 * density + 0.5f);
                content.addView(badge, lp);
                cascadeBadge = badge;
                badgeHost = content;
            }
            // 同文本不重绘（重复批次/快速回包下的高频同文案更新会引发
            // 无谓的布局与视觉抖动），但自动隐藏计时照常续期
            if (!text.contentEquals(cascadeBadge.getText())) {
                cascadeBadge.setText(text);
            }
            cascadeBadge.setVisibility(android.view.View.VISIBLE);
            if (badgeAutoHide != null && badgeHost != null) {
                badgeHost.removeCallbacks(badgeAutoHide);
            }
            if (autoHide) {
                badgeAutoHide = KeywordFilterHook::removeCascadeBadge;
                badgeHost.postDelayed(badgeAutoHide, 10_000);
            }
        } catch (Throwable ignored) {}
    }

    private static void removeCascadeBadge() {
        // 徽标撤下 = 检索结束/中断，同步恢复 TG 自身加载圈的默认态
        restoreTgProgress();
        try {
            // 先撤销已排定的自动隐藏，防止陈旧 runnable 误撤后续新徽标（审计 v30-M2）
            if (badgeHost != null && badgeAutoHide != null) {
                badgeHost.removeCallbacks(badgeAutoHide);
            }
            if (cascadeBadge != null) {
                android.view.ViewParent p = cascadeBadge.getParent();
                if (p instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) p).removeView(cascadeBadge);
                }
                cascadeBadge = null;
            }
        } catch (Throwable ignored) {}
        badgeHost = null;
        badgeAutoHide = null;
    }

    /**
     * 反射调用 MessagesController.loadMessages 请求更早历史。
     * 参数镜像 ChatActivity 自身的滚动加载调用（load_type=0 向更早方向）。
     *
     * @return true=已成功发起请求；false=确定性失败（反射签名失效等），
     *         调用方应立即清链而不是烧看门狗预算重试 40 次（性能审计 P2-6）
     */
    private static boolean triggerCascadeLoad(Object chatActivity, XposedModule module,
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
            if (loadIndexField == null) return false;
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
            return true;
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Cascade load failed: " + t.getMessage());
            return false;
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
                                               KeywordEngine engine, FilterConfig config) {
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
                        ArrayList<Object> kept = filterBatch(arr, engine, module, config.isDebugLog());
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
                                                 XposedModule module, boolean debugLog) {
        ArrayList<Object> kept = new ArrayList<>(arr.size());
        boolean enabled = engine.isEnabled();
        int dropped = 0;
        for (Object obj : arr) {
            if (obj == null || !enabled || !shouldHide(obj, engine, module, debugLog)) {
                kept.add(obj);
            } else {
                dropped++;
                // 逐条明细含用户消息内容，仅调试日志开启时输出（发布前审计 M-3）
                if (debugLog) logFiltered(module, obj);
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
    /** 预算耗尽后短路整个 debug 块，避免每条消息仍做原子递减（性能审计 P2-3a） */
    private static volatile boolean rxDebugDone;

    /** 评估单条消息是否应隐藏（评估失败一律放行） */
    private static boolean shouldHide(Object messageObject, KeywordEngine engine,
                                      XposedModule module, boolean debugLog) {
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

            // 表情规则激活时输出调试明细（限量），用于定位计数读取/表情匹配问题；
            // 仅调试日志开启时（发布版默认关，不暴露用户内容）
            if (debugLog && !rxDebugDone) {
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
                    } else {
                        rxDebugDone = true;
                        module.log(Log.INFO, TAG,
                                "RX-DEBUG budget exhausted, further detail suppressed");
                    }
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
