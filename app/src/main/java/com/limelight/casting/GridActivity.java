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
    private LinearLayout row0;
    private LinearLayout row1;
    private LinearLayout root;
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

        // 固定 2x2 容器：行/列权重动态调整，tile 永不移动
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        row0 = new LinearLayout(this);
        row0.setOrientation(LinearLayout.HORIZONTAL);
        row0.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < 4; i++) {
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(2, 2, 2, 2);
            cell.setLayoutParams(lp);
            cell.setBackgroundColor(0xFF05060A);
            cells[i] = cell;
            (i < 2 ? row0 : row1).addView(cell);
        }
        root.addView(row0);
        root.addView(row1);

        applyTargets(parseTargets(getIntent().getStringExtra("targets")));

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
                applyTargets(parseTargets(intent.getStringExtra("targets")));
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

    /** 增量应用 targets：新增填格、移除停格、已有保持不动。 */
    private void applyTargets(List<JSONObject> targets) {
        runOnUiThread(() -> {
            // 1) 移除不在新列表中的 tile
            List<TileSlot> toRemove = new ArrayList<>();
            for (TileSlot s : slotOrder) {
                boolean keep = false;
                for (JSONObject t : targets) {
                    if (slotKey(t).equals(s.key)) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) {
                    toRemove.add(s);
                }
            }
            for (TileSlot s : toRemove) {
                clearTile(s);
            }

            // 2) 新增 tile 填入空闲格
            for (JSONObject t : targets) {
                String key = slotKey(t);
                if (slots.containsKey(key)) {
                    continue;
                }
                int free = findFreeCell();
                if (free < 0) {
                    Toast("超出本机分屏上限");
                    break;
                }
                fillTile(free, key, t);
            }

            // 3) 布局自适应（不移动已有 tile）
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
        int cols = occupiedColumnsEstimate();
        int decW = Math.max(((dm.widthPixels / Math.max(cols, 1)) / 16) * 16, 480);
        int decH = ((decW * 9 / 16) / 16) * 16;
        if (decH <= 0) {
            decH = 270;
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
    private void applyLayout() {
        boolean anyRow0 = false;
        boolean anyRow1 = false;
        for (TileSlot s : slotOrder) {
            if (s.cellIndex < 2) {
                anyRow0 = true;
            } else {
                anyRow1 = true;
            }
        }
        if (slotOrder.isEmpty()) {
            return;
        }
        if (anyRow0 && anyRow1) {
            // 两行都占用：各自 1 行高
            setRowVisible(row0, true, 1f);
            setRowVisible(row1, true, 1f);
        } else if (anyRow0) {
            setRowVisible(row0, true, 1f);
            setRowVisible(row1, false, 0f);
        } else {
            setRowVisible(row0, false, 0f);
            setRowVisible(row1, true, 1f);
        }
        // 列权重：该行内被占用的 cell 平分
        layoutRow(row0);
        layoutRow(row1);
    }

    private void setRowVisible(LinearLayout row, boolean visible, float weight) {
        row.setVisibility(visible ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        lp.weight = weight;
        row.setLayoutParams(lp);
    }

    private void layoutRow(LinearLayout row) {
        boolean[] used = new boolean[2];
        for (TileSlot s : slotOrder) {
            int cellInRow = s.cellIndex % 2;
            if (row == row0 && s.cellIndex < 2) {
                used[cellInRow] = true;
            } else if (row == row1 && s.cellIndex >= 2) {
                used[cellInRow] = true;
            }
        }
        for (int i = 0; i < 2; i++) {
            FrameLayout cell = (FrameLayout) row.getChildAt(i);
            if (cell == null) {
                continue;
            }
            boolean occupied = used[i];
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
            // 占用的格子占满行内空间；未占用的格子直接收掉（weight=0 + GONE）
            // → 1 路全屏、2 路左右、3 路上二下一、4 路 2x2；退出反向收拢，不移动已有画面
            lp.weight = occupied ? 1f : 0f;
            cell.setLayoutParams(lp);
            cell.setVisibility(occupied ? View.VISIBLE : View.GONE);
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
                int targetW = w;
                int targetH = w * 9 / 16;
                if (targetH > h) {
                    targetH = h;
                    targetW = h * 16 / 9;
                }
                setMeasuredDimension(targetW, targetH);
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }
}
