# TGClean ProGuard Rules
#
# 说明：
# - manifest 组件（App/Activity/Receiver）由 aapt 自动保留
# - java_init.list 指向的入口类与反射调用的自有类需显式保留
# - Telegram 宿主类不在本 APK 内（hook 按名反射发生在宿主进程），无需 keep

# Xposed 入口（META-INF/xposed/java_init.list 按类名加载）
-keep class com.tgclean.ModuleMain { *; }

# Hook / 过滤 / 配置类（框架按名实例化或跨进程 JSON 反序列化依赖字段名）
-keep class com.tgclean.hooks.** { *; }
-keep class com.tgclean.config.** { *; }
-keep class com.tgclean.filter.** { *; }

# libxposed API 为 compileOnly 依赖，不在运行时 classpath，R8 需忽略其引用
-dontwarn io.github.libxposed.api.**

# Material / AppCompat 传递依赖中的可选引用
-dontwarn com.google.android.material.**
-dontwarn androidx.appcompat.**
-dontwarn androidx.activity.**
