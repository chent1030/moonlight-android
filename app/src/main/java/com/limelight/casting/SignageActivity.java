package com.limelight.casting;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 数字标牌播放界面（B15-B20）：全屏循环播放图片/视频素材 + 底部滚动字幕。
 * 由 CastingControlService 按后台指令启动（signage_play / signage_interrupt），
 * signage_stop 结束。播放列表按素材时长循环。
 */
public class SignageActivity extends Activity {

    private static final String TAG = "CastingSignage";

    public static final String ACTION_SIGNAGE_UPDATE = "com.limelight.casting.SIGNAGE_UPDATE";
    public static final String ACTION_SIGNAGE_STOP = "com.limelight.casting.SIGNAGE_STOP";

    private FrameLayout root;
    private FrameLayout display;
    private TextView marquee;
    private ImageView imageView;
    private VideoView videoView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private int current = 0;
    private BroadcastReceiver updateReceiver;
    private BroadcastReceiver stopReceiver;
    private boolean receiversRegistered;
    private okhttp3.OkHttpClient http;

    private static class Item {
        String type;
        String url;
        int durationS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        http = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        display = new FrameLayout(this);
        root.addView(display, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        marquee = new TextView(this);
        marquee.setTextColor(Color.WHITE);
        marquee.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        marquee.setSingleLine(true);
        marquee.setShadowLayer(6, 0, 2, Color.BLACK);
        FrameLayout.LayoutParams mLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        mLp.gravity = Gravity.BOTTOM;
        mLp.bottomMargin = 48;
        root.addView(marquee, mLp);
        marquee.setVisibility(View.GONE);

        applyContent(getIntent().getStringExtra("content"));

        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyContent(intent.getStringExtra("content"));
            }
        };
        stopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish();
            }
        };
    }

    private void applyContent(String json) {
        items.clear();
        String marqueeText = "";
        try {
            JSONObject d = new JSONObject(json == null ? "{}" : json);
            marqueeText = d.optString("marquee_text", d.optString("marquee", ""));
            JSONArray arr = d.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String url = o.optString("url", "");
                    if (url.isEmpty()) {
                        continue;
                    }
                    Item it = new Item();
                    it.type = o.optString("type", "image");
                    it.url = url.startsWith("http") ? url : baseUrl() + url;
                    it.durationS = Math.max(o.optInt("duration_s", 10), 3);
                    items.add(it);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "bad signage content", e);
        }
        if (marqueeText.isEmpty()) {
            marquee.setVisibility(View.GONE);
            marquee.animate().cancel();
        } else {
            startMarquee(marqueeText);
        }
        current = 0;
        showCurrent();
    }

    private String baseUrl() {
        String base = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                .getString(CastingControlService.KEY_URL, "");
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private void showCurrent() {
        handler.removeCallbacksAndMessages(null);
        display.removeAllViews();
        if (imageView != null) {
            imageView = null;
        }
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {
            }
            videoView = null;
        }
        if (items.isEmpty()) {
            return;
        }
        Item item = items.get(current % items.size());
        if ("video".equals(item.type)) {
            playVideo(item);
        } else {
            showImage(item);
        }
        handler.postDelayed(this::next, item.durationS * 1000L);
    }

    private void next() {
        if (items.isEmpty()) {
            return;
        }
        current = (current + 1) % items.size();
        showCurrent();
    }

    private void showImage(Item item) {
        new Thread(() -> {
            try {
                okhttp3.Response resp = http.newCall(new okhttp3.Request.Builder().url(item.url).build()).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    return;
                }
                try (InputStream in = resp.body().byteStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp == null) {
                        return;
                    }
                    runOnUiThread(() -> {
                        ImageView iv = new ImageView(this);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setImageBitmap(bmp);
                        display.addView(iv, new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                        imageView = iv;
                    });
                } finally {
                    resp.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "image load failed " + item.url, e);
            }
        }).start();
    }

    private void playVideo(Item item) {
        VideoView vv = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        display.addView(vv, lp);
        videoView = vv;
        vv.setVideoURI(Uri.parse(item.url));
        vv.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            vv.start();
        });
        vv.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "video error " + what + "/" + extra);
            handler.postDelayed(this::next, 3000);
            return true;
        });
        try {
            vv.start();
        } catch (Exception ignored) {
        }
    }

    private void startMarquee(String text) {
        marquee.setText(text);
        marquee.setVisibility(View.VISIBLE);
        marquee.post(() -> {
            float width = marquee.getPaint().measureText(text);
            TranslateAnimation anim = new TranslateAnimation(
                    getResources().getDisplayMetrics().widthPixels, -width, 0, 0);
            anim.setDuration(Math.max(6000, (long) ((getResources().getDisplayMetrics().widthPixels + width) * 8)));
            anim.setRepeatCount(Animation.INFINITE);
            anim.setRepeatMode(Animation.RESTART);
            marquee.startAnimation(anim);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (!receiversRegistered) {
            IntentFilter u = new IntentFilter(ACTION_SIGNAGE_UPDATE);
            IntentFilter s = new IntentFilter(ACTION_SIGNAGE_STOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(updateReceiver, u, Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(stopReceiver, s, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(updateReceiver, u);
                registerReceiver(stopReceiver, s);
            }
            receiversRegistered = true;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {
            }
        }
        if (receiversRegistered) {
            try {
                unregisterReceiver(updateReceiver);
                unregisterReceiver(stopReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        super.onDestroy();
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
}
