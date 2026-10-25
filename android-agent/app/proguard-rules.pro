# Keep Android components (referenced by name in manifest)
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity
-keep public class * extends android.accessibilityservice.AccessibilityService

# Keep static field accessed cross-class
-keepclassmembers class com.device.health.monitor.SyncService {
    static volatile byte[] pendingShot;
    static final java.lang.String TAG;
}

# Keep getInstance() used by cross-class references
-keepclassmembers class com.device.health.monitor.MonitorService {
    public static com.device.health.monitor.MonitorService getInstance();
}
-keepclassmembers class com.device.health.monitor.SyncService {
    public static com.device.health.monitor.SyncService getInstance();
}

# Suppress warnings
-dontwarn **
-ignorewarnings
