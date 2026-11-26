package com.ti.songdu;

import android.content.Context;

import java.io.File;

/**
 * v1.31.0 念注引擎接口（引擎选型已拍板：云端预生成音频包；本地 TTS 取消）。
 *
 * 契约：
 *  - 输入是「书＋注释版本＋注块键」：注块键＝注数据条目键，即该注块块首句
 *    的句 id（与 App 注释数据、云端打包线 blocks 清单、包内 manifest.json
 *    的 key 四方同口径）。
 *  - 输出是手机本地一个可直接播放的音频文件（包内 mp3，自然语速）；
 *    语速不由引擎处理——框架在播放时用 PlaybackParams 按当前听读倍速
 *    统一施加，与正文录音同口径。
 *  - 包未下载时 prepare 回调 onError(TtsPackStore.ERR_NOT_DOWNLOADED)，
 *    框架据此停发后续触发（不逐条重试、不产生连串停顿）；下载引导由
 *    阅读页在开启听法时发起（查包→确认大小→带进度下载→成功才开）。
 *  - 包已下但本条注不在包内 → onError(其他文案)，框架只跳过本条。
 *  - 回调线程不限，框架侧一律 post 回主线程处理。
 *  - isReady 只表示引擎本体可用（包式引擎恒 true；包级就绪在 prepare
 *    里按书＋版本逐条判，见上）。
 */
public interface NoteTtsEngine {

    interface Callback {
        void onReady(File audioFile);

        void onError(String message);
    }

    /** 引擎本体是否可用（包式引擎恒 true） */
    boolean isReady(Context ctx);

    /** 按书＋版本＋注块键取本地音频；命中回调 onReady(文件)，未命中 onError */
    void prepare(Context ctx, String bookId, String version, String blockKey, Callback cb);

    /** 释放引擎资源（服务销毁时调用）；默认无操作 */
    default void release() {
    }
}
