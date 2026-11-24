package com.limelight.casting;

import android.view.Surface;

interface ITileRenderer {
    /** 在指定 Surface 上开始渲染一路 Moonlight 流；config 为 JSON 字符串。 */
    void start(in Surface surface, String configJson);
    void stop();
}
