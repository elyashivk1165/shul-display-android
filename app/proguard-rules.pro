# Default ProGuard rules for Shul Display
-keepattributes Signature,EnclosingMethod,InnerClasses
-keepattributes *Annotation*

# Keep app classes that are accessed via reflection or manifest
-keep class app.shul.display.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin Coroutines
-keepclassmembernames class kotlinx.** { *; }
-dontwarn kotlinx.coroutines.**

# JSON parsing
-keepclassmembers class org.json.** { *; }

# AndroidX Work
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Accessibility Service
-keep class * extends android.accessibilityservice.AccessibilityService

# Device Admin Receiver
-keep class * extends android.app.admin.DeviceAdminReceiver

# Google Tink (used by EncryptedSharedPreferences)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
