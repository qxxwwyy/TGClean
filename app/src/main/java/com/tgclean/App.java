package com.tgclean;

import android.app.Application;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Application 入口，注册 XposedService 通信
 *
 * libxposed 的 RemotePreferences 通过 XposedService Binder 通道
 * 将配置存储在 LSPosed 框架数据库中（不是 APP 本地 shared_prefs/）。
 * SettingsActivity 和 Hook 进程都通过同一个 XposedService 通道读写，
 * 才能实现真正的跨进程配置共享。
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "TGClean-App";

    private static volatile XposedService service = null;
    private static final CopyOnWriteArraySet<ServiceReadyListener> listeners = new CopyOnWriteArraySet<>();

    public interface ServiceReadyListener {
        void onServiceReady(XposedService service);
    }

    /**
     * 注册一个监听器，当 XposedService 可用时回调。
     * 如果 service 已经就绪，立即回调。
     */
    public static void addServiceReadyListener(ServiceReadyListener listener) {
        listeners.add(listener);
        XposedService existing = service;
        if (existing != null) {
            listener.onServiceReady(existing);
        }
    }

    public static void removeServiceReadyListener(ServiceReadyListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取当前 XposedService 实例（可能为 null）
     */
    public static XposedService getService() {
        return service;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
        Log.i(TAG, "XposedServiceHelper registered");
    }

    @Override
    public void onServiceBind(XposedService svc) {
        service = svc;
        Log.i(TAG, "XposedService bound");
        for (var listener : listeners) {
            try {
                listener.onServiceReady(svc);
            } catch (Throwable t) {
                Log.e(TAG, "ServiceReadyListener error", t);
            }
        }
    }

    @Override
    public void onServiceDied(XposedService svc) {
        service = null;
        Log.w(TAG, "XposedService died");
    }
}
