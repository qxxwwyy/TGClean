package com.tgclean;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.service.XposedService;

/**
 * App 端 remote prefs 写操作队列。
 *
 * 使用场景：Telegram 进程经显式广播触发的配置写入（如表情过滤规则），
 * 到达时 XposedService binder 可能尚未就绪（进程刚被广播拉起）。
 * 此队列将操作暂存，待 App.onServiceBind 后统一执行。
 *
 * 自愈重试：执行失败（如 binder 半死状态抛异常）时重新入队并延时重试，
 * 重试预算 5 次（每次成功后重置），避免单次瞬时故障造成写入永久丢失。
 *
 * 注意：本类仅在 TGClean App 进程使用；hook 端 remote prefs 只读。
 */
public final class RemoteConfigStore {

    private static final String TAG = "TGClean-ConfigStore";
    private static final long RETRY_DELAY_MS = 1500;
    private static final int RETRY_BUDGET = 5;

    public interface Op {
        void run(XposedService service) throws Exception;
    }

    private static final Queue<Op> pendingOps = new ConcurrentLinkedQueue<>();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicInteger retryBudget = new AtomicInteger(RETRY_BUDGET);

    private RemoteConfigStore() {}

    /** 提交写操作：服务就绪立即执行，失败或未就绪则入队等待 */
    public static void submit(Op op) {
        XposedService svc = App.getService();
        if (svc != null && tryRun(op, svc)) {
            return;
        }
        pendingOps.add(op);
        scheduleRetry();
    }

    /** 服务就绪时由 App.onServiceBind 调用，清空积压操作；失败项重新入队 */
    public static void flush(XposedService svc) {
        // 单趟排水：先取尽再执行，失败项入队后交给延时重试。
        // ⚠️ 不能在 poll 循环里失败即 add 回同一队列——binder 半死时
        // poll→add→poll 同一项无限循环，挂死调用线程（App 主线程 ANR，
        // 发布前审计 P0-1，等价结构实验证实百万次不退出）
        List<Op> drained = new ArrayList<>();
        Op op;
        while ((op = pendingOps.poll()) != null) {
            drained.add(op);
        }
        int executed = 0;
        for (Op o : drained) {
            if (tryRun(o, svc)) {
                executed++;
            } else {
                pendingOps.add(o);
            }
        }
        if (executed > 0) {
            Log.i(TAG, "Flushed " + executed + " pending config ops");
        }
        if (!pendingOps.isEmpty()) {
            scheduleRetry();
        }
    }

    private static boolean tryRun(Op op, XposedService svc) {
        try {
            op.run(svc);
            retryBudget.set(RETRY_BUDGET);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Op failed, re-queued: " + t.getMessage());
            return false;
        }
    }

    private static void scheduleRetry() {
        if (retryBudget.getAndDecrement() <= 0) {
            Log.e(TAG, "Retry budget exhausted, " + pendingOps.size() + " ops pending");
            return;
        }
        HANDLER.postDelayed(() -> {
            XposedService svc = App.getService();
            if (svc != null) {
                flush(svc);
            } else {
                scheduleRetry();
            }
        }, RETRY_DELAY_MS);
    }
}
