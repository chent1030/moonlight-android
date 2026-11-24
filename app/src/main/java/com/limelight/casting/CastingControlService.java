package com.limelight.casting;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.DisplayMetrics;

import com.limelight.PcView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * M2 会话服务（CS）的 TA 端控制通道。
 *
 * - 向 CS 注册换取 tv_token，保持 WebSocket 长连接（/ws/tv）
 * - 心跳 5s；接收 show_code / start_session / stop_session / lock / notify 并广播给界面
 * - 配对自动化：PcView 生成 PIN 时调 reportPairing()，把 PIN 经 CS 路由给同房间的 PC 助手
 */
public class CastingControlService extends Service {

    private static final String TAG = "CastingControl";

    public static final String PREFS = "casting_prefs";
    public static final String KEY_URL = "cast_service_url";
    public static final String KEY_TOKEN = "cast_tv_token";
    public static final String KEY_TV_ID = "cast_tv_id";
    public static final String KEY_NAME = "cast_tv_name";
    public static final String KEY_LAST_CODE = "cast_last_code";

    public static final String ACTION_EVENT = "com.limelight.casting.EVENT";
    public static final String ACTION_STOP_GAME = "com.limelight.casting.STOP_GAME";

    private static volatile String sLastSessionId = "";

    public static String getLastSessionId() {
        return sLastSessionId;
    }

    public static void setLastSessionId(String sessionId) {
        sLastSessionId = sessionId == null ? "" : sessionId;
    }

    /** Game 生命周期上报：connecting / streaming / failed。 */
    public static void reportSessionState(String sessionId, String state) {
        CastingControlService s = sInstance;
        if (s == null || sessionId == null || sessionId.isEmpty()) {
            return;
        }
        JSONObject p = new JSONObject();
        try {
            p.put("session_id", sessionId);
            p.put("state", state);
        } catch (JSONException e) {
            return;
        }
        s.sendEnvelope("session_state", p);
        Log.i(TAG, "session_state " + state + " " + sessionId);
    }
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_PAYLOAD = "payload";

    private static final String CHANNEL_ID = "casting_control";
    private static final int NOTIFICATION_ID = 0xCA57;
    private static final long HEARTBEAT_MS = 5000;
    private static final long RECONNECT_MS = 3000;

    private static volatile CastingControlService sInstance;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile WebSocket webSocket;
    private volatile boolean stopped;
    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            sendHeartbeat();
            maybeCheckOta();
            if (!stopped) {
                handler.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    // ── 静态入口 ─────────────────────────────────────────────────────
    public static void start(Context context) {
        Intent i = new Intent(context, CastingControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CastingControlService.class));
    }

    /** 配对时调用（PcView 生成 PIN 后）：上报给 CS，由同房间的 PC 助手代填到 Sunshine。 */
    public static void reportPairing(String pcHost, String pcAddress, String pin) {
        CastingControlService s = sInstance;
        if (s == null) {
            return;
        }
        JSONObject p = new JSONObject();
        try {
            p.put("pairing_id", UUID.randomUUID().toString().replace("-", ""));
            p.put("pc_host", pcHost == null ? "" : pcHost);
            p.put("pc_addr", pcAddress == null ? "" : pcAddress);
            p.put("pin", pin);
        } catch (JSONException e) {
            return;
        }
        s.sendEnvelope("pairing_pending", p);
        Log.i(TAG, "pairing_pending reported for " + pcHost);
    }

    /** 指令执行失败时上报（如找不到目标电脑）。 */
    public static void reportError(String sessionId, String message) {
        CastingControlService s = sInstance;
        if (s == null) {
            return;
        }
        JSONObject p = new JSONObject();
        try {
            p.put("session_id", sessionId == null ? "" : sessionId);
            p.put("message", message == null ? "" : message);
        } catch (JSONException e) {
            return;
        }
        s.sendEnvelope("error", p);
        Log.w(TAG, "error reported: " + message);
    }

