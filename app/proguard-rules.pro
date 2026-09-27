# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Gson 核心规则（必须保留泛型签名与注解，防止 List<T> 反序列化时泛型被擦除）
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# 保护所有模型类与备份数据结构，防止 GSON 反序列化时字段与类名被混淆
-keep class com.moting.linkgo.model.** { *; }
-keep enum com.moting.linkgo.model.** { *; }
-keep class com.moting.linkgo.data.backup.** { *; }
-keep class com.moting.linkgo.data.WindowConfig { *; }
-keep class com.moting.linkgo.data.CapsuleAnchor { *; }

# Shizuku: 保护 UserService（反射创建实例）
-keep class com.moting.linkgo.service.ClipboardUserService { *; }
-keep class com.moting.linkgo.service.ClipboardUserService$* { *; }

# 保护透明提权 Activity
-keep class com.moting.linkgo.ClipboardFocusActivity { *; }

# 保护 AIDL 生成的接口（跨进程 Binder 通信）
-keep class com.moting.linkgo.IClipboardMonitor { *; }
-keep class com.moting.linkgo.IClipboardMonitor$* { *; }
-keep class com.moting.linkgo.IClipboardCallback { *; }
-keep class com.moting.linkgo.IClipboardCallback$* { *; }

# Shizuku API 自身
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Shizuku newProcess: 子进程按类名启动，混淆后必须保留 main() 入口
-keep class com.moting.linkgo.util.XmsfFirewallCommand { *; }

# LSPosed：保护 hook 入口与广播接收器
# 入口类由 META-INF/xposed/java_init.list 按类名加载，混淆后必须保留
-keep class com.moting.linkgo.hook.HookEntry { *; }
-keep class com.moting.linkgo.hook.HookLog { *; }
-keep class com.moting.linkgo.clipboard.ClipboardTextReceiver { *; }
-keep class com.moting.linkgo.clipboard.ClipboardHookContract { *; }

# 应用内链接捕获：广播接收器由 Manifest 与 system_server 按类名引用，契约常量由 HookEntry 引用，均不可混淆
-keep class com.moting.linkgo.applink.LinkIntentReceiver { *; }
-keep class com.moting.linkgo.applink.AppLinkHookContract { *; }

# libxposed 自身（service/binder 集成与 provider 元数据，防 R8 混淆破坏模块识别）
-keep class io.github.libxposed.** { *; }

# 超级岛与系统 IPC 代理保护（防 R8 混淆破坏反射与 Binder 封装）
-keep class com.moting.linkgo.util.AppShell { *; }
-keep class com.moting.linkgo.util.HyperIslandHelper { *; }
-keep interface android.net.IConnectivityManager { *; }
-keep class android.net.IConnectivityManager$* { *; }
-keep interface android.content.pm.IPackageManager { *; }
-keep class android.content.pm.IPackageManager$* { *; }