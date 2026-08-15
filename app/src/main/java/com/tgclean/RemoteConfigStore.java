package com.tgclean;

import android.util.Log;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.github.libxposed.service.XposedService;

/**
 * App 端 remote prefs 写操作队列。
 *
 * 使用场景：Telegram 进程经显式广播触发的配置写入（如表情过滤规则），
 * 到达时 XposedService binder 可能尚未就绪（进程刚被广播拉起）。
 * 此队列将操作暂存，待 App.onServiceBind 后统一执行。
 *
 * 注意：本类仅在 TGClean App 进程使用；hook 端 remote prefs 只读。
 */
public final class RemoteConfigStore {

    private static final String TAG = "TGClean-ConfigStore";

    public interface Op {
        void run(XposedService service) throws Exception;
    }

    private static final Queue<Op> pendingOps = new ConcurrentLinkedQueue<>();

    private RemoteConfigStore() {}

    /** 提交写操作：服务就绪立即执行，否则入队等待 */
    public static void submit(Op op) {
        XposedService svc = App.getService();
        if (svc != null) {
            try {
                op.run(svc);
                return;
            } catch (Throwable t) {
                Log.e(TAG, "Op failed, re-queued: " + t.getMessage());
            }
        }
        pendingOps.add(op);
    }

    /** 服务就绪时由 App.onServiceBind 调用，清空积压操作；失败项重新入队 */
    public static void flush(XposedService svc) {
        int executed = 0;
        Op op;
        while ((op = pendingOps.poll()) != null) {
            try {
                op.run(svc);
                executed++;
            } catch (Throwable t) {
                Log.e(TAG, "Flush op failed: " + t.getMessage());
                pendingOps.add(op);
            }
        }
        if (executed > 0) {
            Log.i(TAG, "Flushed " + executed + " pending config ops");
        }
    }
}
