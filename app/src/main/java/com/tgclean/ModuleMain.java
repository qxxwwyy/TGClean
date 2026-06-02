package com.tgclean;

import android.util.Log;

import androidx.annotation.NonNull;

import com.tgclean.config.FilterConfig;
import com.tgclean.hooks.ChatHelperHook;
import com.tgclean.hooks.KeywordFilterHook;
import com.tgclean.hooks.SponsoredMessageHook;

import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    public static final String TAG = "TGClean";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "====================================");
        log(Log.INFO, TAG, "TGClean Module Loaded");
        log(Log.INFO, TAG, "Process: " + param.getProcessName());
        log(Log.INFO, TAG, String.format("Framework: %s (%s) API %d",
                getFrameworkName(), getFrameworkVersionCode(), getApiVersion()));
        log(Log.INFO, TAG, "====================================");
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!"org.telegram.messenger".equals(param.getPackageName())) {
            log(Log.DEBUG, TAG, "Skipping non-Telegram package: " + param.getPackageName());
            return;
        }
        if (!param.isFirstPackage()) return;

        log(Log.INFO, TAG, "====================================");
        log(Log.INFO, TAG, "Hooking package: " + param.getPackageName());
        log(Log.INFO, TAG, "====================================");

        try {
            ClassLoader cl = param.getClassLoader();

            // 初始化配置
            log(Log.INFO, TAG, "[1/4] Initializing FilterConfig...");
            FilterConfig config = new FilterConfig(this);
            log(Log.INFO, TAG, "[1/4] FilterConfig OK. Enabled=" + config.isEnabled());

            // Phase 1: 移除原生赞助消息
            log(Log.INFO, TAG, "[2/4] Hooking SponsoredMessageHook...");
            SponsoredMessageHook.hook(cl, this);
            log(Log.INFO, TAG, "[2/4] SponsoredMessageHook done.");

            // Phase 2: 关键词过滤（构造函数标记 + Adapter清理双阶段方案）
            log(Log.INFO, TAG, "[3/4] Hooking KeywordFilterHook...");
            KeywordFilterHook.hook(cl, this, config);
            log(Log.INFO, TAG, "[3/4] KeywordFilterHook done.");

            // Phase 3: 聊天辅助（复制聊天ID）
            log(Log.INFO, TAG, "[4/4] Hooking ChatHelperHook...");
            ChatHelperHook.hook(cl, this);
            log(Log.INFO, TAG, "[4/4] ChatHelperHook done.");

            log(Log.INFO, TAG, "====================================");
            log(Log.INFO, TAG, "All hooks initialized successfully!");
            log(Log.INFO, TAG, "====================================");

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "====================================");
            log(Log.ERROR, TAG, "HOOK INITIALIZATION FAILED!", t);
            log(Log.ERROR, TAG, "====================================");
        }
    }
}
