package com.limelight.casting;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONObject;

import java.io.StringReader;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * 多路分屏的单路渲染进程（每个进程独立的 moonlight-core 实例）。
 *
 * Moonlight-core（JNI 全局单例）同进程只能跑一路流，因此每路 tile 由本进程内的
 * 子类（Tile1Service..Tile4Service）承载，宿主 GridActivity 通过 Binder 传入 Surface。
 */
public abstract class TileRendererService extends Service {

    private static final String TAG = "TileRenderer";

    private NvConnection conn;
    private MediaCodecDecoderRenderer decoderRenderer;
    private final ITileRenderer.Stub binder = new ITileRenderer.Stub() {
        @Override
        public void start(android.view.Surface surface, String configJson) {
            startStream(surface, configJson);
        }

        @Override
        public void stop() {
            stopStream();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopStream();
        super.onDestroy();
    }

    private void startStream(android.view.Surface surface, String configJson) {
        stopStream();
        Thread t = new Thread(() -> {
            try {
                JSONObject cfg = new JSONObject(configJson);
                String host = cfg.optString("host");
                int port = cfg.optInt("port", 47989);
                int httpsPort = cfg.optInt("httpsPort", 47984);
                int width = cfg.optInt("width", 640);
                int height = cfg.optInt("height", 360);
                String certB64 = cfg.optString("cert", "");
                String uniqueId = cfg.optString("uniqueId", "");
                boolean withAudio = cfg.optBoolean("audio", false);

                if (surface == null || !surface.isValid()) {
                    Log.w(TAG, "surface invalid for tile process " + getClass().getSimpleName());
                    return;
                }

                X509Certificate cert = null;
                if (!certB64.isEmpty()) {
                    try {
                        byte[] der = android.util.Base64.decode(certB64, android.util.Base64.NO_WRAP);
                        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                        cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
                    } catch (Exception ignored) {
                    }
                }

                NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple(host, port), httpsPort,
                        uniqueId, cert, PlatformBinding.getCryptoProvider(this));
                NvApp desktopApp = null;
                List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(http.getAppListRaw()));
                for (NvApp app : applist) {
                    String name = app.getAppName();
                    if (name != null && (name.equalsIgnoreCase("Desktop") || name.contains("桌面") || name.contains("投屏"))) {
                        desktopApp = app;
                        break;
                    }
                }
                if (desktopApp == null && !applist.isEmpty()) {
                    desktopApp = applist.get(0);
                }
                if (desktopApp == null) {
                    Log.w(TAG, "no app found on " + host);
                    return;
                }

                // 每个渲染进程独立初始化 MediaCodec 能力表（Game 里在 Activity 早期做）
                com.limelight.binding.video.MediaCodecHelper.initialize(this, "");

                StreamConfiguration config = new StreamConfiguration.Builder()
                        .setResolution(width, height)
                        .setApp(desktopApp)
                        .build();

                PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
                decoderRenderer = new MediaCodecDecoderRenderer(this, prefs,
                        new CrashListener() {
                            @Override
                            public void notifyCrash(Exception e) {
                            }
                        }, 0, false, false, "", null);
                decoderRenderer.setRenderSurface(surface);

                final String hostName = host;
                NvConnectionListener listener = new NvConnectionListener() {
                    @Override
                    public void stageStarting(String stage) {
                    }

                    @Override
                    public void stageComplete(String stage) {
                    }

                    @Override
                    public void stageFailed(String stage, int portFlags, int errorCode) {
                        Log.w(TAG, "stage failed " + stage + " err=" + errorCode);
                        notifyTileState(hostName, "failed:" + errorCode);
                    }

                    @Override
                    public void connectionStarted() {
                        Log.i(TAG, "tile connected to " + hostName);
                        notifyTileState(hostName, "connected");
                    }

                    @Override
                    public void connectionTerminated(int errorCode) {
                        Log.i(TAG, "tile terminated " + hostName + " err=" + errorCode);
                    }

                    @Override
                    public void connectionStatusUpdate(int connectionStatus) {
                    }

                    @Override
                    public void displayMessage(String message) {
                    }

                    @Override
                    public void displayTransientMessage(String message) {
                    }

                    @Override
                    public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
                    }

                    @Override
                    public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
                    }

                    @Override
                    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
                    }

                    @Override
                    public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
                    }

                    @Override
                    public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
                    }
                };

                AudioRenderer audio = null;
                if (withAudio) {
                    com.limelight.binding.audio.AndroidAudioRenderer ar =
                            new com.limelight.binding.audio.AndroidAudioRenderer(this, false);
                    audio = ar;
                }

                conn = new NvConnection(getApplicationContext(),
                        new ComputerDetails.AddressTuple(host, port), httpsPort, uniqueId, config,
                        PlatformBinding.getCryptoProvider(this), cert);
                conn.start(audio, decoderRenderer, listener);
            } catch (Exception e) {
                Log.w(TAG, "tile start failed", e);
            }
        });
        t.start();
    }

    private void notifyTileState(String host, String state) {
        android.content.Intent i = new android.content.Intent(GridActivity.ACTION_TILE_STATE);
        i.setPackage(getPackageName());
        i.putExtra("host", host);
        i.putExtra("state", state);
        sendBroadcast(i);
    }

    private void stopStream() {
        if (conn != null) {
            try {
                conn.stop();
            } catch (Exception ignored) {
            }
            conn = null;
        }
        if (decoderRenderer != null) {
            try {
                decoderRenderer.stop();
            } catch (Exception ignored) {
            }
            decoderRenderer = null;
        }
    }
}
