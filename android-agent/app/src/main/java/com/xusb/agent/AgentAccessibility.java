package com.xusb.agent;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;

public class AgentAccessibility extends AccessibilityService {

    private static volatile AgentAccessibility instance;
    static final String TAG = "XAgent";

    public static AgentAccessibility getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.d(TAG, "Accessibility connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // Called by AgentService to request a screenshot asynchronously
    void requestShot() {
        if (Build.VERSION.SDK_INT < 30) return;  // API 30 = Android 11
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), null);
                            if (bmp != null) {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                bmp.compress(Bitmap.CompressFormat.JPEG, 40, baos);
                                AgentService.pendingShot = baos.toByteArray();
                                bmp.recycle();
                            }
                            result.getHardwareBuffer().close();
                        } catch (Exception e) {
                            Log.d(TAG, "shot encode: " + e.getMessage());
                        }
                    }
                    @Override
                    public void onFailure(int errorCode) {
                        Log.d(TAG, "shot fail: " + errorCode);
                    }
                });
        } catch (Exception e) {
            Log.d(TAG, "shot: " + e.getMessage());
        }
    }
}
