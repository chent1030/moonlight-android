package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.util.List;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.ServerHelper;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import com.limelight.casting.CastingControlService;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private TextView castCodeOverlay;
    private TextView castStatusOverlay;
    // ── 服务模式首页（内容层 + 信息层）────────────��──────────────────
    private android.widget.FrameLayout standbyContentLayer;
    private android.widget.ImageView standbyImage;
    private android.widget.VideoView standbyVideo;
    private TextView standbyClock;
    private android.widget.LinearLayout standbyRightColumn;
    private boolean clockFitted = false;
    private TextView standbyCodeText;
    private TextView standbyPageLabel;
    private android.widget.LinearLayout standbyCodeCard;
    public static final String ACTION_STANDBY_CONTENT = "com.limelight.casting.STANDBY_CONTENT";
    private android.content.BroadcastReceiver standbyContentReceiver;
    private Runnable standbyRefreshTick;
    private final java.util.List<org.json.JSONObject> standbyItems = new java.util.ArrayList<>();
    private int standbyIndex = 0;
    private final android.os.Handler standbyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable clockTick;
    private Runnable contentTick;
    private BroadcastReceiver castReceiver;
    private boolean castReceiverRegistered;
    private boolean serviceMode;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates（服务模式跳过电脑探测）
                    if (!serviceMode) {
                        startComputerUpdates();
                    }

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();

        setupCasting();
    }

    // ── M2 投屏控制（会话服务）──────────────────────────────────────
    private void setupCasting() {
        serviceMode = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                .getString(CastingControlService.KEY_URL, "").trim().length() > 0;

        if (serviceMode) {
            buildStandbyHome();
        } else {
            // 直连模式：保留原投屏码黑条覆盖层
            castCodeOverlay = new TextView(this);
            castCodeOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            castCodeOverlay.setTextColor(Color.WHITE);
            castCodeOverlay.setBackgroundColor(0xCC0B0D14);
            castCodeOverlay.setPadding(24, 12, 24, 12);
            castCodeOverlay.setGravity(Gravity.CENTER);
            castCodeOverlay.setVisibility(View.GONE);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            lp.topMargin = 64;
            addContentView(castCodeOverlay, lp);
            castStatusOverlay = new TextView(this);
            castStatusOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            castStatusOverlay.setTextColor(0xFF7A8296);
            castStatusOverlay.setVisibility(View.GONE);
            addContentView(castStatusOverlay, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        }

        // 服务模式：隐藏设置齿轮、+、电脑列表与空态 —— 电视只是接收端
        if (serviceMode) {
            View settings = findViewById(R.id.settingsButton);
            if (settings != null) settings.setVisibility(View.GONE);
            View addPc = findViewById(R.id.manuallyAddPc);
            if (addPc != null) addPc.setVisibility(View.GONE);
            View pcGrid = findViewById(R.id.pcFragmentContainer);
            if (pcGrid != null) pcGrid.setVisibility(View.GONE);
            View noPc = findViewById(R.id.no_pc_found_layout);
            if (noPc != null) noPc.setVisibility(View.GONE);
            setCastStatus("等待投屏指令");
        }

        // 长按标题进入投屏服务配置
        TextView title = findViewById(R.id.title_text);
        if (title != null) {
            title.setOnLongClickListener(v -> {
                showCastingSettings();
                return true;
            });
        }

        // CS 事件接收器
        castReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String type = intent.getStringExtra(CastingControlService.EXTRA_TYPE);
                String payload = intent.getStringExtra(CastingControlService.EXTRA_PAYLOAD);
                if ("show_code".equals(type) && castCodeOverlay != null) {
                    String code = "";
                    try {
                        code = new org.json.JSONObject(payload == null ? "{}" : payload).optString("code", "");
                    } catch (org.json.JSONException ignored) {
                    }
                    castCodeOverlay.setText("投屏码 " + code);
                    castCodeOverlay.setVisibility(View.VISIBLE);
                } else if ("stop_session".equals(type)) {
                    if (castCodeOverlay != null) {
                        castCodeOverlay.setVisibility(View.GONE);
                    }
                    setCastStatus("等待投屏指令");
                } else if ("start_session".equals(type)) {
                    handleStartSession(payload);
                } else if ("lock".equals(type)) {
                    Toast.makeText(PcView.this, "大屏已锁定", Toast.LENGTH_SHORT).show();
                } else if ("notify".equals(type)) {
                    try {
                        String text = new org.json.JSONObject(payload == null ? "{}" : payload).optString("text", "");
                        Toast.makeText(PcView.this, text, Toast.LENGTH_SHORT).show();
                    } catch (org.json.JSONException ignored) {
                    }
                }
            }
        };
        registerCastingReceiver();

        if (castingConfigured()) {
            CastingControlService.start(this);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 服务模式首页：全屏内容 + 左上 logo + 右上时间/投屏码卡片 + 底部轮播指示
    // ══════════════════════════════════════════════════════════════
    private void buildStandbyHome() {
        // 内容层（最底）：用负边距抵消 activity 根布局 padding，做到真正全屏到边
        standbyContentLayer = new android.widget.FrameLayout(this);
        standbyContentLayer.setBackgroundColor(0xFF0A1428);
        android.widget.FrameLayout.LayoutParams contentLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        android.view.View rootContent = findViewById(android.R.id.content);
        if (rootContent instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) rootContent;
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
            contentLp.leftMargin = -vg.getPaddingLeft();
            contentLp.topMargin = -vg.getPaddingTop();
            contentLp.rightMargin = -vg.getPaddingRight();
            contentLp.bottomMargin = -vg.getPaddingBottom();
        }
        addContentView(standbyContentLayer, contentLp);

        // 左上 logo（品牌位）
        TextView logo = new TextView(this);
        logo.setText("CATL 投屏");
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        logo.setTextColor(Color.WHITE);
        logo.getPaint().setFakeBoldText(true);
        logo.setShadowLayer(8, 0, 2, 0x99000000);
        android.widget.FrameLayout.LayoutParams logoLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.START);
        logoLp.topMargin = 36;
        logoLp.leftMargin = 40;
        addContentView(logo, logoLp);

        // 右上信息列：宽度由投屏码卡片决定，时间与卡片左右边缘对齐
        standbyRightColumn = new android.widget.LinearLayout(this);
        standbyRightColumn.setOrientation(android.widget.LinearLayout.VERTICAL);
        standbyRightColumn.setGravity(Gravity.END);

        standbyClock = new TextView(this);
        standbyClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        standbyClock.setTextColor(Color.WHITE);
        standbyClock.setGravity(Gravity.CENTER);
        standbyClock.setShadowLayer(6, 0, 1, 0x99000000);
        android.widget.LinearLayout.LayoutParams clockLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        clockLp.bottomMargin = 10;
        standbyRightColumn.addView(standbyClock, clockLp);

        // 投屏码卡片（白底圆角）
        standbyCodeCard = new android.widget.LinearLayout(this);
        standbyCodeCard.setOrientation(android.widget.LinearLayout.VERTICAL);
        standbyCodeCard.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(dp(18));
        standbyCodeCard.setBackground(cardBg);
        standbyCodeCard.setPadding(dp(40), dp(16), dp(40), dp(16));
        standbyCodeCard.setClipToOutline(true);  // 阴影/裁剪跟随圆角，避免直角残留

        TextView codeTitle = new TextView(this);
        codeTitle.setText("投屏码");
        codeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        codeTitle.setTextColor(0xFF7A8296);
        codeTitle.setGravity(Gravity.CENTER);
        standbyCodeCard.addView(codeTitle);

        standbyCodeText = new TextView(this);
        standbyCodeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        standbyCodeText.setTextColor(0xFF005BAC);  // CATL 蓝
        standbyCodeText.getPaint().setFakeBoldText(true);
        standbyCodeText.setGravity(Gravity.CENTER);
        standbyCodeText.setSingleLine(true);
        standbyCodeText.setLetterSpacing(0.05f);
        standbyCodeText.setText("------");
        android.widget.LinearLayout.LayoutParams codeLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(2);
        standbyCodeCard.addView(standbyCodeText, codeLp);

        TextView codeHint = new TextView(this);
        codeHint.setText("打开投屏助手，输入投屏码投屏");
        codeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        codeHint.setTextColor(0xFF9AA3B5);
        codeHint.setGravity(Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams hintLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        standbyCodeCard.addView(codeHint, hintLp);
        // 卡片加入右列（宽度自适应，列宽由它决定）
        android.widget.LinearLayout.LayoutParams cardLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        standbyRightColumn.addView(standbyCodeCard, cardLp);

        // 右列整体定位右上（卡片右边缘距屏幕 36dp）
        android.widget.FrameLayout.LayoutParams colLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
        colLp.topMargin = 32;
        colLp.rightMargin = 36;
        addContentView(standbyRightColumn, colLp);

        // 底部轮播指示（1/N）
        standbyPageLabel = new TextView(this);
        standbyPageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        standbyPageLabel.setTextColor(0xFFE7EAF2);
        android.graphics.drawable.GradientDrawable pageBg = new android.graphics.drawable.GradientDrawable();
        pageBg.setColor(0x66000000);
        pageBg.setCornerRadius(dp(14));
        standbyPageLabel.setBackground(pageBg);
        standbyPageLabel.setPadding(dp(16), dp(5), dp(16), dp(5));
        standbyPageLabel.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams pageLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        pageLp.bottomMargin = dp(28);
        addContentView(standbyPageLabel, pageLp);

        startClock();
        // 广告机：纯内容展示，不显示投屏码/不接受投屏
        boolean signageDevice = com.limelight.casting.DeviceType.isSignage(this);
        if (signageDevice) {
            standbyCodeCard.setVisibility(View.GONE);
        }
        // 投屏码（服务端 show_code 已存入 prefs）
        String code = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                .getString(CastingControlService.KEY_LAST_CODE, "");
        updateStandbyCode(code);
        // 拉取后台推送内容（图片/视频，多张循环）
        fetchStandbyContent();
        // 后台推送内容即刷新（CastingControlService 收到 signage_play 后广播）
        standbyContentReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                applyStandbyContent(intent.getStringExtra("content"));
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(standbyContentReceiver,
                    new android.content.IntentFilter(ACTION_STANDBY_CONTENT),
                    android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(standbyContentReceiver, new android.content.IntentFilter(ACTION_STANDBY_CONTENT));
        }
        // 每 60 秒同步一次后台内容（排期变更无需重启电视）
        standbyRefreshTick = new Runnable() {
            @Override
            public void run() {
                fetchStandbyContent();
                standbyHandler.postDelayed(this, 60_000L);
            }
        };
        standbyHandler.postDelayed(standbyRefreshTick, 60_000L);
        // 首帧布局完成后，把时间文字宽度适配到卡片宽度（两端齐平）
        standbyCodeCard.post(this::fitClockToCardWidth);
        setCastStatus("等待投屏指令");
    }

    /**
     * 让时间文字的实际渲染宽度等于投屏码卡片宽度：
     * 以卡片宽度为目标，二分/线性搜索最大可用字号（13~24sp），
     * 使「2026-11-25 星期三 13:17」两端与卡片左右边缘齐平。
     */
    private void fitClockToCardWidth() {
        if (standbyClock == null || standbyCodeCard == null) {
            return;
        }
        int cardWidth = standbyCodeCard.getWidth();
        if (cardWidth <= 0) {
            // 布局未完成，稍后重试（最多几帧）
            standbyHandler.postDelayed(this::fitClockToCardWidth, 120L);
            return;
        }
        CharSequence text = standbyClock.getText();
        if (text == null || text.length() == 0) {
            return;
        }
        android.text.TextPaint paint = new android.text.TextPaint(standbyClock.getPaint());
        String probe = text.toString();
        android.graphics.Rect probeInk = new android.graphics.Rect();
        float bestSp = 13f;
        // 选最大字号，保证整串文字（含字间距）不超出卡片宽度，避免被裁
        for (float sp = 24f; sp >= 12f; sp -= 0.25f) {
            paint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
            if (paint.measureText(probe, 0, probe.length()) <= cardWidth) {
                bestSp = sp;
                break;
            }
        }
        standbyClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, bestSp);
        // 按「字形墨迹」宽度精确对齐：目标墨迹宽 = 卡片宽 - 左右字形边距
        float textPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, bestSp, getResources().getDisplayMetrics());
        paint.setTextSize(textPx);
        String str = text.toString();
        android.graphics.Rect ink = new android.graphics.Rect();
        paint.getTextBounds(str, 0, str.length(), ink);
        float measured = paint.measureText(str, 0, str.length());
        float leftBearing = Math.max(ink.left, 0);
        float rightBearing = Math.max(measured - ink.right, 0);
        float inkWidth = ink.width();
        float targetInk = cardWidth - leftBearing - rightBearing
                - standbyClock.getPaddingLeft() - standbyClock.getPaddingRight();
        int gaps = Math.max(str.length() - 1, 1);
        float spacingPx = (targetInk - inkWidth) / gaps;
        standbyClock.setLetterSpacing(spacingPx > 0 ? spacingPx / textPx : 0f);
        clockFitted = true;
    }

    /** 视频铺满全屏：VideoView 默认按视频比例测量并居中，会留黑边；覆盖 onMeasure 强制填满。 */
    private static class FullscreenVideoView extends android.widget.VideoView {
        FullscreenVideoView(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            // 忽略视频自身比例，直接铺满父容器（画面按 CROPPING 模式裁剪）
            setMeasuredDimension(
                    android.view.View.MeasureSpec.getSize(widthMeasureSpec),
                    android.view.View.MeasureSpec.getSize(heightMeasureSpec));
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void startClock() {
        if (clockTick != null) {
            standbyHandler.removeCallbacks(clockTick);
        }
        clockTick = new Runnable() {
            @Override
            public void run() {
                java.util.Calendar c = java.util.Calendar.getInstance();
                String[] week = {"", "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
                String text = String.format(java.util.Locale.CHINA, "%d-%02d-%02d %s %02d:%02d",
                        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                        c.get(java.util.Calendar.DAY_OF_MONTH),
                        week[c.get(java.util.Calendar.DAY_OF_WEEK)],
                        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
                if (standbyClock != null) {
                    standbyClock.setText(text);
                    fitClockToCardWidth();
                }
                standbyHandler.postDelayed(this, 30_000L);
            }
        };
        standbyHandler.post(clockTick);
    }

    /** show_code 更新投屏码卡片。 */
    private void updateStandbyCode(String code) {
        if (standbyCodeText == null || code == null || code.isEmpty()) {
            return;
        }
        runOnUiThread(() -> standbyCodeText.setText(code));
    }

    /** 拉取后台推送的标牌内容并循环播放。 */
    /** 应用后台推送的内容（signage_play 指令 payload）。 */
    private void applyStandbyContent(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        runOnUiThread(() -> {
            try {
                org.json.JSONObject d = new org.json.JSONObject(json);
                org.json.JSONArray arr = d.optJSONArray("items");
                java.util.List<org.json.JSONObject> items = new java.util.ArrayList<>();
                String base = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                        .getString(CastingControlService.KEY_URL, "");
                if (base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String u = o.optString("url", "");
                        if (!u.isEmpty()) {
                            if (!u.startsWith("http")) {
                                o.put("url", base + u);
                            }
                            items.add(o);
                        }
                    }
                }
                standbyHandler.removeCallbacks(contentTick);
                standbyItems.clear();
                standbyItems.addAll(items);
                standbyIndex = 0;
                if (!standbyItems.isEmpty()) {
                    showStandbyContent();
                }
            } catch (Exception e) {
                android.util.Log.w("CastingStandby", "apply content failed: " + e.getMessage());
            }
        });
    }

    private void fetchStandbyContent() {
        String baseTemp = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                .getString(CastingControlService.KEY_URL, "");
        if (baseTemp.endsWith("/")) {
            baseTemp = baseTemp.substring(0, baseTemp.length() - 1);
        }
        final String base = baseTemp;
        final String url = base + "/api/signage/" + CastingControlService.tvIdOf(this);
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
                String body = resp.body().string();
                resp.close();
                org.json.JSONObject root = new org.json.JSONObject(body);
                org.json.JSONObject data = root.optJSONObject("data") != null ? root.getJSONObject("data") : root;
                org.json.JSONArray arr = data.optJSONArray("items");
                java.util.List<org.json.JSONObject> items = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        if (!o.optString("url").isEmpty()) {
                            o.put("url", o.optString("url").startsWith("http")
                                    ? o.optString("url") : base0(base) + o.optString("url"));
                            items.add(o);
                        }
                    }
                }
                final java.util.List<org.json.JSONObject> fItems = items;
                runOnUiThread(() -> {
                    standbyItems.clear();
                    standbyItems.addAll(fItems);
                    standbyIndex = 0;
                    if (!standbyItems.isEmpty()) {
                        showStandbyContent();
                    }
                });
            } catch (Exception e) {
                android.util.Log.w("CastingStandby", "fetch signage failed: " + e.getMessage());
            }
        }).start();
    }

    private String base0(String base) {
        return base;
    }

    private void showStandbyContent() {
        if (standbyItems.isEmpty()) {
            standbyPageLabel.setVisibility(View.GONE);
            return;
        }
        org.json.JSONObject item = standbyItems.get(standbyIndex % standbyItems.size());
        String type = item.optString("type", "image");
        String url = item.optString("url", "");
        int durationS = Math.max(item.optInt("duration_s", 10), 3);
        standbyPageLabel.setText((standbyIndex % standbyItems.size() + 1) + "/" + standbyItems.size());
        standbyPageLabel.setVisibility(standbyItems.size() > 1 ? View.VISIBLE : View.GONE);

        if ("video".equals(type)) {
            standbyContentLayer.removeAllViews();
            FullscreenVideoView vv = new FullscreenVideoView(this);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
            standbyContentLayer.addView(vv, lp);
            standbyVideo = vv;
            vv.setVideoURI(android.net.Uri.parse(url));
            vv.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                // 裁剪铺满全屏（保持比例裁掉超出部分，不拉伸变形）
                try {
                    mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                } catch (Exception ignored) {
                }
                vv.start();
            });
            vv.setOnErrorListener((mp, what, extra) -> {
                standbyHandler.postDelayed(this::nextStandbyContent, 3000);
                return true;
            });
            try {
                vv.start();
            } catch (Exception ignored) {
            }
        } else {
            new Thread(() -> {
                try {
                    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                    okhttp3.Response resp = client.newCall(new okhttp3.Request.Builder().url(url).build()).execute();
                    if (!resp.isSuccessful() || resp.body() == null) {
                        return;
                    }
                    final android.graphics.Bitmap bmp =
                            android.graphics.BitmapFactory.decodeStream(resp.body().byteStream());
                    resp.close();
                    if (bmp == null) {
                        return;
                    }
                    runOnUiThread(() -> {
                        standbyContentLayer.removeAllViews();
                        android.widget.ImageView iv = new android.widget.ImageView(this);
                        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                        iv.setImageBitmap(bmp);
                        standbyContentLayer.addView(iv, new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                        standbyImage = iv;
                    });
                } catch (Exception e) {
                    android.util.Log.w("CastingStandby", "image load failed: " + e.getMessage());
                }
            }).start();
        }
        if (contentTick != null) {
            standbyHandler.removeCallbacks(contentTick);
        }
        contentTick = new Runnable() {
            @Override
            public void run() {
                nextStandbyContent();
            }
        };
        standbyHandler.postDelayed(contentTick, durationS * 1000L);
    }

    private void nextStandbyContent() {
        if (standbyItems.isEmpty()) {
            return;
        }
        standbyIndex = (standbyIndex + 1) % standbyItems.size();
        showStandbyContent();
    }

    private void setCastStatus(String text) {
        if (castStatusOverlay == null) {
            return;
        }
        runOnUiThread(() -> {
            if (text == null || text.isEmpty()) {
                castStatusOverlay.setVisibility(View.GONE);
            } else {
                castStatusOverlay.setText(text);
                castStatusOverlay.setVisibility(View.VISIBLE);
            }
        });
    }

    // 配对证书持久化（服务模式停掉 CMS 轮询后，重启/重装仍可免重配）
    private void saveKnownCert(byte[] der) {
        if (der == null) {
            return;
        }
        getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                .edit()
                .putString("cast_known_cert", android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP))
                .apply();
    }

    private java.security.cert.X509Certificate loadKnownCert() {
        String b64 = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                .getString("cast_known_cert", "");
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        try {
            byte[] der = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            return (java.security.cert.X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean castingConfigured() {
        SharedPreferences sp = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE);
        return !sp.getString(CastingControlService.KEY_URL, "").trim().isEmpty();
    }

    private void showCastingSettings() {
        SharedPreferences sp = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(sp.getString(CastingControlService.KEY_URL, ""));
        input.setHint("http://cast.corp.local:8099");
        new AlertDialog.Builder(this)
                .setTitle("投屏服务地址")
                .setMessage("填写会话服务（CS）地址，留空则关闭服务模式")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    sp.edit().putString(CastingControlService.KEY_URL, url).apply();
                    if (url.isEmpty()) {
                        sp.edit().remove(CastingControlService.KEY_TOKEN).apply();
                        CastingControlService.stop(this);
                    } else {
                        CastingControlService.start(this);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * M2.2：收到 CS 的 start_session 指令，找到目标电脑并直接拉起桌面投屏。
     * 匹配顺序：电脑名 == pc_id 的主机部分 → 地址匹配 → 唯一在线电脑兜底。
     */
    private void handleStartSession(String payloadJson) {
        String sessionId = "";
        final String fPcName, fHost;
        try {
            org.json.JSONObject p = new org.json.JSONObject(payloadJson == null ? "{}" : payloadJson);
            sessionId = p.optString("session_id", "");
            org.json.JSONArray targets = p.optJSONArray("targets");
            if (targets == null || targets.length() == 0) {
                return;
            }
            org.json.JSONObject t = targets.getJSONObject(0);
            String pcId = t.optString("pc_id", "");
            String host = t.optString("host", "");
            fPcName = pcId.contains("/") ? pcId.substring(0, pcId.indexOf('/')) : host;
            fHost = host;
        } catch (org.json.JSONException e) {
            CastingControlService.reportError(sessionId, "bad_start_session_payload");
            return;
        }
        // mDNS 发现是异步的：会话指令可能早于发现完成，后台轮询等待后再拉流
        final String fSessionId = sessionId;
        CastingControlService.setLastSessionId(sessionId);
        CastingControlService.reportSessionState(sessionId, "connecting");
        new Thread(new Runnable() {
            @Override
            public void run() {
                ComputerObject target = null;
                boolean serviceMode = getSharedPreferences(CastingControlService.PREFS, MODE_PRIVATE)
                        .getString(CastingControlService.KEY_URL, "").trim().length() > 0;
                if (serviceMode) {
                    // 服务模式：CS 是权威地址来源——只做一次本地快速匹配，
                    // 未命中立即用指令地址构造（不再等 mDNS 重试，避免 18s 延迟）
                    target = findComputerForSession(fPcName, fHost);
                } else {
                    // 直连模式：mDNS 发现异步，保留轮询等待
                    for (int attempt = 0; attempt < 6; attempt++) {
                        target = findComputerForSession(fPcName, fHost);
                        if (target != null) {
                            break;
                        }
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                // mDNS 不可用时直接信任 CS 指令里的地址（CS 是权威地址来源）
                if (target == null && fHost != null && !fHost.isEmpty()) {
                    ComputerDetails direct = new ComputerDetails();
                    direct.uuid = ("CS-" + fHost).toUpperCase(java.util.Locale.US);
                    direct.name = (fPcName == null || fPcName.isEmpty()) ? fHost : fPcName;
                    try {
                        direct.activeAddress = new ComputerDetails.AddressTuple(fHost, 47989);
                    } catch (IllegalArgumentException e) {
                        return;
                    }
                    direct.httpsPort = 47984;
                    direct.state = ComputerDetails.State.ONLINE;
                    direct.pairState = PairState.NOT_PAIRED;
                    // 恢复上次配对成功的证书（服务模式停用轮询后缓存不可用）
                    java.security.cert.X509Certificate known = loadKnownCert();
                    if (known != null) {
                        direct.serverCert = known;
                    }
                    target = new ComputerObject(direct);
                }
                if (target != null) {
                    setCastStatus("正在连接 " + target.details.name + "…");
                }
                final ComputerObject fTarget = target;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fTarget != null && fTarget.details != null) {
                            Toast.makeText(PcView.this, "投屏到 " + fTarget.details.name, Toast.LENGTH_SHORT).show();
                            doDirectStream(fTarget.details, false);
                        } else {
                            Toast.makeText(PcView.this, "找不到投屏目标电脑", Toast.LENGTH_LONG).show();
                            CastingControlService.reportError(fSessionId, "pc_not_found: " + fPcName);
                        }
                    }
                });
            }
        }).start();
    }

    private ComputerObject findComputerForSession(String pcName, String host) {
        ComputerObject onlyOnline = null;
        int onlineCount = 0;
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject co = (ComputerObject) pcGridAdapter.getItem(i);
            if (co == null || co.details == null || co.details.state == ComputerDetails.State.OFFLINE) {
                continue;
            }
            onlineCount++;
            onlyOnline = co;
            if (pcName != null && !pcName.isEmpty() && pcName.equalsIgnoreCase(co.details.name)) {
                return co;
            }
            if (host != null && !host.isEmpty()) {
                try {
                    String addr = ServerHelper.getCurrentAddressFromComputer(co.details).address;
                    if (addr != null && host.equalsIgnoreCase(addr)) {
                        return co;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return onlineCount == 1 ? onlyOnline : null;
    }

    private void registerCastingReceiver() {
        if (castReceiver != null && !castReceiverRegistered) {
            IntentFilter filter = new IntentFilter(CastingControlService.ACTION_EVENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(castReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(castReceiver, filter);
            }
            castReceiverRegistered = true;
        }
    }

    private void unregisterCastingReceiver() {
        if (castReceiver != null && castReceiverRegistered) {
            try {
                unregisterReceiver(castReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            castReceiverRegistered = false;
        }
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        registerCastingReceiver();
        if (!serviceMode) {
            startComputerUpdates();  // 服务模式由 CS 指令驱动，不做电脑探测
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        unregisterCastingReceiver();
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        final String pinStr = PairingManager.generatePinString();

                        // M2：把 PIN 上报会话服务，由同房间的 PC 助手代填 Sunshine（免人工输入）
                        CastingControlService.reportPairing(computer.name,
                                ServerHelper.getCurrentAddressFromComputer(computer).address, pinStr);

                        if (serviceMode) {
                            // 服务模式：配对全自动，不打扰用户（不显示 PIN 对话框）
                            setCastStatus("正在连接 " + computer.name + "…");
                        } else {
                            // Spin the dialog off in a thread because it blocks
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                    getResources().getString(R.string.pair_pairing_help), false);
                        }

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            ComputerDetails pairedRecord = managerBinder != null ? managerBinder.getComputer(computer.uuid) : null;
                            if (pairedRecord != null) {
                                pairedRecord.serverCert = pm.getPairedCert();
                            } else {
                                computer.serverCert = pm.getPairedCert();
                            }
                            // 持久化证书：服务模式无轮询，重启后免重配
                            try {
                                saveKnownCert(pm.getPairedCert().getEncoded());
                            } catch (Exception ignored) {
                            }

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doDirectStream(computer, true);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    /**
     * Click a computer card to stream the desktop app directly, without
     * showing the app selection page. Falls back to the app list when the
     * host does not expose a desktop-like app.
     */
    private void doDirectStream(final ComputerDetails computer, final boolean newlyPaired) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        // Always use the freshest computer record from the manager: right after
        // pairing the cached copy still carries the old (unpaired) certificate.
        final ComputerDetails target = (managerBinder.getComputer(computer.uuid) != null)
                ? managerBinder.getComputer(computer.uuid)
                : computer;

        new Thread(new Runnable() {
            @Override
            public void run() {
                // M2.2：服务模式停用 CMS 轮询后，pairState 缓存不可信——实时向 Sunshine 查询
                boolean paired = target.pairState == PairState.PAIRED;
                if (!paired) {
                    try {
                        NvHTTP probe = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(target),
                                target.httpsPort, managerBinder.getUniqueId(), target.serverCert,
                                PlatformBinding.getCryptoProvider(PcView.this));
                        paired = probe.getPairState() == PairState.PAIRED;
                        if (paired && probe.getPairingManager().getPairedCert() != null) {
                            target.serverCert = probe.getPairingManager().getPairedCert();
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!paired && !newlyPaired) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            doPair(target);
                        }
                    });
                    return;
                }
                NvApp desktopApp = null;
                try {
                    NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(target), target.httpsPort,
                            managerBinder.getUniqueId(), target.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(http.getAppListRaw()));
                    for (NvApp app : applist) {
                        String name = app.getAppName();
                        if (name == null) {
                            continue;
                        }
                        if (name.equalsIgnoreCase("Desktop") || name.contains("桌面") || name.contains("投屏")) {
                            desktopApp = app;
                            break;
                        }
                    }
                    if (desktopApp == null && !applist.isEmpty()) {
                        // Host exposes apps but none is named like a desktop:
                        // use the first one instead of showing a picker.
                        desktopApp = applist.get(0);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final NvApp selected = desktopApp;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (selected != null) {
                            ServerHelper.doStart(PcView.this, selected, target, managerBinder);
                        } else {
                            // M2.2：应用列表拉取失败多为未配对（证书跨 Sunshine 实例失效，缓存不可信）。
                            // 走配对流程：PIN 经会话服务上报，PC 助手自动代填；配对成功后自动回到本方法拉流。
                            doPair(target);
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }
    
    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    doDirectStream(computer.details, false);
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}
