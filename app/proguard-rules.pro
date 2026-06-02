# TGClean ProGuard Rules

# 保留Xposed入口类
-keep class com.tgclean.ModuleMain { *; }

# 保留Hook类（反射调用）
-keep class com.tgclean.hooks.** { *; }

# 保留设置界面
-keep class com.tgclean.ui.** { *; }

# 保留Telegram内部类引用（反射访问）
-keep class org.telegram.** { *; }

# 保留配置类（Xposed跨进程反射访问）
-keep class com.tgclean.config.** { *; }
-keep class com.tgclean.filter.** { *; }
-keep class com.tgclean.hooks.** { *; }

# Material / AppCompat 保留
-dontwarn com.google.android.material.**
-dontwarn androidx.appcompat.**
-dontwarn androidx.activity.**
