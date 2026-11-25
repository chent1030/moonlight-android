package com.limelight.casting;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路分屏宿主（增量更新，不重建 Activity、不重连已有流）。
 *
 * 固定 2x2 容器 + 动态权重布局：
 * - 已有 tile 的 SurfaceView 永不移动（不换 parent，Surface 不销毁，流不断）
 * - 新设备加入 = 填入空闲格并淡入；退出 = 停该格并移除
 * - 布局按实际占用的格子自适应（1 路全屏 / 2 路左右 / 3-4 路 2x2）
 *
 * Intent extras: "targets" = JSON 数组；广播 ACTION_GRID_UPDATE 增量更新。
 */
public class GridActivity extends Activity {

    private static final String TAG = "CastingGrid";
    private static final int MAX_TILES = 4;
    public static final String ACTION_GRID_UPDATE = "com.limelight.casting.GRID_UPDATE";
    public static final String ACTION_TILE_STATE = "com.limelight.casting.TILE_STATE";

    /** GridActivity 是否在前台运行（CastingControlService 据此选择增量广播或首次启动）。 */
    public static volatile boolean sRunning = false;

    private final FrameLayout[] cells = new FrameLayout[MAX_TILES];
    private android.widget.FrameLayout root;
    private LinearLayout stripRow;   // 顶部缩略窗条
    private FrameLayout mainCell;    // 主画面（占满剩余区域）
    private String mainKey = "";     // 当前主画面 target key
    private int thumbW = 360;        // 缩略窗宽度（px）
    private int thumbH = 203;        // 缩略窗高度（16:9）
    private final Map<String, TileSlot> slots = new HashMap<>();
    private final List<TileSlot> slotOrder = new ArrayList<>();
    private BroadcastReceiver updateReceiver;
    private BroadcastReceiver stopReceiver;
    private BroadcastReceiver tileStateReceiver;
    private boolean receiversRegistered;

    private static class TileSlot {
        String key;              // pc_id 或 host
        int cellIndex;
        SurfaceView view;
        ITileRenderer renderer;
        ServiceConnection connection;
        boolean started;
        TextView hint;
        String host;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        sRunning = true;

        // 布局：主画面始终全屏铺满；缩略窗条悬浮叠加在画面顶部（不挤压主画面）
        root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        android.util.DisplayMetrics dm0 = getResources().getDisplayMetrics();
        thumbW = Math.round(dm0.widthPixels * 0.12f);
        thumbH = Math.round(thumbW * 9f / 16f);

