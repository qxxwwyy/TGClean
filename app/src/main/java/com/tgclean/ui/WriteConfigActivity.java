package com.tgclean.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tgclean.RemoteConfigStore;
import com.tgclean.config.ReactionsRule;
import com.tgclean.receiver.ChannelReceiver;

/**
 * 规则写入的兜底通道（透明无界面，写入完成即退出）。
 *
 * 背景：MIUI 等 ROM 的自启动管理会拦截"广播拉起进程"，
 * FLAG_INCLUDE_STOPPED_PACKAGES 也无法绕过（TG 侧发送了但 App 进程
 * 根本没被拉起，日志零痕迹）。而前台应用 startActivity 不受任何
 * 自启动限制——TG 侧保存规则时广播 2.5s 无回执即透明拉起本类，
 * 由本类完成写入、回执后自行退出。
 *
 * 附带收益：activity 启动会将 App 移出 stopped 态，后续广播恢复可达。
 */
public class WriteConfigActivity extends Activity {

    private static final String TAG = "TGClean-Sync";
    private static final long FINISH_TIMEOUT_MS = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 保底退出：即使写入迟迟不落地也不滞留（透明 Activity 会遮挡触摸）
        handler.postDelayed(this::finish, FINISH_TIMEOUT_MS);

        Intent intent = getIntent();
        long dialogId = intent.getLongExtra("dialog_id", 0);

        ReactionsRule rule = new ReactionsRule();
        rule.enabled = intent.getBooleanExtra("enabled", false);
        rule.whitelistMode = intent.getBooleanExtra("whitelist", true);
        rule.emoji = intent.getStringExtra("emoji");
        rule.minCount = intent.getIntExtra("min_count", 0);
        rule.emoji2 = intent.getStringExtra("emoji2");
        rule.maxCount = intent.getIntExtra("max_count", 0);
        rule.maxDepth = intent.getIntExtra("max_depth", 0); // 0 = 跟随全局默认（审计 F-1）
        if (rule.emoji == null) rule.emoji = "";
        if (rule.emoji2 == null) rule.emoji2 = "";

        if (dialogId == 0) {
            finish();
            return;
        }

        Log.i(TAG, "Write via activity fallback: dialog=" + dialogId
                + " (" + rule.describe() + ")");

        // op 队列按序执行：写入 op 落地并回执 TG 后，收尾 op 立即关闭透明页
        ChannelReceiver.submitRuleWrite(this, dialogId, rule,
                intent.getStringExtra("token"), intent.getStringExtra("nonce"));
        RemoteConfigStore.submit(svc -> handler.post(this::finish));
    }
}
