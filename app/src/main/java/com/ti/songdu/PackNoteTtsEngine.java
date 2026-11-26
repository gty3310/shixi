package com.ti.songdu;

import android.content.Context;

import java.io.File;

/**
 * v1.31.0 念注引擎实现：云端预生成音频包（本地 TTS 方案取消）。
 * prepare 只做本地查包——命中回调包内 mp3 文件；包未下载回
 * ERR_NOT_DOWNLOADED（框架据此停发后续触发，下载引导在阅读页）；
 * 包在但本条注不在包内回一般错误（框架只跳过本条）。
 */
public final class PackNoteTtsEngine implements NoteTtsEngine {

    @Override
    public boolean isReady(Context ctx) {
        return true; // 引擎本体恒可用；包级就绪在 prepare 里逐条判
    }

    @Override
    public void prepare(Context ctx, String bookId, String version, String blockKey,
                        Callback cb) {
        File f = TtsPackStore.fileFor(ctx, bookId, version, blockKey);
        if (f != null) {
            cb.onReady(f);
            return;
        }
        if (!TtsPackStore.isReady(ctx, bookId, version)) {
            cb.onError(TtsPackStore.ERR_NOT_DOWNLOADED);
        } else {
            cb.onError("本条注不在音频包内");
        }
    }
}
