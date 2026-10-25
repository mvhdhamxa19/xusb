# Keep Android components (referenced by name in manifest)
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity
-keep public class * extends android.accessibilityservice.AccessibilityService

# Keep TAG used in logging
-keepclassmembers class com.device.health.monitor.SyncService {
    static final java.lang.String TAG;
}

# Suppress warnings
-dontwarn **
-ignorewarnings
