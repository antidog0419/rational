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

# ===== R8 压缩（2026-09-07 开启）=====
# slf4j 无绑定实现（ktor 引用但运行用不到）→ 静默缺失类
-dontwarn org.slf4j.impl.**
-dontwarn org.slf4j.**
# 无障碍服务/识屏服务/序列化场景模型/本地数据层防误裁（反射/字符串引用多）
-keep class com.example.finance.service.** { *; }
-keep class com.example.finance.capture.** { *; }
-keep class com.example.finance.scene.** { *; }
-keep class com.example.finance.data.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod