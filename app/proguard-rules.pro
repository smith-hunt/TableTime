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

# 1. 保护你的数据类（防止课程名、教室等字段被混淆导致解析失败）
-keep class com.example.mytabletime.** { *; }

# 2. 保护 androidx 安全加密库（防止 EncryptedSharedPreferences 报错）
-keep class androidx.security.crypto.** { *; }

# 3. 保护反射和注解（如果后续涉及 JSON 数据解析）
-keepattributes Signature, *Annotation*