    // ── 生命周期 ─────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        connectIfConfigured();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        handler.removeCallbacks(heartbeatTask);
        if (webSocket != null) {
            webSocket.close(1000, "service stopped");
            webSocket = null;
        }
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── 通知 ─────────────────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "投屏控制", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startForegroundNotification() {
        Intent open = new Intent(this, PcView.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("投屏服务运行中")
                .setContentText("等待投屏指令")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    // ── 配置 / 注册 / 连接 ────────────────────────────────────────────
    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String tvId() {
        SharedPreferences sp = prefs();
        String id = sp.getString(KEY_TV_ID, "");
        if (id.isEmpty()) {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            id = (androidId == null ? "tv" : androidId)
                    + "-" + UUID.randomUUID().toString().substring(0, 4);
            sp.edit().putString(KEY_TV_ID, id).apply();
        }
        return id;
    }

    private void connectIfConfigured() {
        String base = prefs().getString(KEY_URL, "").trim();
        if (base.isEmpty()) {
            Log.i(TAG, "casting service URL not configured, idling");
            return;
        }
        String token = prefs().getString(KEY_TOKEN, "");
        if (token.isEmpty()) {
            register(base);
        } else {
            openSocket(base, token);
        }
    }

    private void register(String base) {
        JSONObject body = new JSONObject();
        try {
            body.put("tv_id", tvId());
            body.put("name", prefs().getString(KEY_NAME, Build.MODEL));
            DisplayMetrics dm = getResources().getDisplayMetrics();
            body.put("caps", dm.widthPixels + "x" + dm.heightPixels);
            body.put("ver", "1.0.0-catl");
        } catch (JSONException e) {
            Log.e(TAG, "register body error", e);
            return;
        }
        Request req = new Request.Builder()
                .url(base + "/api/tvs/register")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                Log.w(TAG, "register failed: " + e.getMessage());
                scheduleReconnect();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String text = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "register HTTP " + response.code() + " " + text);
                        scheduleReconnect();
                        return;
                    }
                    JSONObject json = new JSONObject(text);
                    String token = json.optString("tv_token", "");
                    if (token.isEmpty()) {
                        scheduleReconnect();
                        return;
                    }
                    prefs().edit().putString(KEY_TOKEN, token).apply();
                    openSocket(base, token);
                } catch (Exception e) {
                    Log.w(TAG, "register parse failed", e);
                    scheduleReconnect();
                }
            }
        });
    }

    private void openSocket(String base, String token) {
        String wsBase = base.replace("http://", "ws://").replace("https://", "wss://");
        String url = wsBase + "/ws/tv?tv_id=" + tvId() + "&token=" + token;
        Request req = new Request.Builder().url(url).build();
        webSocket = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "control socket open");
                handler.removeCallbacks(heartbeatTask);
                handler.post(heartbeatTask);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.w(TAG, "control socket failure: " + t.getMessage());
                handler.removeCallbacks(heartbeatTask);
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                handler.removeCallbacks(heartbeatTask);
            }
        });
    }

    private void scheduleReconnect() {
        if (stopped) {
            return;
        }
        handler.postDelayed(() -> {
            if (!stopped) {
                connectIfConfigured();
            }
        }, RECONNECT_MS);
    }

    // ── 消息 ─────────────────────────────────────────────────────────
    private void handleMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type", "");
            JSONObject payload = msg.optJSONObject("payload");
            if ("show_code".equals(type) && payload != null) {
                String code = payload.optString("code", "");
                prefs().edit().putString(KEY_LAST_CODE, code).apply();
            }
            boolean gridHandled = false;
            if ("start_session".equals(type) && payload != null) {
                org.json.JSONArray targets = payload.optJSONArray("targets");
                int n = targets != null ? targets.length() : 0;
                String layout = payload.optString("layout", "single");
                if (n > 1 || layout.startsWith("grid")) {
                    gridHandled = true;
                    String targetsJson = targets == null ? "[]" : targets.toString();
                    if (GridActivity.sRunning) {
                        // 增量更新：不重建 Activity、不断已有流（设备逐个加入/退出丝滑无黑屏）
                        Intent upd = new Intent(GridActivity.ACTION_GRID_UPDATE);
                        upd.setPackage(getPackageName());
                        upd.putExtra("targets", targetsJson);
                        sendBroadcast(upd);
                    } else {
                        Intent grid = new Intent(this, GridActivity.class);
                        grid.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        grid.putExtra("targets", targetsJson);
                        grid.putExtra("layout", layout);
                        startActivity(grid);
                    }
                }
            }
            if (!gridHandled) {
                Intent i = new Intent(ACTION_EVENT);
                i.setPackage(getPackageName());
                i.putExtra(EXTRA_TYPE, type);
                i.putExtra(EXTRA_PAYLOAD, payload != null ? payload.toString() : "");
                sendBroadcast(i);
            }

            if ("restart".equals(type)) {
                // 远程重启投屏 App（电视回待机并重新注册）
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.content.Intent launch = getPackageManager()
                                .getLaunchIntentForPackage(getPackageName());
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(launch);
                        }
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }, 300);
                return;
            }
            if ("stop_session".equals(type)) {
                // 抢占/超时/手动结束：通知前台 Game 退回流屏
                Intent stop = new Intent(ACTION_STOP_GAME);
                stop.setPackage(getPackageName());
                sendBroadcast(stop);
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad message", e);
        }
    }

    private void sendEnvelope(String type, JSONObject payload) {
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        JSONObject msg = new JSONObject();
        try {
            msg.put("v", 1);
            msg.put("type", type);
            msg.put("payload", payload);
        } catch (JSONException e) {
            return;
        }
        ws.send(msg.toString());
    }

    private void sendHeartbeat() {
        JSONObject p = new JSONObject();
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            p.put("res", String.format(Locale.US, "%dx%d", dm.widthPixels, dm.heightPixels));
            p.put("active_sessions", 0);
            p.put("ver", "1.0.0-catl");
            // B30 设备台账
            p.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            p.put("android", Build.VERSION.RELEASE);
            p.put("ip", localIp());
            p.put("uptime_min", android.os.SystemClock.elapsedRealtime() / 60000L);
            try {
                java.io.File f = getFilesDir();
                android.os.StatFs sf = new android.os.StatFs(f.getAbsolutePath());
                long freeMb = sf.getAvailableBytes() / (1024 * 1024);
                long totalMb = sf.getTotalBytes() / (1024 * 1024);
                p.put("storage", freeMb + "/" + totalMb);
            } catch (Exception ignored) {
            }
        } catch (JSONException ignored) {
        }
        sendEnvelope("heartbeat", p);
    }

    // ── B32 OTA 远程升级 ─────────────────────────────────────────────
    private static final long OTA_CHECK_INTERVAL_MS = 6 * 3600 * 1000L;
    private long lastOtaCheck = 0;
    private volatile boolean otaInProgress = false;

    private void maybeCheckOta() {
        long now = System.currentTimeMillis();
        if (otaInProgress || now - lastOtaCheck < OTA_CHECK_INTERVAL_MS) {
            return;
        }
        lastOtaCheck = now;
        int currentCode = com.limelight.BuildConfig.VERSION_CODE;
        String url = serviceUrl("api/ota/check?tv_id=" + android.net.Uri.encode(tvId())
                + "&version_code=" + currentCode);
        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                okhttp3.Response resp = client.newCall(new okhttp3.Request.Builder().url(url).build()).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    return;
                }
                JSONObject d = new JSONObject(resp.body().string());
                resp.close();
                if (!d.optBoolean("update", false)) {
                    return;
                }
                String path = d.optString("url", "");
                String sha = d.optString("sha256", "");
                String verName = d.optString("version_name", "");
                Log.i(TAG, "OTA available: " + verName);
                otaInProgress = true;
                downloadAndInstall(client, serviceUrl(path.replaceFirst("^/", "")), sha, verName);
            } catch (Exception e) {
                otaInProgress = false;
                Log.w(TAG, "ota check failed", e);
            }
        }).start();
    }

    private void downloadAndInstall(okhttp3.OkHttpClient client, String url, String expectedSha, String verName) {
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getFilesDir(), "ota");
                if (!dir.exists() && !dir.mkdirs()) {
                    otaInProgress = false;
                    return;
                }
                java.io.File apk = new java.io.File(dir, "update-" + verName + ".apk");
                okhttp3.Response resp = client.newCall(new okhttp3.Request.Builder().url(url).build()).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    otaInProgress = false;
                    return;
                }
                try (java.io.InputStream in = resp.body().byteStream();
                     java.io.OutputStream out = new java.io.FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                    }
                    String actual = toHex(md.digest());
                    if (!expectedSha.isEmpty() && !actual.equalsIgnoreCase(expectedSha)) {
                        Log.w(TAG, "OTA sha mismatch: " + actual);
                        apk.delete();
                        otaInProgress = false;
                        return;
                    }
                } finally {
                    resp.close();
                }
                storeLastOta(verName);
                installApk(apk);
            } catch (Exception e) {
                Log.w(TAG, "ota download failed", e);
                otaInProgress = false;
            }
        }).start();
    }

    private void installApk(java.io.File apk) {
        try {
            android.net.Uri uri = OtaFileProvider.getUriForFile(this, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Log.i(TAG, "OTA installer launched");
            // 安装完成后 App 会被替换重启；此处保持 otaInProgress 避免重复下载
        } catch (Exception e) {
            Log.w(TAG, "ota install failed", e);
            otaInProgress = false;
        }
    }

    private void storeLastOta(String verName) {
        prefs().edit().putString("ota_pending_version", verName).apply();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(java.util.Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private String serviceUrl(String path) {
        String base = prefs().getString(KEY_URL, "");
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + path;
    }

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