        mainCell = new FrameLayout(this);
        mainCell.setBackgroundColor(0xFF05060A);
        root.addView(mainCell, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        stripRow = new LinearLayout(this);
        stripRow.setOrientation(LinearLayout.HORIZONTAL);
        stripRow.setGravity(Gravity.CENTER_HORIZONTAL);
        stripRow.setBackgroundColor(0x99000000);
        stripRow.setPadding(12, 12, 12, 12);
        stripRow.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams stripLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        stripLp.topMargin = 20;
        root.addView(stripRow, stripLp);

        cells[0] = mainCell;
        for (int i = 1; i < 4; i++) {
            FrameLayout thumb = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(thumbW, thumbH);
            lp.setMargins(8, 0, 8, 0);
            thumb.setLayoutParams(lp);
            thumb.setBackgroundColor(0xFF05060A);
            cells[i] = thumb;
            stripRow.addView(thumb);
        }

        applyTargets(parseTargets(getIntent().getStringExtra("targets")),
                getIntent().getStringExtra("main"));

        tileStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String host = intent.getStringExtra("host");
                String state = intent.getStringExtra("state");
                runOnUiThread(() -> {
                    for (TileSlot s : slotOrder) {
                        if (s.hint == null) {
                            continue;
                        }
                        boolean match = host != null && (host.equals(s.host) || host.equals(s.key));
                        if (!match) {
                            continue;
                        }
                        if (state != null && state.startsWith("connected")) {
                            s.hint.setText("");
                            s.hint.setVisibility(View.GONE);
                        } else if (state != null && state.startsWith("failed")) {
                            s.hint.setText("连接失败");
                            s.hint.setTextColor(0xFFD9534F);
                        }
                    }
                });
            }
        };
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyTargets(parseTargets(intent.getStringExtra("targets")),
                        intent.getStringExtra("main"));
            }
        };
        stopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish();
            }
        };
    }

    private List<JSONObject> parseTargets(String json) {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                if (t.optString("host").isEmpty()) {
                    continue;
                }
                list.add(t);
            }
        } catch (Exception e) {
            Log.w(TAG, "bad targets json", e);
        }
        return list;
    }

    private String slotKey(JSONObject t) {
        String pcId = t.optString("pc_id", "");
        return pcId.isEmpty() ? t.optString("host") : pcId;
    }

    /**
     * 增量应用 targets + 主画面：
     * - 主画面（main）占 cell 0 全屏；其余按顺序占顶部缩略窗 cell 1..3
     * - 已有 tile 的分配不变则完全不动（第一路画面不中断）
     * - 分配变化（切主画面）只重建受影响的两路
     */
    private void applyTargets(List<JSONObject> targets, String desiredMain) {
        runOnUiThread(() -> {
            if (targets.isEmpty()) {
                for (TileSlot s : new ArrayList<>(slotOrder)) {
                    clearTile(s);
                }
                applyLayout();
                return;
            }
            String wantMain = desiredMain == null ? "" : desiredMain;
            boolean mainExists = false;
            for (JSONObject t : targets) {
                if (slotKey(t).equals(wantMain)) {
                    mainExists = true;
                    break;
                }
            }
            if (!mainExists) {
                wantMain = slotKey(targets.get(0));
            }
            mainKey = wantMain;

            // 期望的 cell 分配：main → 0，其余 → 1、2、3
            java.util.Map<String, Integer> desired = new java.util.LinkedHashMap<>();
            desired.put(wantMain, 0);
            int nextCell = 1;
            for (JSONObject t : targets) {
                String k = slotKey(t);
                if (k.equals(wantMain)) {
                    continue;
                }
                if (nextCell >= MAX_TILES) {
                    break;
                }
                desired.put(k, nextCell++);
            }

            // 1) 移除不在列表中的 tile
            for (TileSlot s : new ArrayList<>(slotOrder)) {
                if (!desired.containsKey(s.key)) {
                    clearTile(s);
                }
            }
            // 2) 分配不一致的 tile 重建（切主画面时只动相关两路）
            for (JSONObject t : targets) {
                String key = slotKey(t);
                Integer cell = desired.get(key);
                if (cell == null) {
                    continue;
                }
                TileSlot exist = slots.get(key);
                if (exist != null) {
                    if (exist.cellIndex == cell) {
                        continue;
                    }
                    clearTile(exist);
                }
                fillTile(cell, key, t);
            }
            applyLayout();
        });
    }

    private int findFreeCell() {
        for (int i = 0; i < MAX_TILES; i++) {
            boolean used = false;
            for (TileSlot s : slotOrder) {
                if (s.cellIndex == i) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return i;
            }
        }
        return -1;
    }

    private void fillTile(int cellIndex, String key, JSONObject target) {
        String host = target.optString("host");
        int port = target.optInt("port", 47989);

        TileSlot slot = new TileSlot();
        slot.key = key;
        slot.host = host;
        slot.cellIndex = cellIndex;

        AspectSurfaceView sv = new AspectSurfaceView(this);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        svLp.gravity = Gravity.CENTER;
        cells[cellIndex].addView(sv, svLp);
        sv.setAlpha(0f);
        sv.animate().alpha(1f).setDuration(220).start();  // 淡入
        slot.view = sv;

        // 连接中提示（连接成功后由 TILE_STATE 广播移除；失败则保留说明）
        TextView hint = new TextView(this);
        hint.setTextColor(0xFF7A8296);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setText("连接中…");
        cells[cellIndex].addView(hint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        slot.hint = hint;

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int decW;
        int decH;
        if (cellIndex == 0) {
            // 主画面：按屏幕全尺寸解码
            decW = Math.max((dm.widthPixels / 16) * 16, 1280);
            decH = Math.max(((dm.heightPixels * 9 / 16) / 16) * 16, 720);
        } else {
            // 缩略窗：按缩略尺寸解码（省带宽与解码开销）
            decW = Math.max((thumbW / 16) * 16, 320);
            decH = Math.max((thumbH / 16) * 16, 180);
        }
        String cfg = buildConfig(cellIndex, host, port, decW, decH, slotOrder.isEmpty());

        Class<?> svcClass = tileServiceClass(cellIndex);
        ServiceConnection sc = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final ITileRenderer r = ITileRenderer.Stub.asInterface(service);
                slot.renderer = r;
                android.view.SurfaceHolder holder = sv.getHolder();
                if (holder.getSurface() != null && holder.getSurface().isValid()) {
                    startSlot(slot, holder.getSurface(), cfg);
                } else {
                    holder.addCallback(new android.view.SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(android.view.SurfaceHolder h) {
                            startSlot(slot, h.getSurface(), cfg);
                        }

                        @Override
                        public void surfaceChanged(android.view.SurfaceHolder h, int format, int width, int height) {
                        }

                        @Override
                        public void surfaceDestroyed(android.view.SurfaceHolder h) {
                        }
                    });
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        slot.connection = sc;
        bindService(new Intent(this, svcClass), sc, Context.BIND_AUTO_CREATE);

        slots.put(key, slot);
        slotOrder.add(slot);
        Log.i(TAG, "tile added " + key + " -> cell " + cellIndex);
    }

    private void startSlot(TileSlot slot, android.view.Surface surface, String cfg) {
        if (slot.renderer == null || slot.started || surface == null || !surface.isValid()) {
            return;
        }
        try {
            slot.renderer.start(surface, cfg);
            slot.started = true;
            Log.i(TAG, "tile started " + slot.key);
        } catch (Exception e) {
            Log.w(TAG, "tile start failed", e);
        }
    }

    private void clearTile(TileSlot slot) {
        Log.i(TAG, "tile removed " + slot.key);
        if (slot.renderer != null) {
            try {
                slot.renderer.stop();
            } catch (Exception ignored) {
            }
        }
        if (slot.connection != null) {
            try {
                unbindService(slot.connection);
            } catch (Exception ignored) {
            }
        }
        FrameLayout cell = cells[slot.cellIndex];
        cell.removeAllViews();
        slots.remove(slot.key);
        slotOrder.remove(slot);
        applyLayout();
    }

    private int occupiedColumnsEstimate() {
        // 粗略列数：占用 ≥2 格且跨两行时按 2 列解码
        int count = slotOrder.size() + 1;
        if (count <= 1) {
            return 1;
        }
        return 2;
    }

    private Class<?> tileServiceClass(int cellIndex) {
        switch (cellIndex) {
            case 0: return Tile1Service.class;
            case 1: return Tile2Service.class;
            case 2: return Tile3Service.class;
            default: return Tile4Service.class;
        }
    }

    private String buildConfig(int index, String host, int port, int width, int height, boolean withAudio) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("host", host);
            cfg.put("port", port);
            cfg.put("httpsPort", 47984);
            cfg.put("width", Math.max(width, 320));
            cfg.put("height", Math.max(height, 180));
            // 每路唯一 uniqueid：Sunshine 对相同 uniqueid 的并发连接会拒绝
            cfg.put("uniqueId", String.format(java.util.Locale.US, "%08x-tile%d",
                    java.util.UUID.randomUUID().hashCode(), index));
            cfg.put("audio", withAudio);
            String certKey = "cast_cert_" + host;
            String b64 = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                    .getString(certKey, "");
            if (b64 == null || b64.isEmpty()) {
                b64 = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                        .getString("cast_known_cert", "");
            }
            cfg.put("cert", b64 == null ? "" : b64);
        } catch (Exception ignored) {
        }
        return cfg.toString();
    }

    /** 权重自适应布局：不动 tile 视图，只调行/列可见性与权重。 */
    /** 顶部缩略条：有第二路起才显示；主画面始终占满剩余区域。 */
    private void applyLayout() {
        boolean hasMain = slots.containsKey(mainKey);
        boolean hasThumb = false;
        for (TileSlot s : slotOrder) {
            if (s.cellIndex > 0) {
                hasThumb = true;
                break;
            }
        }
        stripRow.setVisibility(hasThumb ? View.VISIBLE : View.GONE);
        // 缩略窗按实际数量收紧（空 cell 隐藏）
        for (int i = 1; i < MAX_TILES; i++) {
            boolean used = false;
            for (TileSlot s : slotOrder) {
                if (s.cellIndex == i) {
                    used = true;
                    break;
                }
            }
            cells[i].setVisibility(used ? View.VISIBLE : View.GONE);
        }
        if (!hasMain && hasThumb) {
            // 主画面暂无（例如刚被移除）：把第一路缩略提升为主画面
            for (TileSlot s : slotOrder) {
                if (s.cellIndex > 0) {
                    mainKey = s.key;
                    clearTile(s);
                    JSONObject t = new JSONObject();
                    try {
                        t.put("host", s.host);
                        t.put("pc_id", s.key);
                    } catch (Exception ignored) {
                    }
                    fillTile(0, s.key, t);
                    break;
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (!receiversRegistered && updateReceiver != null && stopReceiver != null) {
            IntentFilter update = new IntentFilter(ACTION_GRID_UPDATE);
            IntentFilter stop = new IntentFilter(CastingControlService.ACTION_STOP_GAME);
            IntentFilter tileState = new IntentFilter(ACTION_TILE_STATE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(updateReceiver, update, Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(stopReceiver, stop, Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(tileStateReceiver, tileState, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(updateReceiver, update);
                registerReceiver(stopReceiver, stop);
                registerReceiver(tileStateReceiver, tileState);
            }
            receiversRegistered = true;
        }
    }

    @Override
    protected void onDestroy() {
        sRunning = false;
        if (receiversRegistered) {
            try {
                unregisterReceiver(updateReceiver);
                unregisterReceiver(stopReceiver);
                unregisterReceiver(tileStateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiversRegistered = false;
        }
        for (TileSlot s : new ArrayList<>(slotOrder)) {
            if (s.renderer != null) {
                try {
                    s.renderer.stop();
                } catch (Exception ignored) {
                }
            }
            if (s.connection != null) {
                try {
                    unbindService(s.connection);
                } catch (Exception ignored) {
                }
            }
        }
        slots.clear();
        slotOrder.clear();
        super.onDestroy();
    }

    private void Toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** 保持 16:9 比例、在 cell 内居中的 SurfaceView（画面不变形，黑边留在布局层）。 */
    private static class AspectSurfaceView extends SurfaceView {
        AspectSurfaceView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            if (w > 0 && h > 0) {
                // 强制填满容器：宽高都取容器尺寸（16:9 流在 16:9 屏上无拉伸）
                setMeasuredDimension(w, h);
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }
}
